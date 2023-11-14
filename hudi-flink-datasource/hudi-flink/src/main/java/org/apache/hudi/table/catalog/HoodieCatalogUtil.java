/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.table.catalog;

import org.apache.hudi.configuration.FlinkOptions;
import org.apache.hudi.configuration.HadoopConfigurations;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.table.catalog.CatalogPartitionSpec;
import org.apache.flink.table.catalog.CatalogTable;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.table.catalog.exceptions.CatalogException;
import org.apache.flink.table.catalog.exceptions.PartitionSpecInvalidException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.apache.flink.util.StringUtils.isNullOrWhitespaceOnly;
import static org.apache.hudi.table.catalog.CatalogOptions.HIVE_SITE_FILE;

/**
 * Utilities for Hoodie Catalog.
 */
public class HoodieCatalogUtil {
  private static final Logger LOG = LoggerFactory.getLogger(HoodieCatalogUtil.class);

  /**
   * Returns a new {@code HiveConf}.
   *
   * @param hiveConfDir Hive conf directory path.
   * @return A HiveConf instance.
   */
  public static HiveConf createHiveConf(@Nullable String hiveConfDir, org.apache.flink.configuration.Configuration flinkConf) {
    // create HiveConf from hadoop configuration with hadoop conf directory configured.
    final org.apache.flink.configuration.Configuration flinkConfiguration = flinkConf == null ? new org.apache.flink.configuration.Configuration() : flinkConf;
    Configuration hadoopConf = HadoopConfigurations.getHadoopConf(flinkConfiguration);

    // ignore all the static conf file URLs that HiveConf may have set
    HiveConf.setHiveSiteLocation(null);
    HiveConf.setLoadMetastoreConfig(false);
    HiveConf.setLoadHiveServer2Config(false);
    HiveConf hiveConf = new HiveConf(hadoopConf, HiveConf.class);

    LOG.info("Setting hive conf dir as {}", hiveConfDir);

    if (hiveConfDir != null) {
      Path hiveSite = new Path(hiveConfDir, HIVE_SITE_FILE);
      if (!hiveSite.toUri().isAbsolute()) {
        // treat relative URI as local file to be compatible with previous behavior
        hiveSite = new Path(new File(hiveSite.toString()).toURI());
      }
      try (InputStream inputStream = hiveSite.getFileSystem(hadoopConf).open(hiveSite)) {
        hiveConf.addResource(inputStream, hiveSite.toString());
        // trigger a read from the conf so that the input stream is read
        isEmbeddedMetastore(hiveConf);
      } catch (IOException e) {
        throw new CatalogException(
            "Failed to load hive-site.xml from specified path:" + hiveSite, e);
      }
    } else {
      // user doesn't provide hive conf dir, we try to find it in classpath
      URL hiveSite =
          Thread.currentThread().getContextClassLoader().getResource(HIVE_SITE_FILE);
      if (hiveSite != null) {
        LOG.info("Found {} in classpath: {}", HIVE_SITE_FILE, hiveSite);
        hiveConf.addResource(hiveSite);
      }
    }
    return hiveConf;
  }

  /**
   * Check whether the hive.metastore.uris is empty
   */
  public static boolean isEmbeddedMetastore(HiveConf hiveConf) {
    return isNullOrWhitespaceOnly(hiveConf.getVar(HiveConf.ConfVars.METASTOREURIS));
  }

  /**
   * Returns the partition key list with given table.
   */
  public static List<String> getPartitionKeys(CatalogTable table) {
    // the PARTITIONED BY syntax always has higher priority than option FlinkOptions#PARTITION_PATH_FIELD
    if (table.isPartitioned()) {
      return table.getPartitionKeys();
    } else if (table.getOptions().containsKey(FlinkOptions.PARTITION_PATH_FIELD.key())) {
      return Arrays.stream(table.getOptions().get(FlinkOptions.PARTITION_PATH_FIELD.key()).split(","))
          .collect(Collectors.toList());
    }
    return Collections.emptyList();
  }

  /**
   * Returns the partition path with given {@link CatalogPartitionSpec}.
   */
  public static String inferPartitionPath(boolean hiveStylePartitioning, CatalogPartitionSpec catalogPartitionSpec) {
    return catalogPartitionSpec.getPartitionSpec().entrySet()
        .stream().map(entry ->
            hiveStylePartitioning
                ? String.format("%s=%s", entry.getKey(), entry.getValue())
                : entry.getValue())
        .collect(Collectors.joining("/"));
  }

  /**
   * Returns a list of ordered partition values by re-arranging them based on the given list of
   * partition keys. If the partition value is null, it'll be converted into default partition
   * name.
   *
   * @param partitionSpec The partition spec
   * @param partitionKeys The partition keys
   * @param tablePath     The table path
   * @return A list of partition values ordered by partition keys
   * @throws PartitionSpecInvalidException thrown if partitionSpec and partitionKeys have
   *     different sizes, or any key in partitionKeys doesn't exist in partitionSpec.
   */
  @VisibleForTesting
  public static List<String> getOrderedPartitionValues(
      String catalogName,
      HiveConf hiveConf,
      CatalogPartitionSpec partitionSpec,
      List<String> partitionKeys,
      ObjectPath tablePath)
      throws PartitionSpecInvalidException {
    Map<String, String> spec = partitionSpec.getPartitionSpec();
    if (spec.size() != partitionKeys.size()) {
      throw new PartitionSpecInvalidException(catalogName, partitionKeys, tablePath, partitionSpec);
    }

    List<String> values = new ArrayList<>(spec.size());
    for (String key : partitionKeys) {
      if (!spec.containsKey(key)) {
        throw new PartitionSpecInvalidException(catalogName, partitionKeys, tablePath, partitionSpec);
      } else {
        String value = spec.get(key);
        if (value == null) {
          value = hiveConf.getVar(HiveConf.ConfVars.DEFAULTPARTITIONNAME);
        }
        values.add(value);
      }
    }

    return values;
  }
}
