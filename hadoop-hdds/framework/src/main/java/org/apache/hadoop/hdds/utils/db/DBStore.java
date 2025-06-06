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

package org.apache.hadoop.hdds.utils.db;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import org.apache.hadoop.hdds.annotation.InterfaceStability;
import org.apache.hadoop.hdds.utils.db.cache.TableCache;
import org.apache.hadoop.hdds.utils.db.cache.TableCache.CacheType;
import org.apache.hadoop.hdds.utils.db.managed.ManagedCompactRangeOptions;
import org.apache.ozone.rocksdiff.RocksDBCheckpointDiffer;

/**
 * The DBStore interface provides the ability to create Tables, which store
 * a specific type of Key-Value pair. Some DB interfaces like LevelDB will not
 * be able to do this. In those case a Table creation will map to a default
 * store.
 *
 */
@InterfaceStability.Evolving
public interface DBStore extends Closeable, BatchOperationHandler {

  /**
   * Gets an existing TableStore.
   *
   * @param name - Name of the TableStore to get
   * @return - TableStore.
   * @throws IOException on Failure
   */
  Table<byte[], byte[]> getTable(String name) throws IOException;

  /** The same as getTable(name, keyCodec, valueCodec, CacheType.PARTIAL_CACHE). */
  default <KEY, VALUE> TypedTable<KEY, VALUE> getTable(String name, Codec<KEY> keyCodec, Codec<VALUE> valueCodec)
      throws IOException {
    return getTable(name, keyCodec, valueCodec, CacheType.PARTIAL_CACHE);
  }

  /**
   * Gets table store with implict key/value conversion.
   *
   * @param name - table name
   * @param keyCodec - key codec
   * @param valueCodec - value codec
   * @param cacheType - cache type
   * @return - Table Store
   * @throws IOException
   */
  <KEY, VALUE> TypedTable<KEY, VALUE> getTable(
      String name, Codec<KEY> keyCodec, Codec<VALUE> valueCodec, TableCache.CacheType cacheType) throws IOException;

  /**
   * Lists the Known list of Tables in a DB.
   *
   * @return List of Tables, in case of Rocks DB and LevelDB we will return at
   * least one entry called DEFAULT.
   * @throws IOException on Failure
   */
  ArrayList<Table> listTables() throws IOException;

  /**
   * Flush the DB buffer onto persistent storage.
   * @throws IOException
   */
  void flushDB() throws IOException;

  /**
   * Flush the outstanding I/O operations of the DB.
   * @param sync if true will sync the outstanding I/Os to the disk.
   */
  void flushLog(boolean sync) throws IOException;

  /**
   * Returns the RocksDB checkpoint differ.
   */
  RocksDBCheckpointDiffer getRocksDBCheckpointDiffer();

  /**
   * Compact the entire database.
   *
   * @throws IOException on Failure
   */
  void compactDB() throws IOException;

  /**
   * Compact the specific table.
   *
   * @param tableName - Name of the table to compact.
   * @throws IOException on Failure
   */
  void compactTable(String tableName) throws IOException;

  /**
   * Compact the specific table.
   *
   * @param tableName - Name of the table to compact.
   * @param options - Options for the compact operation.
   * @throws IOException on Failure
   */
  void compactTable(String tableName, ManagedCompactRangeOptions options) throws IOException;

  /**
   * Moves a key from the Source Table to the destination Table.
   *
   * @param key - Key to move.
   * @param source - Source Table.
   * @param dest - Destination Table.
   * @throws IOException on Failure
   */
  <KEY, VALUE> void move(KEY key, Table<KEY, VALUE> source,
                         Table<KEY, VALUE> dest) throws IOException;

  /**
   * Moves a key from the Source Table to the destination Table and updates the
   * destination to the new value.
   *
   * @param key - Key to move.
   * @param value - new value to write to the destination table.
   * @param source - Source Table.
   * @param dest - Destination Table.
   * @throws IOException on Failure
   */
  <KEY, VALUE> void move(KEY key, VALUE value, Table<KEY, VALUE> source,
                         Table<KEY, VALUE> dest)
      throws IOException;

  /**
   * Moves a key from the Source Table to the destination Table and updates the
   * destination with the new key name and value.
   * This is similar to deleting an entry in one table and adding an entry in
   * another table, here it is done atomically.
   *
   * @param sourceKey - Key to move.
   * @param destKey - Destination key name.
   * @param value - new value to write to the destination table.
   * @param source - Source Table.
   * @param dest - Destination Table.
   * @throws IOException on Failure
   */
  <KEY, VALUE> void move(KEY sourceKey, KEY destKey, VALUE value,
                         Table<KEY, VALUE> source, Table<KEY, VALUE> dest)
      throws IOException;

  /**
   * Returns an estimated count of keys in this DB.
   *
   * @return long, estimate of keys in the DB.
   */
  long getEstimatedKeyCount() throws IOException;


  /**
   * Get current snapshot of DB store as an artifact stored on
   * the local filesystem.
   * @return An object that encapsulates the checkpoint information along with
   * location.
   */
  DBCheckpoint getCheckpoint(boolean flush) throws IOException;

  /**
   * Get current snapshot of DB store as an artifact stored on
   * the local filesystem with different parent path.
   * @return An object that encapsulates the checkpoint information along with
   * location.
   */
  DBCheckpoint getCheckpoint(String parentDir, boolean flush) throws IOException;

  /**
   * Get DB Store location.
   * @return DB file location.
   */
  File getDbLocation();

  /**
   * Get List of Index to Table Names.
   * (For decoding table from column family index)
   * @return Map of Index -&gt; TableName
   */
  Map<Integer, String> getTableNames();

  /**
   * Get data written to DB since a specific sequence number.
   */
  DBUpdatesWrapper getUpdatesSince(long sequenceNumber)
      throws IOException;

  /**
   * Get limited data written to DB since a specific sequence number.
   */
  DBUpdatesWrapper getUpdatesSince(long sequenceNumber, long limitCount)
      throws IOException;

  /**
   * Return if the underlying DB is closed. This call is thread safe.
   * @return true if the DB is closed.
   */
  boolean isClosed();
}
