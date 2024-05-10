package org.apache.hadoop.ozone.s3;

import java.io.IOException;
import java.net.URI;
import javax.annotation.Priority;
import javax.inject.Inject;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.Provider;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.s3.proxy.BucketMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Update the URI for proxy if target is not this node cluster.
 */
@Provider
@PreMatching
@Priority(ProxyFilter.PRIORITY)
public class ProxyFilter implements ContainerRequestFilter {
  public static final int PRIORITY = HeaderPreprocessor.PRIORITY +
      S3GatewayHttpServer.FILTER_PRIORITY_DO_AFTER;
  private static final Logger LOG = LoggerFactory.getLogger(ProxyFilter.class);
  @Inject
  private OzoneConfiguration ozoneConfiguration;

  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    try {
      BucketMapper.init(ozoneConfiguration);
    } catch (Exception ex) {
      LOG.error("init failed");
    }
    UriInfo uriInfo = requestContext.getUriInfo();
    if (!BucketMapper.isCurrentNode(uriInfo.getPath(), ozoneConfiguration)) {
      LOG.info("Calling other node " + uriInfo.getPath());
      URI requestUri = uriInfo.getRequestUri();
      requestContext.getHeaders().add("originalUri", requestUri.toString());
      requestContext.setRequestUri(URI.create("/proxy"));
    } else {
      LOG.info("Calling self node " + uriInfo.getPath());
    }
  }
}
