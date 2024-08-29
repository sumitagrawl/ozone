/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.ozone.om.ratis;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.apache.hadoop.hdds.utils.db.BatchOperation;
import org.apache.hadoop.hdds.utils.db.RDBBatchOperation;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.OzoneManagerPrepareState;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.lock.OMLockDetails;
import org.apache.hadoop.ozone.om.ratis.utils.OzoneManagerRatisUtils;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.protocolPB.OzoneManagerRequestHandler;
import org.apache.hadoop.ozone.protocolPB.RequestHandler;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.ratis.server.protocol.TermIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The OM StateMachine is the state machine for OM Ratis server. It is
 * responsible for applying ratis committed transactions to
 * {@link OzoneManager}.
 */
public class OzoneManagerRequestExecutor {

  public static final Logger LOG =
      LoggerFactory.getLogger(OzoneManagerRequestExecutor.class);
  private final OzoneManager ozoneManager;
  private RequestHandler handler;
  private final String threadPrefix;
  private final AtomicLong cacheIndex = new AtomicLong();
  private final AtomicLong currentTerm = new AtomicLong(1);
  private final ExecutorPipeline[] executorPipelines;

  public OzoneManagerRequestExecutor(OzoneManagerRatisServer ratisServer) throws IOException {
    this.ozoneManager = ratisServer.getOzoneManager();
    this.threadPrefix = ozoneManager.getThreadNamePrefix();

    this.handler = new OzoneManagerRequestHandler(ozoneManager);

    ThreadFactory build = new ThreadFactoryBuilder().setDaemon(true)
        .setNameFormat(threadPrefix +
            "OMStateMachineApplyTransactionThread - %d").build();
    executorPipelines = new ExecutorPipeline[2];
    executorPipelines[0] = new ExecutorPipeline(10, this::runExecuteCommand);
    executorPipelines[1] = new ExecutorPipeline(1, this::dbUpdateBatchCommand);
  }

