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
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.helpers.OMAuditLogger;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;

/**
 * interface to define request execution flow.
 */
public interface RequestFlow {
  /**
   * Perform request validation, bucket type check, update parameter like user Info, update time and others.
   *
   * @param ozoneManager ozone manager instance
   * @throws IOException IO exception
   */
  default OzoneManagerProtocolProtos.OMRequest preProcess(OzoneManager ozoneManager)
      throws IOException {
    return null;
  }

  /**
   * perform authorize validation using acl or ranger.
   *
   * @param ozoneManager ozone manager instance
   * @throws IOException IO exception
   */
  default void authorize(OzoneManager ozoneManager) throws IOException {
  }

  /**
   * lock the request flow.
   *
   * @throws IOException IO exception
   */
  default void lock() throws IOException {
  }

  /**
   * unlock the request flow.
   */
  default void unlock() {
  }

  /**
   * perform request processing such as prepare changes, resource validation.
   *
   * @param ozoneManager ozone manager instance
   * @param exeCtx execution context
   */
  default OMClientResponse process(OzoneManager ozoneManager, ExecutionContext exeCtx) throws IOException {
    return null;
  }

  /**
   * retrieve changes to be applied to db.
   *
   * @return DbChangeRecorder
   */
  DbChangeRecorder getChangeRecorder();

  /**
   * get audit builder from request to log audit.
   *
   * @return OMAuditLogger.Builder
   */
  OMAuditLogger.Builder getAuditBuilder();
}
