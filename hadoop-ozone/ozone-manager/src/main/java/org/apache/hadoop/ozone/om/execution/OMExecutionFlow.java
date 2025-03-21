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

package org.apache.hadoop.ozone.om.execution;

import static org.apache.hadoop.ipc.RpcConstants.DUMMY_CLIENT_ID;
import static org.apache.hadoop.ipc.RpcConstants.INVALID_CALL_ID;
import static org.apache.hadoop.ozone.util.MetricUtil.captureLatencyNs;

import com.google.common.base.Preconditions;
import com.google.protobuf.ServiceException;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.apache.hadoop.ipc.ProtobufRpcEngine;
import org.apache.hadoop.ozone.om.OMPerformanceMetrics;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.OzoneManagerPrepareState;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.execution.factory.OmRequestFactory;
import org.apache.hadoop.ozone.om.execution.flowcontrol.RequestContext;
import org.apache.hadoop.ozone.om.execution.flowcontrol.RequestFlow;
import org.apache.hadoop.ozone.om.execution.flowcontrol.RequestFlowExecutor;
import org.apache.hadoop.ozone.om.helpers.OMAuditLogger;
import org.apache.hadoop.ozone.om.ratis.utils.OzoneManagerRatisUtils;
import org.apache.hadoop.ozone.om.request.OMClientRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.protocolPB.OzoneManagerRequestHandler;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * entry for execution flow for write request.
 */
public class OMExecutionFlow {
  private static final Logger LOG = LoggerFactory.getLogger(OMExecutionFlow.class);
  private static final long PREPARE_WAIT_RETRY_COUNT = 60;
  private static final long PREPARE_WAIT_TIME_RETRY = 1000;

  private final OzoneManager ozoneManager;
  private final OMPerformanceMetrics perfMetrics;
  private final AtomicLong requestInProgress = new AtomicLong(0);
  private final RequestFlowExecutor requestFlowExecutor;
  private final Supplier<Long> indexGenerator;

  public OMExecutionFlow(OzoneManager om) {
    this.ozoneManager = om;
    this.perfMetrics = ozoneManager.getPerfMetrics();
    this.indexGenerator = () -> 1L;
    this.requestFlowExecutor = new RequestFlowExecutor(ozoneManager, indexGenerator);
  }

  public void start() {
    requestFlowExecutor.start();
  }

  public void stop() {
    requestFlowExecutor.setEnabled(false);
    requestFlowExecutor.stop();
  }

  /**
   * External request handling.
   * 
   * @param omRequest the request
   * @return OMResponse the response of execution
   * @throws ServiceException the exception on execution
   */
  public OMResponse submit(OMRequest omRequest) throws ServiceException {
    if (PreRatisExecutionEnabler.optimizedFlow(omRequest, ozoneManager)) {
      return submitForExecution(omRequest);
    }
    return submitExecutionToRatis(omRequest);
  }

  private OMResponse submitExecutionToRatis(OMRequest request) throws ServiceException {
    // 1. create client request and preExecute
    OMClientRequest omClientRequest = null;
    final OMRequest requestToSubmit;
    try {
      omClientRequest = OzoneManagerRatisUtils.createClientRequest(request, ozoneManager);
      assert (omClientRequest != null);
      final OMClientRequest finalOmClientRequest = omClientRequest;
      requestToSubmit = captureLatencyNs(perfMetrics.getPreExecuteLatencyNs(),
          () -> finalOmClientRequest.preExecute(ozoneManager));
    } catch (IOException ex) {
      if (omClientRequest != null) {
        OMAuditLogger.log(omClientRequest.getAuditBuilder());
        omClientRequest.handleRequestFailure(ozoneManager);
      }
      return OzoneManagerRatisUtils.createErrorResponse(request, ex);
    }

    // 2. submit request to ratis
    OMResponse response = ozoneManager.getOmRatisServer().submitRequest(requestToSubmit);
    if (!response.getSuccess()) {
      omClientRequest.handleRequestFailure(ozoneManager);
    }
    return response;
  }

  private OMResponse submitForExecution(OMRequest omRequest) throws ServiceException {
    return submitForExecution(omRequest, getClientId(ProtobufRpcEngine.Server.getClientId()),
        getCallId(ProtobufRpcEngine.Server.getCallId()));
  }

