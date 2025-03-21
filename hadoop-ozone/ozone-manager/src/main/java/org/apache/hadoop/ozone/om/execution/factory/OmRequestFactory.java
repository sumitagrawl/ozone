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

package org.apache.hadoop.ozone.om.execution.factory;

import java.io.IOException;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.execution.flowcontrol.DbChangeRecorder;
import org.apache.hadoop.ozone.om.execution.flowcontrol.RequestFlow;
import org.apache.hadoop.ozone.om.helpers.OMAuditLogger;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;

/**
 * Request factory to create request handling object.
 */
public final class OmRequestFactory {
  private OmRequestFactory() {
  }

  /**
   * Create OMClientRequest which encapsulates the OMRequest.
   * @param omRequest
   * @param ozoneManager
   * @return RequestFlow
   * @throws IOException
   */
  public static RequestFlow createRequestExecutor(
      OzoneManagerProtocolProtos.OMRequest omRequest, OzoneManager ozoneManager) throws IOException {
    // TODO add request factory for command type, add dummy request flow

    return new RequestFlow() {
      @Override
      public DbChangeRecorder getChangeRecorder() {
        DbChangeRecorder dbChangeRecorder = new DbChangeRecorder() {
          @Override
          public boolean haveChanges() {
            return false;
          }

          @Override
          public void clear() {
          }
        };
        return dbChangeRecorder;
      }

      @Override
      public OMAuditLogger.Builder getAuditBuilder() {
        return null;
      }
    };
  }
}
