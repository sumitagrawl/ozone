/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.om.execution.flowcontrol;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.hadoop.hdds.conf.StorageUnit;
import org.apache.hadoop.ozone.om.OMConfigKeys;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.exceptions.OMLeaderNotReadyException;
import org.apache.hadoop.ozone.om.helpers.OMAuditLogger;
import org.apache.hadoop.ozone.om.ratis.utils.OzoneManagerRatisUtils;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.util.function.CheckedConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ozone manager request executor.
 * Execute in below stage:
 * 1. Request is executed (process) and submit to merge pool
 * 2. request is picked in bulk, db changes are merged to single request
 * 3. merged single request is submitted to ratis to persist in db
 */
public class RequestFlowExecutor {
  private static final Logger LOG = LoggerFactory.getLogger(RequestFlowExecutor.class);
  private final OzoneManager ozoneManager;
  private final Supplier<Long> indexGenerator;
  private final AtomicBoolean isEnabled = new AtomicBoolean(true);
  private final int ratisByteLimit;
  private final int mergeTaskPoolSize;
  private final AtomicInteger mergeCurrentPool = new AtomicInteger(0);
  private final PoolExecutor<RequestContext, RatisContext> requestMerger;
  private final ClientId clientId = ClientId.randomId();

  public RequestFlowExecutor(OzoneManager om, Supplier<Long> indexGenerator) {
    this.ozoneManager = om;
    this.indexGenerator = indexGenerator;
    this.mergeTaskPoolSize = om.getConfiguration().getInt("ozone.om.leader.merge.pool.size", 5);
    int mergeTaskQueueSize = om.getConfiguration().getInt("ozone.om.leader.merge.queue.size", 1000);
    requestMerger = new PoolExecutor<>(mergeTaskPoolSize, mergeTaskQueueSize,
        ozoneManager.getThreadNamePrefix() + "-RequestExecutorMerge", this::requestMergeCommand, this::ratisSubmit);
    int limit = (int) ozoneManager.getConfiguration().getStorageSize(
        OMConfigKeys.OZONE_OM_RATIS_LOG_APPENDER_QUEUE_BYTE_LIMIT,
        OMConfigKeys.OZONE_OM_RATIS_LOG_APPENDER_QUEUE_BYTE_LIMIT_DEFAULT,
        StorageUnit.BYTES);
    // always go to 80% of max limit for request as other header will be added
    this.ratisByteLimit = (int) (limit * 0.8);
  }

  public void start() {
    isEnabled.set(true);
    requestMerger.start();
  }
  public void stop() {
    requestMerger.stop();
  }

  public void setEnabled(boolean enabled) {
    isEnabled.set(enabled);
  }

  public boolean isEnabled() {
    return isEnabled.get();
  }

  public void submit(RequestContext ctx) throws InterruptedException, IOException {
    if (!isEnabled.get()) {
      rejectRequest(Collections.singletonList(ctx));
      return;
    }
    executeRequest(ctx, this::mergeSubmit);
  }

  private void rejectRequest(Collection<RequestContext> ctxs) {
    Throwable th;
    if (!ozoneManager.isLeaderReady()) {
      String peerId = ozoneManager.getOmRatisServer().getRaftPeerId().toString();
      th = new OMLeaderNotReadyException(peerId + " is not ready to process request yet.");
    } else {
      th = new OMException("Request processing is disabled due to error", OMException.ResultCodes.INTERNAL_ERROR);
    }
    handleBatchUpdateComplete(ctxs, th, null);
  }

  private void executeRequest(RequestContext ctx, CheckedConsumer<RequestContext, InterruptedException> nxtStage) {
    OMRequest request = ctx.getRequest();
    ExecutionContext executionContext = ExecutionContext.of(indexGenerator.get(), null);
    ctx.setExecutionContext(executionContext);
    boolean doProcessByRatis = false;
    Throwable ex = null;
    try {
      handleRequest(ctx, executionContext);
      if (ctx.getRequestFlow().getChangeRecorder().haveChanges()) {
        nxtStage.accept(ctx);
        doProcessByRatis = true;
      }
    } catch (IOException e) {
      LOG.warn("Failed to handle request execution, Exception occurred ", e);
      ex = e;
      ctx.setResponse(OzoneManagerRatisUtils.createErrorResponse(request, e));
    } catch (Throwable e) {
      LOG.warn("Failed to handle request execution, Exception occurred ", e);
      ex = e;
      ctx.setResponse(OzoneManagerRatisUtils.createErrorResponse(request, new IOException(e)));
    } finally {
      if (!doProcessByRatis) {
        // mark request handling complete as no further process required or failed
        handleBatchUpdateComplete(Collections.singletonList(ctx), ex, null);
      }
    }
  }

  private void handleRequest(RequestContext ctx, ExecutionContext exeCtx) throws IOException {
    RequestFlow requestFlow = ctx.getRequestFlow();
    try {
      OMClientResponse omClientResponse = requestFlow.process(ozoneManager, exeCtx);
      ctx.setResponse(omClientResponse.getOMResponse());
      if (!omClientResponse.getOMResponse().getSuccess()) {
        requestFlow.getChangeRecorder().clear();
      }
    } catch (Throwable th) {
      requestFlow.getChangeRecorder().clear();
      throw th;
    }
  }