  /**
   * perform execution before submission to ratis.
   * 1. prepare request context
   * 2. preProcess() request
   * 3. perform om upgrade validation
   * 4. submit to RequestFlowExecutor
   * 5. wait for completion of request flow execution and return response
   */
  private OMResponse submitForExecution(OMRequest omRequest, String clientId, long callId) throws ServiceException {
    requestInProgress.incrementAndGet();
    RequestContext requestContext = new RequestContext();
    requestContext.setRequest(omRequest);
    requestContext.setUuidClientId(clientId);
    requestContext.setCallId(callId);
    requestContext.setFuture(new CompletableFuture<>());
    CompletableFuture<OMResponse> f = requestContext.getFuture().whenComplete(
        (r, th) -> handleAfterExecution(requestContext, th));
    try {
      RequestFlow requestFlow = OmRequestFactory.createRequestExecutor(omRequest, ozoneManager);
      OMRequest request = captureLatencyNs(perfMetrics.getPreExecuteLatencyNs(),
          () -> requestFlow.preProcess(ozoneManager));
      // re-update modified request from pre-process
      requestContext.setRequest(request);
      requestContext.setRequestFlow(requestFlow);

      OzoneManagerRequestHandler.requestParamValidation(omRequest);

      requestContext.getRequestFlow().authorize(ozoneManager);

      // TODO perform lock
      validatePrepareState(requestContext.getRequest());
      ensurePreviousRequestCompletionForPrepare(requestContext.getRequest());
      requestFlowExecutor.submit(requestContext);

      try {
        return f.get();
      } catch (ExecutionException ex) {
        if (ex.getCause() != null) {
          throw new ServiceException(ex.getMessage(), ex.getCause());
        } else {
          throw new ServiceException(ex.getMessage(), ex);
        }
      }
    } catch (InterruptedException e) {
      LOG.error("Interrupted while handling request", e);
      Thread.currentThread().interrupt();
      throw new ServiceException(e.getMessage(), e);
    } catch (ServiceException e) {
      throw e;
    } catch (Throwable e) {
      LOG.error("Exception occurred while handling request", e);
      throw new ServiceException(e.getMessage(), e);
    }
  }

  private void ensurePreviousRequestCompletionForPrepare(OMRequest omRequest) throws InterruptedException {
    // if a prepare request, other request will be discarded before calling this
    if (omRequest.getCmdType() == OzoneManagerProtocolProtos.Type.Prepare) {
      for (int cnt = 0; cnt < PREPARE_WAIT_RETRY_COUNT && requestInProgress.get() > 1; ++cnt) {
        Thread.sleep(PREPARE_WAIT_TIME_RETRY);
      }
      if (requestInProgress.get() > 1) {
        LOG.warn("Still few requests {} are in progress, continuing with prepare", (requestInProgress.get() - 1));
      }
    }
  }

  private synchronized void validatePrepareState(OMRequest omRequest) throws IOException {
    OzoneManagerProtocolProtos.Type cmdType = omRequest.getCmdType();
    OzoneManagerPrepareState prepareState = ozoneManager.getPrepareState();
    if (cmdType == OzoneManagerProtocolProtos.Type.Prepare) {
      // Must authenticate prepare requests here, since we must determine
      // whether or not to apply the prepare gate before proceeding with the
      // prepare request.
      UserGroupInformation userGroupInformation =
          UserGroupInformation.createRemoteUser(omRequest.getUserInfo().getUserName());
      if (ozoneManager.getAclsEnabled() && !ozoneManager.isAdmin(userGroupInformation)) {
        String message = "Access denied for user " + userGroupInformation + ". "
            + "Superuser privilege is required to prepare ozone managers.";
        throw new OMException(message, OMException.ResultCodes.ACCESS_DENIED);
      } else {
        prepareState.enablePrepareGate();
      }
    }

    // In prepare mode, only prepare and cancel requests are allowed to go
    // through.
    if (!prepareState.requestAllowed(cmdType)) {
      String message = "Cannot apply write request " +
          omRequest.getCmdType().name() + " when OM is in prepare mode.";
      throw new OMException(message, OMException.ResultCodes.NOT_SUPPORTED_OPERATION_WHEN_PREPARED);
    }
  }

  private void handleAfterExecution(RequestContext ctx, Throwable th) {
    requestInProgress.decrementAndGet();
  }

  private String getClientId(byte[] clientIdBytes) {
    if (!ozoneManager.isTestSecureOmFlag()) {
      Preconditions.checkArgument(clientIdBytes != DUMMY_CLIENT_ID);
    }
    return UUID.nameUUIDFromBytes(clientIdBytes).toString();
  }

  private long getCallId(long callId) {
    if (!ozoneManager.isTestSecureOmFlag()) {
      Preconditions.checkArgument(callId != INVALID_CALL_ID);
    }
    return callId;
  }
}
