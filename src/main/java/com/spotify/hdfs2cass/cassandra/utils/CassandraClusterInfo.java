/*
 * Copyright 2014 Spotify AB. All rights reserved.
 *
 * The contents of this file are licensed under the Apache License, Version
 * 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.spotify.hdfs2cass.cassandra.utils;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ColumnMetadata;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.Metadata;
import com.datastax.driver.core.TableMetadata;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.spotify.hdfs2cass.cassandra.thrift.ExternalSSTableLoaderClient;
import org.apache.cassandra.config.Config;
import org.apache.cassandra.hadoop.ConfigHelper;
import org.apache.crunch.CrunchRuntimeException;
import org.apache.hadoop.conf.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.List;

public class CassandraClusterInfo implements Serializable {

  private static final Logger logger = LoggerFactory.getLogger(CassandraClusterInfo.class);

  private final String host;
  private final int port;
  private String partitionerClass;
  private int numClusterNodes;
  private String keyspace;
  private String columnFamily;
  private String cqlSchema;
  private List<ColumnMetadata> columns;
  private int[] partitionKeyIndexes;

  /**
   * Uses DataStax JavaDriver to fetch Cassandra cluster metadata.
   *
   * @param host Hostname of a node in the cluster.
   * @param port Binary/cql protocol port. Optional.
   */
  public CassandraClusterInfo(final String host, final int port) {
    this.host = host;
    this.port = port;
  }

  public void init(final String keyspace, final String columnFamily) {

    this.keyspace = keyspace;
    this.columnFamily = columnFamily;

    // connect to the cluster
    Cluster.Builder clusterBuilder = Cluster.builder();
    clusterBuilder.addContactPoints(host);
    if (port != -1) {
      clusterBuilder.withPort(port);
    }

    // ask for some metadata
    logger.info("getting cluster metadata for {}.{}", keyspace, columnFamily);
    final TableMetadata tableMetadata;
    try (final Cluster cluster = clusterBuilder.build()) {
      Metadata clusterMetadata = cluster.getMetadata();
      KeyspaceMetadata keyspaceMetadata = clusterMetadata.getKeyspace('"' + keyspace + '"');
      tableMetadata = keyspaceMetadata.getTable('"' + columnFamily + '"');
      cqlSchema = tableMetadata.asCQLQuery();
      partitionerClass = clusterMetadata.getPartitioner();
      Class.forName(partitionerClass);
      numClusterNodes = clusterMetadata.getAllHosts().size();
      columns = tableMetadata.getColumns();
    } catch (ClassNotFoundException cnfe) {
      throw new CrunchRuntimeException("No such partitioner: " + partitionerClass, cnfe);
    } catch (NullPointerException npe) {
      String msg = String.format("No such keyspace/table: %s/%s", keyspace, columnFamily);
      throw new CrunchRuntimeException(msg, npe);
    }

    // map the partition key columns
    final List<ColumnMetadata> partitionKeyColumns = tableMetadata.getPartitionKey();
    partitionKeyIndexes = new int[partitionKeyColumns.size()];
    for (int i = 0; i < partitionKeyColumns.size(); i++) {
      final String keyColName = partitionKeyColumns.get(i).getName();
      int j;
      for (j = 0; j < columns.size(); j++) {
        if (columns.get(j).getName().equals(keyColName)) {
          partitionKeyIndexes[i] = j;
          logger.info("partition key column {} index {}", keyColName, j);
          break;
        }
      }
      if (j == columns.size()) {
        throw new CrunchRuntimeException("no matching column for key " + keyColName);
      }
    }
  }

  /**
   * The partitioner used by the Cassandra cluster
   *
   * @return The full class name of the partitioner or null, if error
   */
  public String getPartitionerClass() {
    return partitionerClass;
  }

  /**
   * The number of nodes participating in the cluster
   *
   * @return The number of nodes or zero, if error
   */
  public int getNumClusterNodes() {
    return numClusterNodes;
  }

  /**
   * CQL schema of the table data is imported to
   *
   * @return valid CQL command to create the table
   */
  public String getCqlSchema() {
    return cqlSchema;
  }

  public int[] getPartitionKeyIndexes() {
    return partitionKeyIndexes;
  }

  /**
   * Get all column names from table metadata. Used if
   * {@link com.spotify.hdfs2cass.cassandra.utils.CassandraParams} don't specify column names.
   */
  public String[] getAllColumnNames() {
    List<String> colNames = Lists.newArrayList();
    for (ColumnMetadata col : columns) {
      colNames.add(col.getName());
    }
    return colNames.toArray(new String[colNames.size()]);
  }

  /**
   * Prepare the insert statement with column names ordered as they appear in columnNames.
   *
   * @param columnNames array of column names
   * @return Prepared insert statement, e.g. 'INSERT INTO ks.table (column) VALUES (?);'
   */
  public String buildPreparedStatement(String[] columnNames) {
    StringBuilder colNames = new StringBuilder();
    StringBuilder valueTemplates = new StringBuilder();
    for (String col : columnNames) {
      colNames.append(String.format("%s, ", col));
      valueTemplates.append("?, ");
    }
    // remove last ','
    colNames.deleteCharAt(colNames.lastIndexOf(","));
    valueTemplates.deleteCharAt(valueTemplates.lastIndexOf(","));
    return String.format("INSERT INTO %s.%s (%s) VALUES (%s) USING TIMESTAMP ? AND TTL ?;",
        keyspace, columnFamily, colNames.toString(), valueTemplates.toString());
  }

}
