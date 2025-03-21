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
import java.util.List;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;

/**
 * Merge the request to be submitted to ratis.
 */
public final class MergeRequestUtils {
  private MergeRequestUtils() {
  }

  /**
   * merge the db changes from all request to Request builder.
   *
   * @param ctxs Request context having details to merge request
   * @param builder request build to have change details
   * @param sendList list of request context updated in builder
   * @param ratisByteLimit max message size of builder after merge
   * @return pending request context to be applied
   */
  public static Collection<RequestContext> merge(
      Collection<RequestContext> ctxs, OzoneManagerProtocolProtos.OMRequest.Builder builder,
      List<RequestContext> sendList, int ratisByteLimit) {
    // TODO merge implementation for merging request to be send to ratis for db update
    sendList.addAll(ctxs);
    return new ArrayList<>();
  }
}
