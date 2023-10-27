/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.ozone.container.common.transport.server.ratis;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.apache.hadoop.hdds.HddsConfigKeys;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.hdds.conf.DatanodeRatisServerConfig;
import org.apache.hadoop.hdds.conf.StorageUnit;
import org.apache.hadoop.hdds.scm.pipeline.PipelineID;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.apache.hadoop.util.concurrent.HadoopExecutors;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.metrics.RaftServerMetrics;
import org.apache.ratis.server.metrics.RaftServerMetricsImpl;
import org.apache.ratis.statemachine.StateMachine;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.ratis.server.metrics.RaftServerMetricsImpl.REQUEST_MEGA_BYTE_SIZE;
import static org.apache.ratis.server.metrics.RaftServerMetricsImpl.REQUEST_QUEUE_SIZE;

/**
 * Health monitor for container state machine.
 */
public class CSHealthMonitor implements Runnable {
  static final Logger LOG =
      LoggerFactory.getLogger(CSHealthMonitor.class);
  private final XceiverServerRatis ratisServer;
  private final long followerSyncDiffThreshold;
  private final long pendingRequestCountThreshold;
  private final long pendingRequestSizeThreshold;
  private final long cacheMissPercentThreshold;
  private final long pendingReqMaxMemory;
  private ScheduledExecutorService executorService;

  public CSHealthMonitor(
      XceiverServerRatis ratisServer, ConfigurationSource conf) {
    this.ratisServer = ratisServer;
    int numPendingRequests = conf
        .getObject(DatanodeRatisServerConfig.class)
        .getLeaderNumPendingRequests();
    long pendingRequestsBytesLimit = (long)conf.getStorageSize(
        OzoneConfigKeys.DFS_CONTAINER_RATIS_LEADER_PENDING_BYTES_LIMIT,
        OzoneConfigKeys.DFS_CONTAINER_RATIS_LEADER_PENDING_BYTES_LIMIT_DEFAULT,
        StorageUnit.BYTES);
    String thresholdPercent = conf.get(
        HddsConfigKeys.HDDS_CONTAINER_MONITOR_THRESHOLD_PERCENT,
        HddsConfigKeys.HDDS_CONTAINER_MONITOR_THRESHOLD_PERCENT_DEFAULT);
    int[] thresholdPercentVals = getThresholdPercentVals(thresholdPercent);
    pendingRequestCountThreshold = numPendingRequests
        * thresholdPercentVals[0] / 100;
    pendingRequestSizeThreshold = pendingRequestsBytesLimit
        * thresholdPercentVals[1] / 100;
    followerSyncDiffThreshold = numPendingRequests
        * thresholdPercentVals[2] / 100;
    cacheMissPercentThreshold = thresholdPercentVals[3];
    pendingReqMaxMemory = Runtime.getRuntime().maxMemory()
        * thresholdPercentVals[4] / 100;
  }

  @NotNull
  private static int[] getThresholdPercentVals(String thresholdPercent) {
    String[] split = thresholdPercent.split(",");
    if (split.length < 5) {
      split = HddsConfigKeys.HDDS_CONTAINER_MONITOR_THRESHOLD_PERCENT_DEFAULT
          .split(",");
    }
    int[] thresholdPercentVals = new int[5];
    try {
      for (int i = 0; i < 5; ++i) {
        thresholdPercentVals[i] = Integer.parseInt(split[i]);
      }
    } catch (NumberFormatException ex) {
      LOG.warn("monitor threshold percent config incorrect, ", ex);
      split = HddsConfigKeys.HDDS_CONTAINER_MONITOR_THRESHOLD_PERCENT_DEFAULT
          .split(",");
      for (int i = 0; i < 5; ++i) {
        thresholdPercentVals[i] = Integer.parseInt(split[i]);
      }
    }

    return thresholdPercentVals;
  }

