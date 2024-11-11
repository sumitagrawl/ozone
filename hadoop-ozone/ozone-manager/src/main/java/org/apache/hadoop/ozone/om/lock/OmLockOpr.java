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

package org.apache.hadoop.ozone.om.lock;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import org.apache.hadoop.util.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * key locking.
 */
public class OmLockOpr {
  private static final Logger LOG = LoggerFactory.getLogger(OmLockOpr.class);
  private static final long MONITOR_DELAY = 10 * 60 * 1000;
  private static final long MONITOR_LOCK_THRESHOLD_NS = 10 * 60 * 1000_000_000L;
  private final KeyLock keyLocking;
  private final KeyLock bucketLocking;
  private final String threadNamePrefix;
  private ScheduledExecutorService executorService;
  private final Map<OmLockInfo, OmLockInfo> lockMonitorMap = new ConcurrentHashMap<>();

  public OmLockOpr(String threadNamePrefix) {
    this.threadNamePrefix = threadNamePrefix;
    keyLocking = new KeyLock(102400);
    bucketLocking = new KeyLock(1024);
  }

  public void start() {
    ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true)
        .setNameFormat(threadNamePrefix + "OmLockOpr-Monitor-%d").build();
    executorService = Executors.newScheduledThreadPool(1, threadFactory);
    executorService.scheduleWithFixedDelay(this::monitor, 0, MONITOR_DELAY, TimeUnit.MILLISECONDS);
  }

  public void stop() {
    executorService.shutdown();
  }

  public OmLockInfo obsLock(String bucketName, String keyName) throws IOException {
    OmLockInfo omLockInfo = new OmLockInfo();
    List<Lock> locks = omLockInfo.getLocks();
    // bucket read lock
    long startTime = Time.monotonicNowNanos();
    locks.add(bucketLocking.readLock(bucketName));
    try {
      // key lock
      locks.add(keyLocking.lock(keyName));
      long endTime = Time.monotonicNowNanos();
      omLockInfo.add(endTime - startTime, OmLockInfo.LockOpType.WAIT);
      omLockInfo.setLockTakenTime(endTime);
      lockMonitorMap.put(omLockInfo, omLockInfo);
      return omLockInfo;
    } catch (IOException ex) {
      omLockInfo.add(Time.monotonicNowNanos() - startTime, OmLockInfo.LockOpType.WAIT);
      obsUnlock(omLockInfo);
      throw ex;
    }
  }

  public void obsUnlock(OmLockInfo lockInfo) {
    Collections.reverse(lockInfo.getLocks());
    lockInfo.getLocks().forEach(Lock::unlock);
    if (lockInfo.getLockTakenTime() > 0) {
      lockInfo.add(Time.monotonicNowNanos() - lockInfo.getLockTakenTime(), OmLockInfo.LockOpType.WRITE);
    }
    lockInfo.getLocks().clear();
    lockMonitorMap.remove(lockInfo);
  }
  
  public void monitor() {
    long curTime = Time.monotonicNowNanos() - MONITOR_LOCK_THRESHOLD_NS;
    for (OmLockInfo lockInfo : lockMonitorMap.values()) {
      if ((curTime - lockInfo.getLockTakenTime()) > 0) {
        LOG.warn("Lock {} is crossing threshold {}: ", lockInfo, MONITOR_LOCK_THRESHOLD_NS);
      }
    }
  }

  /**
   * Lock information.
   */
  public static class OmLockInfo {
    private String key;
    private long lockTakenTime;
    private long waitLockNanos;
    private long readLockNanos;
    private long writeLockNanos;
    private List<Lock> locks = new ArrayList<>();

    public void setKey(String key) {
      this.key = key;
    }

    public String getKey() {
      return key;
    }

    public long getWaitLockNanos() {
      return waitLockNanos;
    }

    public long getReadLockNanos() {
      return readLockNanos;
    }

    public long getWriteLockNanos() {
      return writeLockNanos;
    }

    public List<Lock> getLocks() {
      return locks;
    }

    public long getLockTakenTime() {
      return lockTakenTime;
    }

    public void setLockTakenTime(long lockTakenTime) {
      this.lockTakenTime = lockTakenTime;
    }

    void add(long timeNanos, LockOpType lockOpType) {
      switch (lockOpType) {
      case WAIT:
        waitLockNanos += timeNanos;
        break;
      case READ:
        readLockNanos += timeNanos;
        break;
      case WRITE:
        writeLockNanos += timeNanos;
        break;
      default:
      }
    }

    @Override
    public String toString() {
      return "OMLockDetails{" +
          "key=" + key +
          ", waitLockNanos=" + waitLockNanos +
          ", readLockNanos=" + readLockNanos +
          ", writeLockNanos=" + writeLockNanos +
          '}';
    }

    enum LockOpType {
      WAIT,
      READ,
      WRITE
    }
  }
}
