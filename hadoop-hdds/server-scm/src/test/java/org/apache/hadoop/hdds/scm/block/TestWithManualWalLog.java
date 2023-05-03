/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdds.scm.block;

import java.io.File;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.hdds.HddsConfigKeys;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.scm.HddsTestUtils;
import org.apache.hadoop.hdds.scm.ScmConfigKeys;
import org.apache.hadoop.hdds.scm.container.ContainerID;
import org.apache.hadoop.hdds.scm.container.ContainerInfo;
import org.apache.hadoop.hdds.scm.ha.SCMHADBTransactionBufferStub;
import org.apache.hadoop.hdds.scm.metadata.DBTransactionBuffer;
import org.apache.hadoop.hdds.scm.pipeline.PipelineID;
import org.apache.hadoop.hdds.scm.server.StorageContainerManager;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.util.Time;
import org.apache.ozone.test.GenericTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for DeletedBlockLog.
 */
public class TestWithManualWalLog {

  private OzoneConfiguration conf;
  private File testDir;
  private StorageContainerManager scm = null;

  @BeforeEach
  public void setup() throws Exception {
    testDir = GenericTestUtils.getTestDir(
        TestDeletedBlockLog.class.getSimpleName());
    conf = new OzoneConfiguration();
    conf.setBoolean(ScmConfigKeys.OZONE_SCM_HA_ENABLE_KEY, true);
    conf.set(HddsConfigKeys.OZONE_METADATA_DIRS, testDir.getAbsolutePath());
  }


  @AfterEach
  public void tearDown() throws Exception {
    if (scm != null) {
      scm.stop();
      scm.join();
      FileUtils.deleteDirectory(testDir);
    }
  }

  @Test
  public void testTxBufferWithManualWalEnablePerfTest() throws Exception {
    conf.setBoolean("rocksdb.WAL.manual.flush.enabled", true);
    scm = HddsTestUtils.getScm(conf);
    DBTransactionBuffer dbTransactionBuffer = scm.getScmHAManager()
        .getDBTransactionBuffer();
    if (dbTransactionBuffer instanceof SCMHADBTransactionBufferStub) {
      ((SCMHADBTransactionBufferStub) dbTransactionBuffer)
          .setManualFlushConf(true);
    }
    testPerf();
  }

  @Test
  public void testTxBufferWithBatchOperationPerfTest() throws Exception {
    conf.setBoolean("rocksdb.WAL.manual.flush.enabled", false);
    scm = HddsTestUtils.getScm(conf);
    testPerf();
  }
  
  public void testPerf() throws Exception {
    Table<ContainerID, ContainerInfo> tbl1
        = scm.getScmMetadataStore().getContainerTable();

    HddsProtos.ContainerInfoProto.Builder containerInfoBuilder
        = HddsProtos.ContainerInfoProto
        .newBuilder()
        .setState(HddsProtos.LifeCycleState.OPEN)
        .setPipelineID(PipelineID.randomId().getProtobuf())
        .setUsedBytes(0)
        .setNumberOfKeys(0)
        .setStateEnterTime(Time.now())
        .setOwner("test")
        .setContainerID(1)
        .setDeleteTransactionId(0)
        .setReplicationType(HddsProtos.ReplicationType.RATIS);
    HddsProtos.ContainerInfoProto build = containerInfoBuilder.build();
    long start = 0;
    long end = 0;
    long[] addTs = new long[8];
    for (int i = 0; i < 8; ++i) {
      addTs[i] = 0;
    }
    scm.getScmMetadataStore().getStore().flushLog(true);
    scm.getScmMetadataStore().getStore().flushDB();
    int itrLen = 10;
    for (int k = 0; k < itrLen; ++k) {
      start = System.nanoTime();
      for (int i = 0; i < 100000; ++i) {
        scm.getScmHAManager().getDBTransactionBuffer().addToBuffer(tbl1,
            new ContainerID(i), ContainerInfo.fromProtobuf(build));
      }
      end = System.nanoTime();
      //System.out.println("Time taken Add: " + (end - start));
      addTs[0] += (end - start);

      start = System.nanoTime();
      scm.getScmHAManager().getDBTransactionBuffer().close();
      end = System.nanoTime();
      //System.out.println("Time taken Add Flush: " + (end - start));
      addTs[1] += (end - start);

      start = System.nanoTime();
      for (int i = 0; i < 100000; ++i) {
        scm.getScmHAManager().getDBTransactionBuffer().removeFromBuffer(tbl1,
            new ContainerID(i));
      }
      end = System.nanoTime();
      //System.out.println("Time taken Delete: " + (end - start));
      addTs[2] += (end - start);

      start = System.nanoTime();
      scm.getScmHAManager().getDBTransactionBuffer().close();
      end = System.nanoTime();
      //System.out.println("Time taken Delete flush: " + (end - start));
      addTs[3] += (end - start);

      start = System.nanoTime();
      for (int i = 0; i < 100000; ++i) {
        scm.getScmHAManager().getDBTransactionBuffer().addToBuffer(tbl1,
            new ContainerID(i), ContainerInfo.fromProtobuf(build));
        scm.getScmHAManager().getDBTransactionBuffer().removeFromBuffer(tbl1,
            new ContainerID(i));
      }
      end = System.nanoTime();
      //System.out.println("Time taken Add/Delete: " + (end - start));
      addTs[4] += (end - start);

      start = System.nanoTime();
      scm.getScmHAManager().getDBTransactionBuffer().close();
      end = System.nanoTime();
      //System.out.println("Time taken Add/Delete flush: " + (end - start));
      addTs[5] += (end - start);

      // Update test
      start = System.nanoTime();
      for (int i = 0; i < 100000; ++i) {
        scm.getScmHAManager().getDBTransactionBuffer().addToBuffer(tbl1,
            new ContainerID(i), ContainerInfo.fromProtobuf(build));
        scm.getScmHAManager().getDBTransactionBuffer().addToBuffer(tbl1,
            new ContainerID(i), ContainerInfo.fromProtobuf(build));
      }
      end = System.nanoTime();
      //System.out.println("Time taken Update: " + (end - start));
      addTs[6] += (end - start);

      start = System.nanoTime();
      scm.getScmHAManager().getDBTransactionBuffer().close();
      end = System.nanoTime();
      //System.out.println("Time taken Update Flush: " + (end - start));
      addTs[7] += (end - start);

      for (int i = 0; i < 100000; ++i) {
        scm.getScmHAManager().getDBTransactionBuffer().removeFromBuffer(tbl1,
            new ContainerID(i));
      }
      scm.getScmHAManager().getDBTransactionBuffer().close();
    }

    System.out.println("Average usages");
    System.out.println("Time taken Add: " + addTs[0] / itrLen);
    System.out.println("Time taken Add Flush: " + addTs[1] / itrLen);
    System.out.println("Time taken Del: " + addTs[2] / itrLen);
    System.out.println("Time taken Del Flush: " + addTs[3] / itrLen);
    System.out.println("Time taken Add/Del: " + addTs[4] / itrLen);
    System.out.println("Time taken Add/Del Flush: " + addTs[5] / itrLen);
    System.out.println("Time taken Update: " + addTs[6] / itrLen);
    System.out.println("Time taken Update Flush: " + addTs[7] / itrLen);
  }
  
}
