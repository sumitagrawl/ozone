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

import org.apache.ratis.server.protocol.TermIndex;

/**
 * Context required for execution of a request.
 */
public final class ExecutionContext {
  private final long index;
  private final TermIndex termIndex;

  private ExecutionContext(long index, TermIndex termIndex) {
    if (null == termIndex) {
      // termIndex will be null for pre-ratis execution case which is before ratis transaction
      termIndex = TermIndex.valueOf(-1, index);
    }
    if (index == -1) {
      // during upgrade case before finalization, index will be -1 as returned by indexGenerator
      // for this, it will make use of termIndex's index for execution similar to previous behavior
      // and after finalization of feature, index generator will return initialized value > 0
      index = termIndex.getIndex();
    }
    this.index = index;
    this.termIndex = termIndex;
  }

  public static ExecutionContext of(long index, TermIndex termIndex) {
    return new ExecutionContext(index, termIndex);
  }

  public long getIndex() {
    return index;
  }

  public TermIndex getTermIndex() {
    return termIndex;
  }
}
