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
import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.s3.endpoint.ListObjectResponse;
import org.apache.hadoop.ozone.s3.exception.OS3Exception;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proxy endpoints.
 */
@Path("/proxy")
public class ProxyEndpoint {

  private static final Logger LOG =
      LoggerFactory.getLogger(ProxyEndpoint.class);

  @Inject
  private OzoneConfiguration ozoneConfiguration;
  
  public ProxyEndpoint() {
    LOG.info("starting proxy cons");
  }

  @PostConstruct
  public void init() {
    LOG.info("init proxy");
  }
  
  /**
   * Rest endpoint as proxy for get.
   */
  @GET
  public Response get(@Context UriInfo info,
      @Context HttpHeaders hh) throws OS3Exception, IOException {
    URI originalUri = URI.create(hh.getHeaderString("originalUri"));
    String bucketName = BucketMapper.getBucketName(originalUri.getPath());
    LOG.info("Get Called..." + bucketName);
    if (bucketName.equals("test1")) {
      bucketName = "test";
    }
    FileSystem fs = BucketMapper.getFS(bucketName, ozoneConfiguration);
    RemoteIterator<LocatedFileStatus> itr = fs.listFiles(new org.apache.hadoop.fs.Path("/"), false);
    if (itr.hasNext()) {
      LOG.info("found element {}", itr.next().getPath());
    } else {
      LOG.info("no element");
    }
    ListObjectResponse response = new ListObjectResponse();
    return Response.ok(response).build();
  }
}
