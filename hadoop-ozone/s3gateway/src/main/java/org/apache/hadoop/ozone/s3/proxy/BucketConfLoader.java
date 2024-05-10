package org.apache.hadoop.ozone.s3.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * configuration loader for bucket.
 */
public class BucketConfLoader {
  private static final Logger LOG = LoggerFactory.getLogger(BucketConfLoader.class);
  private Map<String, BucketConf> bucketConfMap = new HashMap<>();
  private Map<String, BucketInfo> bucketLoadedConfMap = new ConcurrentHashMap<>();
  public void init(List<String> uriPaths, OzoneConfiguration baseConf) throws Exception {
    // load all json from uri
    ObjectMapper mapper = new ObjectMapper();
    for (String uri : uriPaths) {
      try {
        // read from local file with file:
        AllBucketConf allBucketConf = mapper.readValue(new URL(uri), AllBucketConf.class);
        bucketConfMap.putAll(allBucketConf.getAllBuckets());
      } catch (Exception ex) {
        throw ex;
      }
    }
    
    // prepare bucket conf
    for (Map.Entry<String, BucketConf> entry : bucketConfMap.entrySet()) {
      String bucketName = entry.getKey();
      BucketConf bucketConf = entry.getValue();
      if (bucketLoadedConfMap.containsKey(bucketName)) {
        // already loaded using ref or duplicate entry, ignore
        continue;
      }

      String bucketRef = bucketConf.getControl().getOrDefault("bucket.ref.conf", "");
      if (!bucketRef.isEmpty()) {
        // check and load ref bucket
        if (!loadBucketRef(baseConf, bucketRef)) {
          LOG.error("Unable to load bucket {} configuration as ref bucket load failed", bucketName);
          continue;
        }
        BucketInfo bucketInfo = new BucketInfo(bucketLoadedConfMap.get(bucketRef));
        loadBucketConf(bucketInfo.getConf(), bucketConf, bucketInfo);
        setSelfNode(bucketConf, bucketInfo, baseConf);
        bucketLoadedConfMap.put(bucketName, bucketInfo);
        continue;
      }
      BucketInfo bucketInfo = new BucketInfo();
      loadBucketConf(baseConf, bucketConf, bucketInfo);
      setSelfNode(bucketConf, bucketInfo, baseConf);
      bucketLoadedConfMap.put(bucketName, bucketInfo);
    }
  }

  private boolean loadBucketRef(OzoneConfiguration baseConf, String bucketRef) {
    if (!bucketConfMap.containsKey(bucketRef)) {
      // bucket reference conf not present
      LOG.error("Unable to load bucket as ref bucket {} is not present", bucketRef);
      return false;
    }
    BucketConf bucketRefConf = bucketConfMap.get(bucketRef);
    if (!bucketRefConf.getControl().getOrDefault("bucket.ref.conf", "").isEmpty()) {
      // second ref is not allowed
      LOG.error("Multiple level of ref bucket {} is not allowed", bucketRef);
      return false;
    }
    if (!bucketLoadedConfMap.containsKey(bucketRef)) {
      BucketInfo bucketRefInfo = new BucketInfo();
      loadBucketConf(baseConf, bucketRefConf, bucketRefInfo);
      setSelfNode(bucketRefConf, bucketRefInfo, baseConf);
      bucketLoadedConfMap.put(bucketRef, bucketRefInfo);
    }
    return true;
  }

  private static void loadBucketConf(OzoneConfiguration baseConf, BucketConf bucketConf, BucketInfo bucketInfo) {
    if ("true".equals(bucketConf.getControl().getOrDefault("bucket.replace.base.conf", "false"))) {
      // load new config from file
      Configuration newConf = new Configuration(false);
      newConf.setDeprecatedProperties();
      // TODO load file content to newConf
      //newConf.addResource(new Path("/Users/sumitagrawal/ozone/code/self/ozone/raztest/1/dfs.xml"));
      //newConf.addResource(new Path("/Users/sumitagrawal/ozone/code/self/ozone/raztest/1/hadoop.xml"));
      OzoneConfiguration conf = new OzoneConfiguration(newConf);
      loadBucketContentParam(bucketConf, conf);
      bucketInfo.setConf(conf);
    } else {
      OzoneConfiguration conf = new OzoneConfiguration(baseConf);
      // TODO load file content to conf
      loadBucketContentParam(bucketConf, conf);
      bucketInfo.setConf(conf);
    }

    String val = bucketConf.getControl().get("aws.raz.enabled");
    if ("true".equals(val) || "false".equals(val)) {
      bucketInfo.setRazEnabled(val.equals("true") ? true : false);
    }
  }

  private static void setSelfNode(BucketConf bucketConf, BucketInfo bucketInfo, OzoneConfiguration conf) {
    String id = bucketConf.getControl().get("env.id");
    String confName = bucketConf.getControl().get("env.conf.id");
    if (null == confName || null == id) {
      return;
    }
    String confVal = conf.getTrimmed(confName);
    if (confVal.equals(id)) {
      bucketInfo.setSelfNode(true);
    } else {
      bucketInfo.setSelfNode(false);
    }
  }

  private static void loadBucketContentParam(BucketConf bucketConf, OzoneConfiguration conf) {
    for (ConfInfo info : bucketConf.getConf()) {
      if ("content".equals(info.type)) {
        for (Map.Entry<String, String> kvEntry : info.data.entrySet()) {
          if (kvEntry.getValue() == null) {
            conf.unset(kvEntry.getKey());
          } else {
            conf.set(kvEntry.getKey(), kvEntry.getValue());
          }
        }
      }
    }
  }

  public BucketInfo getBucketInfo(String bucketName) {
    return bucketLoadedConfMap.get(bucketName);
  }

  /**
   * bucket info loaded.
   */
  public static class BucketInfo {
    private OzoneConfiguration conf;
    private boolean isSelfNode = false;
    private boolean isRazEnabled = false;
    
    BucketInfo() {
    }
    
    BucketInfo(BucketInfo other) {
      conf = other.getConf();
      isSelfNode = other.isSelfNode();
      isRazEnabled = other.isRazEnabled();
    }

    public OzoneConfiguration getConf() {
      return conf;
    }

    public void setConf(OzoneConfiguration conf) {
      this.conf = conf;
    }

    public boolean isSelfNode() {
      return isSelfNode;
    }

    public void setSelfNode(boolean selfNode) {
      isSelfNode = selfNode;
    }

    public boolean isRazEnabled() {
      return isRazEnabled;
    }

    public void setRazEnabled(boolean razEnabled) {
      isRazEnabled = razEnabled;
    }
  }
  private static class AllBucketConf {
    private Map<String, BucketConf> allBuckets;

    public Map<String, BucketConf> getAllBuckets() {
      return allBuckets;
    }

    public void setAllBuckets(Map<String, BucketConf> allBuckets) {
      this.allBuckets = allBuckets;
    }
  }
  private static class BucketConf {
    private List<ConfInfo> conf;
    private Map<String, String> control;

    public List<ConfInfo> getConf() {
      return conf;
    }

    public void setConf(List<ConfInfo> conf) {
      this.conf = conf;
    }

    public Map<String, String> getControl() {
      return control;
    }

    public void setControl(Map<String, String> control) {
      this.control = control;
    }
  }
  private static class ConfInfo {
    private String type;
    private Map<String, String> data;

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public Map<String, String> getData() {
      return data;
    }

    public void setData(Map<String, String> data) {
      this.data = data;
    }
  }
}