  /**
   * Validate the incoming update request.
   * @throws IOException when validation fails
   */
  public void validate(OMRequest omRequest) throws IOException {
    handler.validateRequest(omRequest);

    // validate prepare state
    OzoneManagerProtocolProtos.Type cmdType = omRequest.getCmdType();
    OzoneManagerPrepareState prepareState = ozoneManager.getPrepareState();
    if (cmdType == OzoneManagerProtocolProtos.Type.Prepare) {
      // Must authenticate prepare requests here, since we must determine
      // whether or not to apply the prepare gate before proceeding with the
      // prepare request.
      UserGroupInformation userGroupInformation =
          UserGroupInformation.createRemoteUser(
              omRequest.getUserInfo().getUserName());
      if (ozoneManager.getAclsEnabled()
          && !ozoneManager.isAdmin(userGroupInformation)) {
        String message = "Access denied for user " + userGroupInformation
            + ". "
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
      throw new OMException(message,
          OMException.ResultCodes.NOT_SUPPORTED_OPERATION_WHEN_PREPARED);
    }
  }

  /*
   * Apply a committed log entry to the state machine.
   */
  public CompletableFuture<OMResponse> submit(OMRequest omRequest) {
    RequestContext requestContext = new RequestContext();
    requestContext.setRequest(omRequest);
    requestContext.setFuture(new CompletableFuture<>());
    try {
      String distributionKey = getDistributionKey(omRequest, ozoneManager);
      int idx = Math.abs(distributionKey.hashCode() % 10);
      //LOG.warn("sumit..entry...{}-{}-{}", omRequest.getCmdType().name(), distributionKey, idx);
      executorPipelines[0].submit(idx, requestContext);
    } catch (InterruptedException e) {
      requestContext.getFuture().completeExceptionally(e);
      Thread.currentThread().interrupt();
    }
    return requestContext.getFuture();
  }

  private void runExecuteCommand(Collection<RequestContext> ctxs) {
    for (RequestContext ctx : ctxs) {
      executeRequest(ctx);
    }
  }
  private void executeRequest(RequestContext ctx) {
    OMRequest request = ctx.getRequest();
    TermIndex termIndex = TermIndex.valueOf(currentTerm.get(), cacheIndex.incrementAndGet());
    ctx.setCacheIndex(termIndex);
    try {
      validate(request);
      LOG.warn("sumit..start...{}--{}", request.getCmdType().name(), termIndex.getIndex());
      final OMClientResponse omClientResponse = handler.handleWriteRequestImpl(request, termIndex);
      OMLockDetails omLockDetails = omClientResponse.getOmLockDetails();
      OMResponse omResponse = omClientResponse.getOMResponse();
      if (omLockDetails != null) {
        omResponse = omResponse.toBuilder().setOmLockDetails(omLockDetails.toProtobufBuilder()).build();
      }
      ctx.setResponse(omResponse);
      if (omResponse.getSuccess()) {
        OMRequest nextRequest = prepareDBupdateRequest(request, termIndex, omClientResponse);
        if (nextRequest != null) {
          ctx.setNextRequest(nextRequest);
        }
      }
    } catch (IOException e) {
      LOG.warn("Failed to write, Exception occurred ", e);
      ctx.setResponse(createErrorResponse(request, e));
    } catch (Throwable e) {
      LOG.warn("Failed to write, Exception occurred ", e);
      ctx.setResponse(createErrorResponse(request, new IOException(e)));
    } finally {
      LOG.warn("sumit..end...{}--{}", request.getCmdType().name(), termIndex.getIndex());
      if (ctx.getNextRequest() != null) {
        try {
          executorPipelines[1].submit(0, ctx);
          return;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      ctx.getFuture().complete(ctx.getResponse());
    }
  }

  private void dbUpdateBatchCommand(Collection<RequestContext> ctxs) {
    OzoneManagerProtocolProtos.PersistDbRequest.Builder reqBuilder
        = OzoneManagerProtocolProtos.PersistDbRequest.newBuilder();
    int count = ctxs.size();
    for (RequestContext ctx : ctxs) {
      //LOG.warn("sumit..start.DB.{}-{}", ctx.getRequest().getCmdType().name(), ctx.getCacheIndex().getIndex());
      OMRequest nextRequest = ctx.getNextRequest();
      for (OzoneManagerProtocolProtos.DBTableUpdate tblUpdates
          : nextRequest.getPersistDbRequest().getTableUpdatesList()) {
        OzoneManagerProtocolProtos.DBTableUpdate.Builder tblBuilder
            = OzoneManagerProtocolProtos.DBTableUpdate.newBuilder();
        tblBuilder.setTableName(tblUpdates.getTableName());
        tblBuilder.addAllRecords(tblUpdates.getRecordsList());
        reqBuilder.addTableUpdates(tblBuilder.build());
      }
      // TODO based on size of request, need merge, test purpose, add all
      --count;
      if (count == 0) {
        reqBuilder.setCacheIndex(nextRequest.getPersistDbRequest().getCacheIndex());
        OMRequest.Builder omReqBuilder = OMRequest.newBuilder().setPersistDbRequest(reqBuilder.build())
            .setCmdType(OzoneManagerProtocolProtos.Type.PersistDb).setClientId(nextRequest.getClientId());
        
        try {
          OMResponse dbUpdateRsp = sendDbUpdateRequest(omReqBuilder.build(), ctx.getCacheIndex());
          if (dbUpdateRsp != null) {
            for (RequestContext repCtx : ctxs) {
              repCtx.getFuture().complete(dbUpdateRsp);
            }
            continue;
          }
          for (RequestContext repCtx : ctxs) {
            LOG.warn("sumit..end.DB.{}-{}", repCtx.getRequest().getCmdType().name(), repCtx.getCacheIndex().getIndex());
            repCtx.getFuture().complete(repCtx.getResponse());
          }
        } catch (IOException e) {
          LOG.warn("Failed to write, Exception occurred ", e);
          for (RequestContext repCtx : ctxs) {
            LOG.warn("sumit..end.DB.Fail.{}-{}", repCtx.getRequest().getCmdType().name(), repCtx.getCacheIndex().getIndex());
            repCtx.getFuture().complete(createErrorResponse(ctx.getRequest(), e));
          }
        } catch (Throwable e) {
          LOG.warn("Failed to write, Exception occurred ", e);
          for (RequestContext repCtx : ctxs) {
            LOG.warn("sumit..end.DB.Fail.{}-{}", repCtx.getRequest().getCmdType().name(), repCtx.getCacheIndex().getIndex());
            repCtx.getFuture().complete(createErrorResponse(ctx.getRequest(), new IOException(e)));

          }
        }
      }
    }
  }
  private void dbUpdate(RequestContext ctx) {
    OMResponse finalResponse = ctx.getResponse();
    try {
      OMResponse dbUpdateRsp = sendDbUpdateRequest(ctx.getNextRequest(), ctx.getCacheIndex());
      if (dbUpdateRsp != null) {
        // in case of failure, return db update failure response
        finalResponse = dbUpdateRsp;
      }
    } catch (IOException e) {
      LOG.warn("Failed to write, Exception occurred ", e);
      finalResponse = createErrorResponse(ctx.getRequest(), e);
    } catch (Throwable e) {
      LOG.warn("Failed to write, Exception occurred ", e);
      finalResponse = createErrorResponse(ctx.getRequest(), new IOException(e));
    }
    ctx.getFuture().complete(finalResponse);
  }

  private OMResponse sendDbUpdateRequest(OMRequest nextRequest, TermIndex termIndex) throws Exception {
    try {
      OMResponse response = ozoneManager.getOmRatisServer().submitRequest(nextRequest, termIndex.getIndex());
      if (!response.getSuccess()) {
        // TODO: retry on failure for db operation, otherwise next operation may have incorrect value
        // if retry fails, need reject all request in all queue, and restart
        refreshCache(ozoneManager, termIndex.getIndex(), nextRequest.getPersistDbRequest().getTableUpdatesList());
        return response;
      }
    } catch (Exception ex) {
      // TODO: retry on failure for db operation, otherwise next operation may have incorrect value
      // if retry fails, need reject all request in all queue, and restart handler.
      refreshCache(ozoneManager, termIndex.getIndex(), nextRequest.getPersistDbRequest().getTableUpdatesList());
      throw ex;
    }
    return null;
  }
  private OMRequest prepareDBupdateRequest(OMRequest request, TermIndex termIndex, OMClientResponse omClientResponse)
      throws IOException {
    try (BatchOperation batchOperation = ozoneManager.getMetadataManager().getStore()
        .initBatchOperation()) {
      omClientResponse.checkAndUpdateDB(ozoneManager.getMetadataManager(), batchOperation);
      // get db update and raise request to flush
      OzoneManagerProtocolProtos.PersistDbRequest.Builder reqBuilder
          = OzoneManagerProtocolProtos.PersistDbRequest.newBuilder();
      Map<String, Map<byte[], byte[]>> cachedDbTxs = ((RDBBatchOperation) batchOperation).getCachedTransaction();
      for (Map.Entry<String, Map<byte[], byte[]>> tblEntry : cachedDbTxs.entrySet()) {
        OzoneManagerProtocolProtos.DBTableUpdate.Builder tblBuilder
            = OzoneManagerProtocolProtos.DBTableUpdate.newBuilder();
        tblBuilder.setTableName(tblEntry.getKey());
        for (Map.Entry<byte[], byte[]> kvEntry : tblEntry.getValue().entrySet()) {
          OzoneManagerProtocolProtos.DBTableRecord.Builder kvBuild
              = OzoneManagerProtocolProtos.DBTableRecord.newBuilder();
          kvBuild.setKey(ByteString.copyFrom(kvEntry.getKey()));
          if (kvEntry.getValue() != null) {
            kvBuild.setValue(ByteString.copyFrom(kvEntry.getValue()));
          }
          tblBuilder.addRecords(kvBuild.build());
        }
        reqBuilder.addTableUpdates(tblBuilder.build());
      }
      reqBuilder.setCacheIndex(termIndex.getIndex());
      OMRequest.Builder omReqBuilder = OMRequest.newBuilder().setPersistDbRequest(reqBuilder.build())
          .setCmdType(OzoneManagerProtocolProtos.Type.PersistDb).setClientId(request.getClientId());
      return omReqBuilder.build();
    }
  }

  private void refreshCache(
      OzoneManager om, long index, List<OzoneManagerProtocolProtos.DBTableUpdate> tableUpdatesList)
      throws IOException {
    String bucketTableName = om.getMetadataManager().getBucketTable().getName();
    String volTableName = om.getMetadataManager().getVolumeTable().getName();
    for (OzoneManagerProtocolProtos.DBTableUpdate tblUpdates : tableUpdatesList) {
      if (tblUpdates.getTableName().equals(bucketTableName)) {
        List<OzoneManagerProtocolProtos.DBTableRecord> recordsList = tblUpdates.getRecordsList();
        for (OzoneManagerProtocolProtos.DBTableRecord record : recordsList) {
          om.getMetadataManager().getBucketTable().resetCache(record.getKey().toByteArray(), index);
        }
      }
      if (tblUpdates.getTableName().equals(volTableName)) {
        List<OzoneManagerProtocolProtos.DBTableRecord> recordsList = tblUpdates.getRecordsList();
        for (OzoneManagerProtocolProtos.DBTableRecord record : recordsList) {
          om.getMetadataManager().getVolumeTable().resetCache(record.getKey().toByteArray(), index);
        }
      }
      om.getMetadataManager().getTable(tblUpdates.getTableName())
          .cleanupCache(Collections.singletonList(index));
    }
  }

  private OMResponse createErrorResponse(OMRequest omRequest, IOException exception) {
    OMResponse.Builder omResponseBuilder = OMResponse.newBuilder()
        .setStatus(OzoneManagerRatisUtils.exceptionToResponseStatus(exception))
        .setCmdType(omRequest.getCmdType())
        .setTraceID(omRequest.getTraceID())
        .setSuccess(false);
    if (exception.getMessage() != null) {
      omResponseBuilder.setMessage(exception.getMessage());
    }
    OMResponse omResponse = omResponseBuilder.build();
    return omResponse;
  }

  public void notifyTermIndex(TermIndex termIndex) {
    long lastIdx = cacheIndex.get();
    cacheIndex.set(termIndex.getIndex() > lastIdx ? termIndex.getIndex() : lastIdx);
    currentTerm.set(termIndex.getTerm());
  }

  /**
   * request context.
   */
  public static final class RequestContext {
    private OMRequest request;
    private OMResponse response;
    private TermIndex cacheIndex;
    private CompletableFuture<OMResponse> future;
    private OMRequest nextRequest;

    private RequestContext() {
    }

    public OMRequest getRequest() {
      return request;
    }

    public void setRequest(OMRequest request) {
      this.request = request;
    }

    public OMResponse getResponse() {
      return response;
    }

    public void setResponse(OMResponse response) {
      this.response = response;
    }

    public TermIndex getCacheIndex() {
      return cacheIndex;
    }

    public void setCacheIndex(TermIndex cacheIndex) {
      this.cacheIndex = cacheIndex;
    }

    public CompletableFuture<OMResponse> getFuture() {
      return future;
    }

    public void setFuture(CompletableFuture<OMResponse> future) {
      this.future = future;
    }

    public OMRequest getNextRequest() {
      return nextRequest;
    }

    public void setNextRequest(OMRequest nextRequest) {
      this.nextRequest = nextRequest;
    }
  }

  /**
   * Executor pipeline.
   */
  public static class ExecutorPipeline {
    private Thread[] threadPool;
    private List<BlockingQueue<RequestContext>> queues;
    private Consumer<Collection<RequestContext>> handler = null;
    private AtomicBoolean isRunning = new AtomicBoolean(true);

    private ExecutorPipeline(int poolSize) {
      threadPool = new Thread[poolSize];
      queues = new ArrayList<>(poolSize);
      for (int i = 0; i < poolSize; ++i) {
        LinkedBlockingQueue<RequestContext> queue = new LinkedBlockingQueue<>(1000);
        queues.add(queue);
        threadPool[i] = new Thread(() -> execute(queue), "OMExecutor-" + i);
        threadPool[i].start();
      }
    }
    public ExecutorPipeline(int poolSize, Consumer<Collection<RequestContext>> handler) {
      this(poolSize);
      this.handler = handler;
    }
    public void submit(int idx, RequestContext ctx) throws InterruptedException {
      if (idx >= threadPool.length) {
        return;
      }
      queues.get(idx).put(ctx);
    }

    private void execute(BlockingQueue<RequestContext> q) {
      while (isRunning.get()) {
        try {
          List<RequestContext> entries = new LinkedList<>();
          RequestContext ctx = q.take();
          entries.add(ctx);
          q.drainTo(entries);
          handler.accept(entries);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
  }

  private static String getDistributionKey(OMRequest omRequest, OzoneManager ozoneManager) {
    OzoneManagerProtocolProtos.Type cmdType = omRequest.getCmdType();
    OzoneManagerProtocolProtos.KeyArgs keyArgs;

    switch (cmdType) {
    case CreateVolume:
      return omRequest.getCreateVolumeRequest().getVolumeInfo().getVolume();
    case SetVolumeProperty:
      return omRequest.getSetVolumePropertyRequest().getVolumeName();
    case DeleteVolume:
      return omRequest.getDeleteVolumeRequest().getVolumeName();
    case CreateBucket:
      return omRequest.getCreateBucketRequest().getBucketInfo().getBucketName();
    case DeleteBucket:
      return omRequest.getDeleteBucketRequest().getBucketName();
    case SetBucketProperty:
      return omRequest.getSetBucketPropertyRequest().getBucketArgs().getBucketName();
    case AddAcl:
      return omRequest.getAddAclRequest().getObj().getPath();
    case RemoveAcl:
      return omRequest.getRemoveAclRequest().getObj().getPath();
    case SetAcl:
      return omRequest.getSetAclRequest().getObj().getPath();
    case GetDelegationToken:
    case CancelDelegationToken:
    case RenewDelegationToken:
      return "delegation";
    case GetS3Secret:
      return omRequest.getGetS3SecretRequest().getKerberosID();
    case SetS3Secret:
      return omRequest.getSetS3SecretRequest().getAccessId();
    case RevokeS3Secret:
      return omRequest.getRevokeS3SecretRequest().getKerberosID();
    case FinalizeUpgrade:
    case Prepare:
    case CancelPrepare:
    case SetRangerServiceVersion:
    case EchoRPC:
    case QuotaRepair:
    case AbortExpiredMultiPartUploads:
      return "om";
    case PurgeKeys:
    case PurgeDirectories:
    case SnapshotMoveDeletedKeys:
    case SnapshotPurge:
    case SetSnapshotProperty:
    case DeleteOpenKeys:
    case PersistDb:
      return "internal";
    case CreateTenant:
      return omRequest.getCreateTenantRequest().getVolumeName();
    case DeleteTenant:
      return omRequest.getDeleteTenantRequest().getTenantId();
    case TenantAssignUserAccessId:
      return omRequest.getTenantAssignUserAccessIdRequest().getTenantId();
    case TenantRevokeUserAccessId:
      return omRequest.getTenantRevokeUserAccessIdRequest().getTenantId();
    case TenantAssignAdmin:
      return omRequest.getTenantAssignAdminRequest().getTenantId();
    case TenantRevokeAdmin:
      return omRequest.getTenantRevokeAdminRequest().getTenantId();
    case CreateSnapshot:
      return omRequest.getCreateSnapshotRequest().getBucketName();
    case DeleteSnapshot:
      return omRequest.getDeleteSnapshotRequest().getBucketName();
    case RenameSnapshot:
      return omRequest.getRenameSnapshotRequest().getBucketName();
    case RecoverLease:
      return ozoneManager.getMetadataManager().getOzoneKey(omRequest.getRecoverLeaseRequest().getVolumeName(),
          omRequest.getRecoverLeaseRequest().getBucketName(),
          omRequest.getRecoverLeaseRequest().getKeyName());
    case CreateDirectory:
      keyArgs = omRequest.getCreateDirectoryRequest().getKeyArgs();
      return ozoneManager.getMetadataManager().getOzoneKey(keyArgs.getVolumeName(), keyArgs.getBucketName(),
          keyArgs.getKeyName());
    case CreateFile:
      keyArgs = omRequest.getCreateFileRequest().getKeyArgs();
      return ozoneManager.getMetadataManager().getOzoneKey(keyArgs.getVolumeName(), keyArgs.getBucketName(),
          keyArgs.getKeyName());
    case CreateKey:
      keyArgs = omRequest.getCreateKeyRequest().getKeyArgs();
      return ozoneManager.getMetadataManager().getOzoneKey(keyArgs.getVolumeName(), keyArgs.getBucketName(),
          keyArgs.getKeyName());
    case AllocateBlock:
      keyArgs = omRequest.getAllocateBlockRequest().getKeyArgs();
      return ozoneManager.getMetadataManager().getOzoneKey(keyArgs.getVolumeName(), keyArgs.getBucketName(),
          keyArgs.getKeyName());
    case CommitKey:
      keyArgs = omRequest.getCommitKeyRequest().getKeyArgs();
      return ozoneManager.getMetadataManager().getOzoneKey(keyArgs.getVolumeName(), keyArgs.getBucketName(),
          keyArgs.getKeyName());
    case DeleteKey:
      keyArgs = omRequest.getDeleteKeyRequest().getKeyArgs();
      return ozoneManager.getMetadataManager().getOzoneKey(keyArgs.getVolumeName(), keyArgs.getBucketName(),
          keyArgs.getKeyName());
    case DeleteKeys:
      OzoneManagerProtocolProtos.DeleteKeyArgs deleteKeyArgs = omRequest.getDeleteKeysRequest().getDeleteKeys();
      return ozoneManager.getMetadataManager().getOzoneKey(deleteKeyArgs.getVolumeName(),
          deleteKeyArgs.getBucketName(), "");
    case RenameKey:
      keyArgs = omRequest.getRenameKeyRequest().getKeyArgs();
      return ozoneManager.getMetadataManager().getOzoneKey(keyArgs.getVolumeName(), keyArgs.getBucketName(),
          keyArgs.getKeyName());
    case RenameKeys:
      OzoneManagerProtocolProtos.RenameKeysArgs renameKeysArgs =
          omRequest.getRenameKeysRequest().getRenameKeysArgs();
      return ozoneManager.getMetadataManager().getOzoneKey(renameKeysArgs.getVolumeName(),
          renameKeysArgs.getBucketName(), "");
    case InitiateMultiPartUpload:
      keyArgs = omRequest.getInitiateMultiPartUploadRequest().getKeyArgs();
      return ozoneManager.getMetadataManager().getOzoneKey(keyArgs.getVolumeName(),
          keyArgs.getBucketName(), keyArgs.getKeyName());
    case CommitMultiPartUpload:
      keyArgs = omRequest.getCommitMultiPartUploadRequest().getKeyArgs();
      return ozoneManager.getMetadataManager().getOzoneKey(keyArgs.getVolumeName(),
          keyArgs.getBucketName(), keyArgs.getKeyName());
    case AbortMultiPartUpload:
      keyArgs = omRequest.getAbortMultiPartUploadRequest().getKeyArgs();
      return ozoneManager.getMetadataManager().getOzoneKey(keyArgs.getVolumeName(),
          keyArgs.getBucketName(), keyArgs.getKeyName());
    case CompleteMultiPartUpload:
      keyArgs = omRequest.getCompleteMultiPartUploadRequest().getKeyArgs();
      return ozoneManager.getMetadataManager().getOzoneKey(keyArgs.getVolumeName(),
          keyArgs.getBucketName(), keyArgs.getKeyName());
    case SetTimes:
      keyArgs = omRequest.getSetTimesRequest().getKeyArgs();
      return ozoneManager.getMetadataManager().getOzoneKey(keyArgs.getVolumeName(), keyArgs.getBucketName(),
          keyArgs.getKeyName());
    default:
      return "";
    }
  }
}