  private void mergeSubmit(RequestContext ctx) throws InterruptedException {
    if (mergeTaskPoolSize == 0) {
      requestMergeCommand(Collections.singletonList(ctx), this::ratisSubmit);
      return;
    }
    int nxtIndex = Math.abs(mergeCurrentPool.getAndIncrement() % mergeTaskPoolSize);
    requestMerger.submit(nxtIndex, ctx);
  }

  private void requestMergeCommand(
      Collection<RequestContext> ctxs, CheckedConsumer<RatisContext, InterruptedException> ratisStage) {
    if (!isEnabled.get()) {
      rejectRequest(ctxs);
      return;
    }

    Collection<RequestContext> pendingList = ctxs;
    while (!pendingList.isEmpty()) {
      OMRequest.Builder builder = OMRequest.newBuilder();
      List<RequestContext> sendList = new ArrayList<>();
      pendingList = MergeRequestUtils.merge(ctxs, builder, sendList, ratisByteLimit);
      if (!sendList.isEmpty()) {
        OzoneManagerProtocolProtos.ExecutionControlRequest.Builder controlReq = prepareControlRequest(ctxs);
        prepareAndSendRequest(sendList, builder, controlReq, ratisStage);
      }
    }
  }
  
  private OzoneManagerProtocolProtos.ExecutionControlRequest.Builder prepareControlRequest(
      Collection<RequestContext> ctxs) {
    OzoneManagerProtocolProtos.ExecutionControlRequest.Builder controlReq
        = OzoneManagerProtocolProtos.ExecutionControlRequest.newBuilder();
    long maxIdx = ctxs.stream().mapToLong(e -> e.getExecutionContext().getIndex()).max().orElseGet(() -> 0L);
    controlReq.setIndex(maxIdx);
    return controlReq;
  }

  private void prepareAndSendRequest(
      List<RequestContext> sendList, OMRequest.Builder omReqBuilder,
      OzoneManagerProtocolProtos.ExecutionControlRequest.Builder controlReq,
      CheckedConsumer<RatisContext, InterruptedException> ratisStage) {
    omReqBuilder.setExecutionControlRequest(controlReq.build()).setClientId(clientId.toString());
    OMRequest reqBatch = omReqBuilder.build();
    try {
      ratisStage.accept(new RatisContext(sendList, reqBatch));
    } catch (InterruptedException e) {
      handleBatchUpdateComplete(sendList, e, null);
      Thread.currentThread().interrupt();
    }
  }

  private void ratisSubmit(RatisContext ctx) {
    ratisCommand(ctx);
  }
  private void ratisCommand(RatisContext ctx) {
    if (!isEnabled.get()) {
      rejectRequest(ctx.getRequestContexts());
      return;
    }

    List<RequestContext> sendList = ctx.getRequestContexts();
    OMRequest reqBatch = ctx.getRequest();
    try {
      OMResponse dbUpdateRsp = ozoneManager.getOmRatisServer().submitRequest(reqBatch, clientId,
          reqBatch.getExecutionControlRequest().getIndex());
      if (!dbUpdateRsp.getSuccess()) {
        throw new OMException(dbUpdateRsp.getMessage(),
            OMException.ResultCodes.values()[dbUpdateRsp.getStatus().ordinal()]);
      }
      handleBatchUpdateComplete(sendList, null, dbUpdateRsp.getLeaderOMNodeId());
    } catch (Throwable e) {
      LOG.warn("submit request to ratis handling failed, Exception occurred ", e);
      handleBatchUpdateComplete(sendList, e, null);
    }
  }

  /**
   * complete the request handling by marking future as complete.
   *
   * @param ctxs request context that needs to be marked completed
   * @param th the throwable exception
   * @param leaderOMNodeId the nodeIf of leader used for fallback if any exception at client
   */
  private void handleBatchUpdateComplete(Collection<RequestContext> ctxs, Throwable th, String leaderOMNodeId) {
    for (RequestContext ctx : ctxs) {
      // reset quota resource and release memory for change update
      ctx.getRequestFlow().getChangeRecorder().clear();

      if (th != null) {
        OMAuditLogger.log(ctx.getRequestFlow().getAuditBuilder(), ctx.getExecutionContext(), th);
        if (th instanceof IOException) {
          ctx.getFuture().complete(OzoneManagerRatisUtils.createErrorResponse(ctx.getRequest(), (IOException)th));
        } else {
          ctx.getFuture().complete(OzoneManagerRatisUtils.createErrorResponse(ctx.getRequest(), new IOException(th)));
        }
      } else {
        OMAuditLogger.log(ctx.getRequestFlow().getAuditBuilder(), ctx.getExecutionContext());
        OMResponse newRsp = ctx.getResponse();
        if (leaderOMNodeId != null) {
          newRsp = OMResponse.newBuilder(newRsp).setLeaderOMNodeId(leaderOMNodeId).build();
        }
        ctx.getFuture().complete(newRsp);
      }
    }
  }

  static class RatisContext {
    private final List<RequestContext> ctxs;
    private final OMRequest req;
    RatisContext(List<RequestContext> ctxs, OMRequest req) {
      this.ctxs = ctxs;
      this.req = req;
    }
    public List<RequestContext> getRequestContexts() {
      return ctxs;
    }

    public OMRequest getRequest() {
      return req;
    }
  }
}
