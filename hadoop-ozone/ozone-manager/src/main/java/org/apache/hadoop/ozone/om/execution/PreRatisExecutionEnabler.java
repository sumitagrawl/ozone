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

import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;

/**
 * control class for pre-ratis execution compatibility.
 */
@SuppressWarnings("checkstyle:HideUtilityClassConstructor")
public class PreRatisExecutionEnabler {
  private static boolean isLeaderExecutionEnabled = false;
  public static void init(OzoneManager om) {
    isLeaderExecutionEnabled = om.getConfiguration().getBoolean("ozone.om.leader.execution.enabled", false);
  }

  public static boolean optimizedFlow(OzoneManagerProtocolProtos.OMRequest omRequest, OzoneManager om) {
    if (!isLeaderExecutionEnabled) {
      return false;
    }

    // TODO check for supported type and return flag
    return false;
  }
}
