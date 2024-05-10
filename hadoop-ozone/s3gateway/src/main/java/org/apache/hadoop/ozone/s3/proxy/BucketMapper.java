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
package org.apache.hadoop.ozone.s3.proxy;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;

/**
 * bucket mapper to map bucket and configuration.
 */
public final class BucketMapper {
  private BucketMapper() {
  }
  
  private static BucketConfLoader bucketConfLoader = new BucketConfLoader();
  private static AtomicBoolean isInit = new AtomicBoolean(false);

  public static void init(OzoneConfiguration conf) throws Exception {
    if (isInit.compareAndSet(false, true)) {
      List<String> uriPaths = new ArrayList<>();
      uriPaths.add("file:~/ozone/code/self/ozone/hadoop-ozone/s3gateway/src/main/java/org/apache/hadoop/ozone/" +
          "s3/proxy/sampleconf.txt");

      try {
        bucketConfLoader.init(uriPaths, conf);
      } catch (Exception e) {
        throw e;
      }
    }
  }
  
  public static boolean isCurrentNode(String path, OzoneConfiguration conf) {
    String bucket = getBucketName(path);
    BucketConfLoader.BucketInfo bucketInfo = bucketConfLoader.getBucketInfo(bucket);
    if (null == bucketInfo || !bucketInfo.isSelfNode()) {
      return false;
    }
    return true;
  }

  public static FileSystem getFS(String bucketName, OzoneConfiguration conf) throws IOException {
    BucketConfLoader.BucketInfo bucketInfo = bucketConfLoader.getBucketInfo(bucketName);
    if (null == bucketInfo) {
      throw new IOException("incorrect call");
    }
    
    return FileSystem.get(URI.create("s3a://" + bucketName + "/"), bucketInfo.getConf());
  }
  
  public static String getBucketName(String path) {
    String bucket = path;
    if (bucket.startsWith("/")) {
      bucket = bucket.substring(1);
    }
    if (bucket.indexOf("/") != -1) {
      bucket = bucket.substring(0, bucket.indexOf("/"));
    }
    return bucket;
  }
}
