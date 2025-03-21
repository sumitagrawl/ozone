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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.apache.ratis.util.function.CheckedConsumer;

/**
 * Pool executor.
 */
public class PoolExecutor <T, Q> {
  private final Thread[] threadPool;
  private final List<BlockingQueue<T>> queues;
  private final String threadPrefix;
  private final AtomicBoolean isRunning = new AtomicBoolean(true);
  private BiConsumer<Collection<T>, CheckedConsumer<Q, InterruptedException>> handler = null;
  private CheckedConsumer<Q, InterruptedException> submitter;

  private PoolExecutor(int poolSize, int queueSize, String threadPrefix) {
    threadPool = new Thread[poolSize];
    queues = new ArrayList<>(poolSize);
    this.threadPrefix = threadPrefix;
    for (int i = 0; i < poolSize; ++i) {
      LinkedBlockingQueue<T> queue = new LinkedBlockingQueue<>(queueSize);
      queues.add(queue);
    }
  }
  public PoolExecutor(
      int poolSize, int queueSize, String threadPrefix,
      BiConsumer<Collection<T>, CheckedConsumer<Q, InterruptedException>> handler,
      CheckedConsumer<Q, InterruptedException> submitter) {
    this(poolSize, queueSize, threadPrefix);
    this.handler = handler;
    this.submitter = submitter;
  }
  public void start() {
    for (int i = 0; i < threadPool.length; ++i) {
      BlockingQueue<T> queue = queues.get(i);
      threadPool[i] = new Thread(() -> execute(queue), threadPrefix + "-" + i);
      threadPool[i].start();
    }
  }
  public void submit(int idx, T task) throws InterruptedException {
    if (idx < 0 || idx >= threadPool.length) {
      return;
    }
    queues.get(idx).put(task);
  }

  private void execute(BlockingQueue<T> q) {
    while (isRunning.get()) {
      try {
        List<T> entries = new LinkedList<>();
        T task = q.take();
        entries.add(task);
        q.drainTo(entries);
        handler.accept(entries, submitter);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
  }

  public void stop() {
    for (Thread thread : threadPool) {
      thread.interrupt();
      try {
        thread.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
