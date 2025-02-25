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

import static org.apache.hadoop.ozone.om.upgrade.OMLayoutFeature.MANAGED_INDEX;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hadoop.hdds.utils.TransactionInfo;
import org.apache.hadoop.hdds.utils.db.BatchOperation;
import org.apache.hadoop.ozone.om.OzoneManager;

/**
 * Manages indexes for request handling and persist.
 */
public final class IndexGenerator {
  public static final String OM_INDEX_KEY = "#OMINDEX";

  private final AtomicLong index = new AtomicLong();
  private final AtomicLong commitIndex = new AtomicLong();
  private final OzoneManager ozoneManager;
  private final AtomicBoolean enabled = new AtomicBoolean(true);

  public IndexGenerator(OzoneManager ozoneManager) throws IOException {
    this.ozoneManager = ozoneManager;
    initialize();
  }

  public void initialize() throws IOException {
    if (!ozoneManager.getVersionManager().isAllowed(MANAGED_INDEX)) {
      enabled.set(false);
      return;
    }

    // default first time starts with "0"
    long initIndex = 0;
    // retrieve last saved index
    TransactionInfo transactionInfo = ozoneManager.getMetadataManager().getTransactionInfoTable().get(OM_INDEX_KEY);
    if (null == transactionInfo) {
      // use ratis transaction for first time upgrade
      transactionInfo = TransactionInfo.readTransactionInfo(ozoneManager.getMetadataManager());
    }
    if (null != transactionInfo) {
      initIndex = transactionInfo.getTransactionIndex();
    }
    index.set(initIndex);
    commitIndex.set(initIndex);
  }

  public void finalizeIndexGeneratorFeature() throws IOException {
    if (enabled.get()) {
      return;
    }

    // reinit the feature on finalization
    long initIndex = 0;
    TransactionInfo transactionInfo = TransactionInfo.readTransactionInfo(ozoneManager.getMetadataManager());
    if (null != transactionInfo) {
      initIndex = transactionInfo.getTransactionIndex();
    }
    index.set(initIndex);
    enabled.set(true);

    try (BatchOperation batchOperation = ozoneManager.getMetadataManager().getStore()
        .initBatchOperation()) {
      saveIndex(batchOperation, initIndex);
      ozoneManager.getMetadataManager().getStore().commitBatchOperation(batchOperation);
    }
  }

  public long nextIndex() {
    if (!enabled.get()) {
      return -1;
    }
    return index.incrementAndGet();
  }

  /**
   *  Follower on every transaction update the commit index via saveIndex().
   *  When the follower becomes leader onLeaderChange(), it updates index with Max (commit index, current index).
   *  Max is done for purpose where follower can have higher index (not yet sync to other nodes) and being discarded
   *  for continuation on that node.
   */
  public void onLeaderChange() {
    index.set(Math.max(commitIndex.get(), index.get()));
  }

  public synchronized void saveIndex(BatchOperation batchOperation, long idx) throws IOException {
    if (!enabled.get()) {
      return;
    }
    if (idx <= commitIndex.get()) {
      return;
    }

    ozoneManager.getMetadataManager().getTransactionInfoTable().putWithBatch(batchOperation, OM_INDEX_KEY,
        TransactionInfo.valueOf(-1, idx));
    commitIndex.set(idx);
  }
}