  public void start() {
    executorService = HadoopExecutors.newScheduledThreadPool(1,
    new ThreadFactoryBuilder().setDaemon(true)
        .setNameFormat("CSM Health Monitor Thread - %d").build());
    executorService.scheduleAtFixedRate(this,
        0, 300, TimeUnit.SECONDS);
  }

  public void stop() {
    executorService.shutdown();
  }

  @Override
  public void run() {
    try {
      monitor();
    } catch (Exception ex) {
      LOG.error("Exception while running monitor, ", ex);
    }
  }
  
  private void monitor() throws IOException {
    long globalSize = 0;
    Map<String, List<String>> pipelineErrorMap = new HashMap<>();
    Iterable<RaftGroupId> groupIds = ratisServer.getServer().getGroupIds();
    for (RaftGroupId groupId : groupIds) {
      RaftServer.Division serverDivision
          = ratisServer.getServerDivision(groupId);
      PipelineID pipelineID = PipelineID.valueOf(groupId.getUuid());
      pipelineErrorMap.put(pipelineID.getId().toString(), new ArrayList<>());

      RaftServerMetrics raftMetrics = serverDivision.getRaftServerMetrics();
      RaftServerMetricsImpl raftMetricsImpl = null;
      if (raftMetrics instanceof RaftServerMetricsImpl) {
        raftMetricsImpl = (RaftServerMetricsImpl) raftMetrics;
      }
      if (null != raftMetricsImpl) {
        long pendingReqCount = raftMetricsImpl
            .getCounter(REQUEST_QUEUE_SIZE).getCount();
        if (pendingReqCount > pendingRequestCountThreshold) {
          pipelineErrorMap.get(pipelineID.getId().toString()).add(
              "Pending request count " + pendingReqCount);
        }
        long pendingReqSize = raftMetricsImpl
            .getCounter(REQUEST_MEGA_BYTE_SIZE).getCount();
        if (pendingReqSize > pendingRequestSizeThreshold) {
          pipelineErrorMap.get(pipelineID.getId().toString()).add(
              "Pending request size " + pendingReqSize);
        }

        globalSize += pendingReqSize;
      }

      LongStream indicesStream = Arrays.stream(serverDivision.getInfo()
          .getFollowerNextIndices());
      long diff = indicesStream.max().getAsLong()
          - indicesStream.min().getAsLong();
      if (diff > followerSyncDiffThreshold) {
        pipelineErrorMap.get(pipelineID.getId().toString()).add(
            "Follower sync difference count " + diff);
      }

      StateMachine stateMachine = serverDivision.getStateMachine();
      if (stateMachine instanceof ContainerStateMachine) {
        CSMMetrics metrics = ((ContainerStateMachine) stateMachine)
            .getMetrics();
        long cacheMissCount = metrics.getNumDataCacheMiss();
        long cacheHitCount = metrics.getNumDataCacheHit();
        if (cacheMissCount > 0) {
          long cacheMissPercent = (cacheMissCount
              / (cacheHitCount + cacheMissCount)) * 100;
          if (cacheMissPercent > cacheMissPercentThreshold) {
            pipelineErrorMap.get(pipelineID.getId().toString()).add(
                "Cache miss ratio is high, cache miss count: "
                    + cacheMissCount);
          }
        }
      }
    }
    if (globalSize > pendingReqMaxMemory) {
      pipelineErrorMap.put("Cache Memory Usages", new ArrayList<>());
      pipelineErrorMap.get("Cache Memory Usages").add(
          "Overall memory usages by pending request " + globalSize);
    }

    logPipelineHealth(pipelineErrorMap);
  }

  private static void logPipelineHealth(
      Map<String, List<String>> pipelineErrorMap) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, List<String>> entry : pipelineErrorMap.entrySet()) {
      if (entry.getValue().isEmpty()) {
        continue;
      }
      sb.append("\n").append(entry.getKey()).append(":");
      for (String data : entry.getValue()) {
        sb.append("\n\t").append(data);
      }
    }
    if (sb.length() > 0) {
      LOG.warn("CS Health report, %s", sb);
    }
  }
}
