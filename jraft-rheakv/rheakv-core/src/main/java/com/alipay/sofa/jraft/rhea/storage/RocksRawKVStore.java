/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.jraft.rhea.storage;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

import org.apache.commons.io.FileUtils;
import org.rocksdb.BackupEngine;
import org.rocksdb.BackupInfo;
import org.rocksdb.BackupableDBOptions;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.Checkpoint;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompactionStyle;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.Env;
import org.rocksdb.EnvOptions;
import org.rocksdb.IngestExternalFileOptions;
import org.rocksdb.MergeOperator;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RestoreOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Snapshot;
import org.rocksdb.SstFileWriter;
import org.rocksdb.Statistics;
import org.rocksdb.StatisticsCollectorCallback;
import org.rocksdb.StatsCollectorInput;
import org.rocksdb.StringAppendOperator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.rocksdb.util.SizeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alipay.sofa.jraft.rhea.errors.StorageException;
import com.alipay.sofa.jraft.rhea.options.RocksDBOptions;
import com.alipay.sofa.jraft.rhea.rocks.support.RocksStatisticsCollector;
import com.alipay.sofa.jraft.rhea.serialization.Serializer;
import com.alipay.sofa.jraft.rhea.serialization.Serializers;
import com.alipay.sofa.jraft.rhea.util.ByteArray;
import com.alipay.sofa.jraft.rhea.util.Lists;
import com.alipay.sofa.jraft.rhea.util.Maps;
import com.alipay.sofa.jraft.rhea.util.Partitions;
import com.alipay.sofa.jraft.rhea.util.StackTraceUtil;
import com.alipay.sofa.jraft.rhea.util.concurrent.DistributedLock;
import com.alipay.sofa.jraft.util.Bits;
import com.alipay.sofa.jraft.util.BytesUtil;
import com.codahale.metrics.Timer;
import com.google.protobuf.ByteString;

import static com.alipay.sofa.jraft.entity.LocalFileMetaOutter.LocalFileMeta;
import static com.alipay.sofa.jraft.rhea.rocks.support.RocksConfigs.ENV_BACKGROUND_COMPACTION_THREADS;
import static com.alipay.sofa.jraft.rhea.rocks.support.RocksConfigs.ENV_BACKGROUND_FLUSH_THREADS;
import static com.alipay.sofa.jraft.rhea.rocks.support.RocksConfigs.LEVEL0_FILE_NUM_COMPACTION_TRIGGER;
import static com.alipay.sofa.jraft.rhea.rocks.support.RocksConfigs.LEVEL0_SLOWDOWN_WRITES_TRIGGER;
import static com.alipay.sofa.jraft.rhea.rocks.support.RocksConfigs.LEVEL0_STOP_WRITES_TRIGGER;
import static com.alipay.sofa.jraft.rhea.rocks.support.RocksConfigs.MAX_BACKGROUND_JOBS;
import static com.alipay.sofa.jraft.rhea.rocks.support.RocksConfigs.MAX_BYTES_FOR_LEVEL_BASE;
import static com.alipay.sofa.jraft.rhea.rocks.support.RocksConfigs.MAX_BATCH_WRITE_SIZE;
import static com.alipay.sofa.jraft.rhea.rocks.support.RocksConfigs.MAX_LOG_FILE_SIZE;
import static com.alipay.sofa.jraft.rhea.rocks.support.RocksConfigs.MAX_OPEN_FILES;
import static com.alipay.sofa.jraft.rhea.rocks.support.RocksConfigs.MAX_WRITE_BUFFER_NUMBER;
import static com.alipay.sofa.jraft.rhea.rocks.support.RocksConfigs.MIN_WRITE_BUFFER_NUMBER_TO_MERGE;
import static com.alipay.sofa.jraft.rhea.rocks.support.RocksConfigs.TARGET_FILE_SIZE_BASE;
import static com.alipay.sofa.jraft.rhea.rocks.support.RocksConfigs.WRITE_BUFFER_SIZE;

/**
 * Local KV store based on RocksDB
 *
 * @author dennis
 * @author jiachun.fjc
 */
public class RocksRawKVStore extends BatchRawKVStore<RocksDBOptions> {

    private static final Logger                LOG             = LoggerFactory.getLogger(RocksRawKVStore.class);

    static {
        RocksDB.loadLibrary();
    }

    private final ReadWriteLock                readWriteLock   = new ReentrantReadWriteLock();

    private final AtomicLong                   databaseVersion = new AtomicLong(0);
    private final Serializer                   serializer      = Serializers.getDefault();

    private final List<ColumnFamilyOptions>    cfOptionsList   = Lists.newArrayList();
    private final List<ColumnFamilyDescriptor> cfDescriptors   = Lists.newArrayList();

    private ColumnFamilyHandle                 defaultHandle;
    private ColumnFamilyHandle                 sequenceHandle;
    private ColumnFamilyHandle                 lockingHandle;
    private ColumnFamilyHandle                 fencingHandle;

    private RocksDB                            db;

    private RocksDBOptions                     opts;
    private DBOptions                          options;
    private WriteOptions                       writeOptions;
    private MergeOperator                      mergeOperator;
    private Statistics                         statistics;
    private RocksStatisticsCollector           statisticsCollector;

    @Override
    public boolean init(final RocksDBOptions opts) {
        final Lock writeLock = this.readWriteLock.writeLock();
        writeLock.lock();
        try {
            if (this.db != null) {
                LOG.info("[RocksRawKVStore] already started.");
                return true;
            }
            this.mergeOperator = new StringAppendOperator();
            this.opts = opts;
            this.options = createDBOptions();
            if (opts.isOpenStatisticsCollector()) {
                this.statistics = new Statistics();
                this.options.setStatistics(this.statistics);
                final long intervalSeconds = opts.getStatisticsCallbackIntervalSeconds();
                if (intervalSeconds > 0) {
                    this.statisticsCollector = new RocksStatisticsCollector(TimeUnit.SECONDS.toMillis(intervalSeconds));
                    this.statisticsCollector.start();
                }
            }
            final ColumnFamilyOptions cfOptions = createColumnFamilyOptions(this.mergeOperator);
            this.cfOptionsList.add(cfOptions);
            // default column family
            this.cfDescriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOptions));
            // sequence column family
            this.cfDescriptors.add(new ColumnFamilyDescriptor(BytesUtil.writeUtf8("RHEA_SEQUENCE"), cfOptions));
            // locking column family
            this.cfDescriptors.add(new ColumnFamilyDescriptor(BytesUtil.writeUtf8("RHEA_LOCKING"), cfOptions));
            // fencing column family
            this.cfDescriptors.add(new ColumnFamilyDescriptor(BytesUtil.writeUtf8("RHEA_FENCING"), cfOptions));
            this.writeOptions = new WriteOptions();
            this.writeOptions.setSync(opts.isSync());
            this.writeOptions.setDisableWAL(false);
            openRocksDB(opts);
            LOG.info("[RocksRawKVStore] start successfully, options: {}.", opts);
            return true;
        } catch (final RocksDBException e) {
            LOG.error("Fail to open rocksDB at path {}, {}.", opts.getDbPath(), StackTraceUtil.stackTrace(e));
        } finally {
            writeLock.unlock();
        }
        return false;
    }

    @Override
    public void shutdown() {
        final Lock writeLock = this.readWriteLock.writeLock();
        writeLock.lock();
        try {
            if (this.db == null) {
                return;
            }
            closeRocksDB();
            if (this.defaultHandle != null) {
                this.defaultHandle.close();
            }
            if (this.sequenceHandle != null) {
                this.sequenceHandle.close();
            }
            if (this.lockingHandle != null) {
                this.lockingHandle.close();
            }
            if (this.fencingHandle != null) {
                this.fencingHandle.close();
            }
            for (final ColumnFamilyOptions cfOptions : this.cfOptionsList) {
                cfOptions.close();
            }
            this.cfOptionsList.clear();
            this.cfDescriptors.clear();
            if (this.options != null) {
                this.options.close();
            }
            if (this.statisticsCollector != null) {
                try {
                    this.statisticsCollector.shutDown(3000);
                } catch (final Throwable ignored) {
                    // ignored
                }
            }
            if (this.statistics != null) {
                this.statistics.close();
            }
            if (this.mergeOperator != null) {
                this.mergeOperator.close();
            }
            if (this.writeOptions != null) {
                this.writeOptions.close();
            }
        } finally {
            writeLock.unlock();
            LOG.info("[RocksRawKVStore] shutdown successfully.");
        }
    }

    @Override
    public KVIterator localIterator() {
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try {
            return new RocksKVIterator(this, this.db.newIterator(), readLock, getDatabaseVersion());
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void get(final byte[] key, @SuppressWarnings("unused") final boolean readOnlySafe,
                    final KVStoreClosure closure) {
        final Timer.Context timeCtx = getTimeContext("GET");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try {
            final byte[] value = this.db.get(key);
            setSuccess(closure, value);
        } catch (final Exception e) {
            LOG.error("Fail to [GET], key: [{}], {}.", Arrays.toString(key), StackTraceUtil.stackTrace(e));
            setFailure(closure, "Fail to [GET]");
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public void multiGet(final List<byte[]> keys, @SuppressWarnings("unused") final boolean readOnlySafe,
                         final KVStoreClosure closure) {
        final Timer.Context timeCtx = getTimeContext("MULTI_GET");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try {
            final Map<byte[], byte[]> rawMap = this.db.multiGet(keys);
            final Map<ByteArray, byte[]> resultMap = Maps.newHashMapWithExpectedSize(rawMap.size());
            for (final Map.Entry<byte[], byte[]> entry : rawMap.entrySet()) {
                resultMap.put(ByteArray.wrap(entry.getKey()), entry.getValue());
            }
            setSuccess(closure, resultMap);
        } catch (final Exception e) {
            LOG.error("Fail to [MULTI_GET], key size: [{}], {}.", keys.size(), StackTraceUtil.stackTrace(e));
            setFailure(closure, "Fail to [MULTI_GET]");
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public void scan(final byte[] startKey, final byte[] endKey, final int limit,
                     @SuppressWarnings("unused") final boolean readOnlySafe, final KVStoreClosure closure) {
        final Timer.Context timeCtx = getTimeContext("SCAN");
        final List<KVEntry> entries = Lists.newArrayList();
        // If limit == 0, it will be modified to Integer.MAX_VALUE on the server
        // and then queried.  So 'limit == 0' means that the number of queries is
        // not limited. This is because serialization uses varint to compress
        // numbers.  In the case of 0, only 1 byte is occupied, and Integer.MAX_VALUE
        // takes 5 bytes.
        final int maxCount = limit > 0 ? limit : Integer.MAX_VALUE;
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try (final RocksIterator it = this.db.newIterator()) {
            if (startKey == null) {
                it.seekToFirst();
            } else {
                it.seek(startKey);
            }
            int count = 0;
            while (it.isValid() && count++ < maxCount) {
                final byte[] key = it.key();
                if (endKey != null && BytesUtil.compare(key, endKey) >= 0) {
                    break;
                }
                entries.add(new KVEntry(key, it.value()));
                it.next();
            }
            setSuccess(closure, entries);
        } catch (final Exception e) {
            LOG.error("Fail to [SCAN], range: ['[{}, {})'], {}.",
                    Arrays.toString(startKey), Arrays.toString(endKey), StackTraceUtil.stackTrace(e));
            setFailure(closure, "Fail to [SCAN]");
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public void getSequence(final byte[] seqKey, final int step, final KVStoreClosure closure) {
        final Timer.Context timeCtx = getTimeContext("GET_SEQUENCE");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try {
            final byte[] prevBytesVal = this.db.get(this.sequenceHandle, seqKey);
            long startVal;
            if (prevBytesVal == null) {
                startVal = 0;
            } else {
                startVal = Bits.getLong(prevBytesVal, 0);
            }
            final long endVal = Math.max(startVal, (startVal + step) & Long.MAX_VALUE);
            final byte[] newBytesVal = new byte[8];
            Bits.putLong(newBytesVal, 0, endVal);
            this.db.put(this.sequenceHandle, this.writeOptions, seqKey, newBytesVal);
            setSuccess(closure, new Sequence(startVal, endVal));
        } catch (final Exception e) {
            LOG.error("Fail to [GET_SEQUENCE], [key = {}, step = {}], {}.", Arrays.toString(seqKey), step,
                StackTraceUtil.stackTrace(e));
            setFailure(closure, "Fail to [GET_SEQUENCE]");
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public void resetSequence(final byte[] seqKey, final KVStoreClosure closure) {
        final Timer.Context timeCtx = getTimeContext("RESET_SEQUENCE");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try {
            this.db.delete(this.sequenceHandle, seqKey);
            setSuccess(closure, Boolean.TRUE);
        } catch (final Exception e) {
            LOG.error("Fail to [RESET_SEQUENCE], [key = {}], {}.", Arrays.toString(seqKey),
                StackTraceUtil.stackTrace(e));
            setFailure(closure, "Fail to [RESET_SEQUENCE]");
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public void batchResetSequence(final KVStateOutputList kvStates) {
        if (kvStates.isSingletonList()) {
            final KVState kvState = kvStates.getSingletonElement();
            resetSequence(kvState.getOp().getKey(), kvState.getDone());
            return;
        }
        final Timer.Context timeCtx = getTimeContext("BATCH_RESET_SEQUENCE");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try {
            Partitions.manyToOne(kvStates, MAX_BATCH_WRITE_SIZE, (Function<List<KVState>, Void>) segment -> {
                try (final WriteBatch batch = new WriteBatch()) {
                    for (final KVState kvState : segment) {
                        batch.delete(sequenceHandle, kvState.getOp().getKey());
                    }
                    this.db.write(this.writeOptions, batch);
                    for (final KVState kvState : segment) {
                        setSuccess(kvState.getDone(), Boolean.TRUE);
                    }
                } catch (final Exception e) {
                    LOG.error("Failed to [BATCH_RESET_SEQUENCE],  [size = {}], {}.",
                            segment.size(), StackTraceUtil.stackTrace(e));
                    for (final KVState kvState : segment) {
                        setFailure(kvState.getDone(), "Fail to [BATCH_RESET_SEQUENCE]");
                    }
                }
                return null;
            });
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public void put(final byte[] key, final byte[] value, final KVStoreClosure closure) {
        final Timer.Context timeCtx = getTimeContext("PUT");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try {
            this.db.put(this.writeOptions, key, value);
            setSuccess(closure, Boolean.TRUE);
        } catch (final Exception e) {
            LOG.error("Fail to [PUT], [{}, {}], {}.", Arrays.toString(key), Arrays.toString(value),
                StackTraceUtil.stackTrace(e));
            setFailure(closure, "Fail to [PUT]");
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public void batchPut(final KVStateOutputList kvStates) {
        if (kvStates.isSingletonList()) {
            final KVState kvState = kvStates.getSingletonElement();
            final KVOperation op = kvState.getOp();
            put(op.getKey(), op.getValue(), kvState.getDone());
            return;
        }
        final Timer.Context timeCtx = getTimeContext("BATCH_PUT");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try {
            Partitions.manyToOne(kvStates, MAX_BATCH_WRITE_SIZE, (Function<List<KVState>, Void>) segment -> {
                try (final WriteBatch batch = new WriteBatch()) {
                    for (final KVState kvState : segment) {
                        final KVOperation op = kvState.getOp();
                        batch.put(op.getKey(), op.getValue());
                    }
                    this.db.write(this.writeOptions, batch);
                    for (final KVState kvState : segment) {
                        setSuccess(kvState.getDone(), Boolean.TRUE);
                    }
                } catch (final Exception e) {
                    LOG.error("Failed to [BATCH_PUT], [size = {}] {}.", segment.size(),
                            StackTraceUtil.stackTrace(e));
                    for (final KVState kvState : segment) {
                        setFailure(kvState.getDone(), "Fail to [BATCH_PUT]");
                    }
                }
                return null;
            });
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public void getAndPut(final byte[] key, final byte[] value, final KVStoreClosure closure) {
        final Timer.Context timeCtx = getTimeContext("GET_PUT");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try {
            final byte[] prevVal = this.db.get(key);
            this.db.put(this.writeOptions, key, value);
            setSuccess(closure, prevVal);
        } catch (final Exception e) {
            LOG.error("Fail to [GET_PUT], [{}, {}], {}.", Arrays.toString(key), Arrays.toString(value),
                StackTraceUtil.stackTrace(e));
            setFailure(closure, "Fail to [GET_PUT]");
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public void batchGetAndPut(final KVStateOutputList kvStates) {
        if (kvStates.isSingletonList()) {
            final KVState kvState = kvStates.getSingletonElement();
            final KVOperation op = kvState.getOp();
            getAndPut(op.getKey(), op.getValue(), kvState.getDone());
            return;
        }
        final Timer.Context timeCtx = getTimeContext("BATCH_GET_PUT");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try {
            Partitions.manyToOne(kvStates, MAX_BATCH_WRITE_SIZE, (Function<List<KVState>, Void>) segment -> {
                try (final WriteBatch batch = new WriteBatch()) {
                    final List<byte[]> keys = Lists.newArrayListWithCapacity(segment.size());
                    for (final KVState kvState : segment) {
                        final KVOperation op = kvState.getOp();
                        final byte[] key = op.getKey();
                        keys.add(key);
                        batch.put(key, op.getValue());
                    }
                    // first, get prev values
                    final Map<byte[], byte[]> prevValMap = this.db.multiGet(keys);
                    this.db.write(this.writeOptions, batch);
                    for (final KVState kvState : segment) {
                        setSuccess(kvState.getDone(), prevValMap.get(kvState.getOp().getKey()));
                    }
                } catch (final Exception e) {
                    LOG.error("Failed to [BATCH_GET_PUT], [size = {}] {}.", segment.size(),
                            StackTraceUtil.stackTrace(e));
                    for (final KVState kvState : segment) {
                        setFailure(kvState.getDone(), "Fail to [BATCH_GET_PUT]");
                    }
                }
                return null;
            });
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public void merge(final byte[] key, final byte[] value, final KVStoreClosure closure) {
        final Timer.Context timeCtx = getTimeContext("MERGE");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try {
            this.db.merge(this.writeOptions, key, value);
            setSuccess(closure, Boolean.TRUE);
        } catch (final Exception e) {
            LOG.error("Fail to [MERGE], [{}, {}], {}.", Arrays.toString(key), Arrays.toString(value),
                StackTraceUtil.stackTrace(e));
            setFailure(closure, "Fail to [MERGE]");
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public void batchMerge(final KVStateOutputList kvStates) {
        if (kvStates.isSingletonList()) {
            final KVState kvState = kvStates.getSingletonElement();
            final KVOperation op = kvState.getOp();
            merge(op.getKey(), op.getValue(), kvState.getDone());
            return;
        }
        final Timer.Context timeCtx = getTimeContext("BATCH_MERGE");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try {
            Partitions.manyToOne(kvStates, MAX_BATCH_WRITE_SIZE, (Function<List<KVState>, Void>) segment -> {
                try (final WriteBatch batch = new WriteBatch()) {
                    for (final KVState kvState : segment) {
                        final KVOperation op = kvState.getOp();
                        batch.merge(op.getKey(), op.getValue());
                    }
                    this.db.write(this.writeOptions, batch);
                    for (final KVState kvState : segment) {
                        setSuccess(kvState.getDone(), Boolean.TRUE);
                    }
                } catch (final Exception e) {
                    LOG.error("Failed to [BATCH_MERGE], [size = {}] {}.", segment.size(),
                            StackTraceUtil.stackTrace(e));
                    for (final KVState kvState : segment) {
                        setFailure(kvState.getDone(), "Fail to [BATCH_MERGE]");
                    }
                }
                return null;
            });
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public void put(final List<KVEntry> entries, final KVStoreClosure closure) {
        final Timer.Context timeCtx = getTimeContext("PUT_LIST");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try (final WriteBatch batch = new WriteBatch()) {
            for (final KVEntry entry : entries) {
                batch.put(entry.getKey(), entry.getValue());
            }
            this.db.write(this.writeOptions, batch);
            setSuccess(closure, Boolean.TRUE);
        } catch (final Exception e) {
            LOG.error("Failed to [PUT_LIST], [size = {}], {}.", entries.size(), StackTraceUtil.stackTrace(e));
            setFailure(closure, "Fail to [PUT_LIST]");
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public void putIfAbsent(final byte[] key, final byte[] value, final KVStoreClosure closure) {
        final Timer.Context timeCtx = getTimeContext("PUT_IF_ABSENT");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try {
            final byte[] prevVal = this.db.get(key);
            if (prevVal == null) {
                this.db.put(this.writeOptions, key, value);
            }
            setSuccess(closure, prevVal);
        } catch (final Exception e) {
            LOG.error("Fail to [PUT_IF_ABSENT], [{}, {}], {}.", Arrays.toString(key), Arrays.toString(value),
                StackTraceUtil.stackTrace(e));
            setFailure(closure, "Fail to [PUT_IF_ABSENT]");
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public void tryLockWith(final byte[] key, final boolean keepLease, final DistributedLock.Acquirer acquirer,
                            final KVStoreClosure closure) {
        final Timer.Context timeCtx = getTimeContext("TRY_LOCK");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try {
            // The algorithm relies on the assumption that while there is no
            // synchronized clock across the processes, still the local time in
            // every process flows approximately at the same rate, with an error
            // which is small compared to the auto-release time of the lock.
            final long now = acquirer.getLockingTimestamp();
            final long timeoutMillis = acquirer.getLeaseMillis();
            final byte[] prevBytesVal = this.db.get(this.lockingHandle, key);

            final DistributedLock.Owner owner;
            // noinspection ConstantConditions
            do {
                final DistributedLock.OwnerBuilder builder = DistributedLock.newOwnerBuilder();
                if (prevBytesVal == null) {
                    // no others own this lock
                    if (keepLease) {
                        // it wants to keep the lease but too late, will return failure
                        owner = builder //
                            // set acquirer id
                            .id(acquirer.getId())
                            // fail to keep lease
                            .remainingMillis(DistributedLock.OwnerBuilder.KEEP_LEASE_FAIL)
                            // set failure
                            .success(false).build();
                        break;
                    }
                    // is first time to try lock (another possibility is that this lock has been deleted),
                    // will return successful
                    owner = builder //
                        // set acquirer id, now it will own the lock
                        .id(acquirer.getId())
                        // set a new deadline
                        .deadlineMillis(now + timeoutMillis)
                        // first time to acquire and success
                        .remainingMillis(DistributedLock.OwnerBuilder.FIRST_TIME_SUCCESS)
                        // create a new fencing token
                        .fencingToken(getNextFencingToken(LOCK_FENCING_KEY))
                        // init acquires
                        .acquires(1)
                        // set acquirer ctx
                        .context(acquirer.getContext())
                        // set successful
                        .success(true).build();
                    this.db.put(this.lockingHandle, this.writeOptions, key, this.serializer.writeObject(owner));
                    break;
                }

                // this lock has an owner, check if it has expired
                final DistributedLock.Owner prevOwner = this.serializer.readObject(prevBytesVal,
                    DistributedLock.Owner.class);
                final long remainingMillis = prevOwner.getDeadlineMillis() - now;
                if (remainingMillis < 0) {
                    // the previous owner is out of lease
                    if (keepLease) {
                        // it wants to keep the lease but too late, will return failure
                        owner = builder //
                            // still previous owner id
                            .id(prevOwner.getId())
                            // do not update
                            .deadlineMillis(prevOwner.getDeadlineMillis())
                            // fail to keep lease
                            .remainingMillis(DistributedLock.OwnerBuilder.KEEP_LEASE_FAIL)
                            // set previous ctx
                            .context(prevOwner.getContext())
                            // set failure
                            .success(false).build();
                        break;
                    }
                    // create new lock owner
                    owner = builder //
                        // set acquirer id, now it will own the lock
                        .id(acquirer.getId())
                        // set a new deadline
                        .deadlineMillis(now + timeoutMillis)
                        // success as a new acquirer
                        .remainingMillis(DistributedLock.OwnerBuilder.NEW_ACQUIRE_SUCCESS)
                        // create a new fencing token
                        .fencingToken(getNextFencingToken(LOCK_FENCING_KEY))
                        // init acquires
                        .acquires(1)
                        // set acquirer ctx
                        .context(acquirer.getContext())
                        // set successful
                        .success(true).build();
                    this.db.put(this.lockingHandle, this.writeOptions, key, this.serializer.writeObject(owner));
                    break;
                }

                // the previous owner is not out of lease (remainingMillis >= 0)
                final boolean isReentrant = prevOwner.isSameAcquirer(acquirer);
                if (isReentrant) {
                    // is the same old friend come back (reentrant lock)
                    if (keepLease) {
                        // the old friend only wants to keep lease of lock
                        owner = builder //
                            // still previous owner id
                            .id(prevOwner.getId())
                            // update the deadline to keep lease
                            .deadlineMillis(now + timeoutMillis)
                            // success to keep lease
                            .remainingMillis(DistributedLock.OwnerBuilder.KEEP_LEASE_SUCCESS)
                            // keep fencing token
                            .fencingToken(prevOwner.getFencingToken())
                            // keep acquires
                            .acquires(prevOwner.getAcquires())
                            // do not update ctx when keeping lease
                            .context(prevOwner.getContext())
                            // set successful
                            .success(true).build();
                        this.db.put(this.lockingHandle, this.writeOptions, key, this.serializer.writeObject(owner));
                        break;
                    }
                    // now we are sure that is an old friend who is back again (reentrant lock)
                    owner = builder //
                        // still previous owner id
                        .id(prevOwner.getId())
                        // by the way, the lease will also be kept
                        .deadlineMillis(now + timeoutMillis)
                        // success reentrant
                        .remainingMillis(DistributedLock.OwnerBuilder.REENTRANT_SUCCESS)
                        // keep fencing token
                        .fencingToken(prevOwner.getFencingToken())
                        // acquires++
                        .acquires(prevOwner.getAcquires() + 1)
                        // update ctx when reentrant
                        .context(acquirer.getContext())
                        // set successful
                        .success(true).build();
                    this.db.put(this.lockingHandle, this.writeOptions, key, this.serializer.writeObject(owner));
                    break;
                }

                // the lock is exist and also prev locker is not the same as current
                owner = builder //
                    // set previous owner id to tell who is the real owner
                    .id(prevOwner.getId())
                    // set the remaining lease time of current owner
                    .remainingMillis(remainingMillis)
                    // set previous ctx
                    .context(prevOwner.getContext())
                    // set failure
                    .success(false).build();
                LOG.debug("Another locker [{}] is trying the existed lock [{}].", acquirer, prevOwner);
            } while (false);

            setSuccess(closure, owner);
        } catch (final Exception e) {
            LOG.error("Fail to [TRY_LOCK], [{}, {}], {}.", Arrays.toString(key), acquirer, StackTraceUtil.stackTrace(e));
            setFailure(closure, "Fail to [TRY_LOCK]");
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public void releaseLockWith(final byte[] key, final DistributedLock.Acquirer acquirer, final KVStoreClosure closure) {
        final Timer.Context timeCtx = getTimeContext("RELEASE_LOCK");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try {
            final byte[] prevBytesVal = this.db.get(this.lockingHandle, key);

            final DistributedLock.Owner owner;
            // noinspection ConstantConditions
            do {
                final DistributedLock.OwnerBuilder builder = DistributedLock.newOwnerBuilder();
                if (prevBytesVal == null) {
                    LOG.warn("Lock not exist: {}.", acquirer);
                    owner = builder //
                        // set acquirer id
                        .id(acquirer.getId())
                        // set acquirer fencing token
                        .fencingToken(acquirer.getFencingToken())
                        // set acquires=0
                        .acquires(0)
                        // set successful
                        .success(true).build();
                    break;
                }

                final DistributedLock.Owner prevOwner = this.serializer.readObject(prevBytesVal,
                    DistributedLock.Owner.class);

                if (prevOwner.isSameAcquirer(acquirer)) {
                    final long acquires = prevOwner.getAcquires() - 1;
                    owner = builder //
                        // still previous owner id
                        .id(prevOwner.getId())
                        // do not update deadline
                        .deadlineMillis(prevOwner.getDeadlineMillis())
                        // keep fencing token
                        .fencingToken(prevOwner.getFencingToken())
                        // acquires--
                        .acquires(acquires)
                        // set previous ctx
                        .context(prevOwner.getContext())
                        // set successful
                        .success(true).build();
                    if (acquires <= 0) {
                        // real delete, goodbye ~
                        this.db.delete(this.lockingHandle, this.writeOptions, key);
                    } else {
                        // acquires--
                        this.db.put(this.lockingHandle, this.writeOptions, key, this.serializer.writeObject(owner));
                    }
                    break;
                }

                // invalid acquirer, can't to release the lock
                owner = builder //
                    // set previous owner id to tell who is the real owner
                    .id(prevOwner.getId())
                    // keep previous fencing token
                    .fencingToken(prevOwner.getFencingToken())
                    // do not update acquires
                    .acquires(prevOwner.getAcquires())
                    // set previous ctx
                    .context(prevOwner.getContext())
                    // set failure
                    .success(false).build();
                LOG.warn("The lock owner is: [{}], [{}] could't release it.", prevOwner, acquirer);
            } while (false);

            setSuccess(closure, owner);
        } catch (final Exception e) {
            LOG.error("Fail to [RELEASE_LOCK], [{}], {}.", Arrays.toString(key), StackTraceUtil.stackTrace(e));
            setFailure(closure, "Fail to [RELEASE_LOCK]");
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    private long getNextFencingToken(final byte[] fencingKey) throws RocksDBException {
        final Timer.Context timeCtx = getTimeContext("FENCING_TOKEN");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try {
            final byte[] prevBytesVal = this.db.get(this.fencingHandle, fencingKey);
            final long prevVal;
            if (prevBytesVal == null) {
                prevVal = 0; // init
            } else {
                prevVal = Bits.getLong(prevBytesVal, 0);
            }
            // Don't worry about the token number overflow.
            // It takes about 290,000 years for the 1 million TPS system
            // to use the numbers in the range [0 ~ Long.MAX_VALUE].
            final long newVal = prevVal + 1;
            final byte[] newBytesVal = new byte[8];
            Bits.putLong(newBytesVal, 0, newVal);
            this.db.put(this.fencingHandle, this.writeOptions, fencingKey, newBytesVal);
            return newVal;
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public void delete(final byte[] key, final KVStoreClosure closure) {
        final Timer.Context timeCtx = getTimeContext("DELETE");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try {
            this.db.delete(this.writeOptions, key);
            setSuccess(closure, Boolean.TRUE);
        } catch (final Exception e) {
            LOG.error("Fail to [DELETE], [{}], {}.", Arrays.toString(key), StackTraceUtil.stackTrace(e));
            setFailure(closure, "Fail to [DELETE]");
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public void batchDelete(final KVStateOutputList kvStates) {
        if (kvStates.isSingletonList()) {
            final KVState kvState = kvStates.getSingletonElement();
            delete(kvState.getOp().getKey(), kvState.getDone());
            return;
        }
        final Timer.Context timeCtx = getTimeContext("BATCH_DELETE");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try {
            Partitions.manyToOne(kvStates, MAX_BATCH_WRITE_SIZE, (Function<List<KVState>, Void>) segment -> {
                try (final WriteBatch batch = new WriteBatch()) {
                    for (final KVState kvState : segment) {
                        batch.delete(kvState.getOp().getKey());
                    }
                    this.db.write(this.writeOptions, batch);
                    for (final KVState kvState : segment) {
                        setSuccess(kvState.getDone(), Boolean.TRUE);
                    }
                } catch (final Exception e) {
                    LOG.error("Failed to [BATCH_DELETE],  [size = {}], {}.",
                            segment.size(), StackTraceUtil.stackTrace(e));
                    for (final KVState kvState : segment) {
                        setFailure(kvState.getDone(), "Fail to [BATCH_DELETE]");
                    }
                }
                return null;
            });
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public void deleteRange(final byte[] startKey, final byte[] endKey, final KVStoreClosure closure) {
        final Timer.Context timeCtx = getTimeContext("DELETE_RANGE");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try {
            this.db.deleteRange(this.writeOptions, startKey, endKey);
            setSuccess(closure, Boolean.TRUE);
        } catch (final Exception e) {
            LOG.error("Fail to [DELETE_RANGE], ['[{}, {})'], {}.", Arrays.toString(startKey), Arrays.toString(endKey),
                StackTraceUtil.stackTrace(e));
            setFailure(closure, "Fail to [DELETE_RANGE]");
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public long getApproximateKeysInRange(final byte[] startKey, final byte[] endKey) {
        // TODO This is a sad code, the performance is too damn bad
        final Timer.Context timeCtx = getTimeContext("APPROXIMATE_KEYS");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        final Snapshot snapshot = this.db.getSnapshot();
        try (final ReadOptions readOptions = new ReadOptions()) {
            readOptions.setSnapshot(snapshot);
            try (final RocksIterator it = this.db.newIterator(readOptions)) {
                if (startKey == null) {
                    it.seekToFirst();
                } else {
                    it.seek(startKey);
                }
                long approximateKeys = 0;
                for (;;) {
                    // The accuracy is 100, don't ask more
                    for (int i = 0; i < 100; i++) {
                        if (!it.isValid()) {
                            return approximateKeys;
                        }
                        it.next();
                        ++approximateKeys;
                    }
                    if (endKey != null && BytesUtil.compare(it.key(), endKey) >= 0) {
                        return approximateKeys;
                    }
                }
            }
        } finally {
            // Nothing to release, rocksDB never own the pointer for a snapshot.
            snapshot.close();
            // The pointer to the snapshot is released by the database instance.
            this.db.releaseSnapshot(snapshot);
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public byte[] jumpOver(final byte[] startKey, final long distance) {
        final Timer.Context timeCtx = getTimeContext("JUMP_OVER");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        final Snapshot snapshot = this.db.getSnapshot();
        try (final ReadOptions readOptions = new ReadOptions()) {
            readOptions.setSnapshot(snapshot);
            try (final RocksIterator it = this.db.newIterator(readOptions)) {
                if (startKey == null) {
                    it.seekToFirst();
                } else {
                    it.seek(startKey);
                }
                long approximateKeys = 0;
                for (;;) {
                    byte[] lastKey = null;
                    if (it.isValid()) {
                        lastKey = it.key();
                    }
                    // The accuracy is 100, don't ask more
                    for (int i = 0; i < 100; i++) {
                        if (!it.isValid()) {
                            return lastKey;
                        }
                        it.next();
                        if (++approximateKeys >= distance) {
                            return it.key();
                        }
                    }
                }
            }
        } finally {
            // Nothing to release, rocksDB never own the pointer for a snapshot.
            snapshot.close();
            // The pointer to the snapshot is released by the database instance.
            this.db.releaseSnapshot(snapshot);
            readLock.unlock();
            timeCtx.stop();
        }
    }

    @Override
    public LocalFileMeta onSnapshotSave(final String snapshotPath) throws Exception {
        if (this.opts.isFastSnapshot()) {
            FileUtils.deleteDirectory(new File(snapshotPath));
            writeSnapshot(snapshotPath);
            return null;
        } else {
            FileUtils.forceMkdir(new File(snapshotPath));
            return backupDB(snapshotPath);
        }
    }

    @Override
    public void onSnapshotLoad(final String snapshotPath, final LocalFileMeta meta) throws Exception {
        if (this.opts.isFastSnapshot()) {
            readSnapshot(snapshotPath);
        } else {
            restoreBackup(snapshotPath, meta);
        }
    }

    public long getDatabaseVersion() {
        return this.databaseVersion.get();
    }

    public void createSstFiles(final EnumMap<SstColumnFamily, File> sstFileTable, final byte[] startKey,
                               final byte[] endKey) {
        final Timer.Context timeCtx = getTimeContext("CREATE_SST_FILE");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        final Snapshot snapshot = this.db.getSnapshot();
        try (final ReadOptions readOptions = new ReadOptions();
             final EnvOptions envOptions = new EnvOptions();
             final Options options = new Options().setMergeOperator(this.mergeOperator)) {
            readOptions.setSnapshot(snapshot);
            for (final Map.Entry<SstColumnFamily, File> entry : sstFileTable.entrySet()) {
                final SstColumnFamily sstColumnFamily = entry.getKey();
                final File sstFile = entry.getValue();
                final ColumnFamilyHandle columnFamilyHandle = findColumnFamilyHandle(sstColumnFamily);
                try (final RocksIterator it = this.db.newIterator(columnFamilyHandle, readOptions);
                     final SstFileWriter sstFileWriter = new SstFileWriter(envOptions, options)) {
                    if (startKey == null) {
                        it.seekToFirst();
                    } else {
                        it.seek(startKey);
                    }
                    sstFileWriter.open(sstFile.getAbsolutePath());
                    for (;;) {
                        if (!it.isValid()) {
                            break;
                        }
                        final byte[] key = it.key();
                        if (endKey != null && BytesUtil.compare(key, endKey) >= 0) {
                            break;
                        }
                        sstFileWriter.put(key, it.value());
                        it.next();
                    }
                    sstFileWriter.finish();
                } catch (final RocksDBException e) {
                    throw new StorageException("Fail to create sst file at path: " + sstFile, e);
                }
            }
        } finally {
            // Nothing to release, rocksDB never own the pointer for a snapshot.
            snapshot.close();
            // The pointer to the snapshot is released by the database instance.
            this.db.releaseSnapshot(snapshot);
            readLock.unlock();
            timeCtx.stop();
        }
    }

    public void ingestSstFiles(final EnumMap<SstColumnFamily, File> sstFileTable) {
        final Timer.Context timeCtx = getTimeContext("INGEST_SST_FILE");
        final Lock readLock = this.readWriteLock.readLock();
        readLock.lock();
        try {
            for (final Map.Entry<SstColumnFamily, File> entry : sstFileTable.entrySet()) {
                final SstColumnFamily sstColumnFamily = entry.getKey();
                final File sstFile = entry.getValue();
                final ColumnFamilyHandle columnFamilyHandle = findColumnFamilyHandle(sstColumnFamily);
                try (final IngestExternalFileOptions ingestOptions = new IngestExternalFileOptions()) {
                    final List<String> filePathList = Collections.singletonList(sstFile.getAbsolutePath());
                    this.db.ingestExternalFile(columnFamilyHandle, filePathList, ingestOptions);
                } catch (final RocksDBException e) {
                    throw new StorageException("Fail to ingest sst file at path: " + sstFile, e);
                }
            }
        } finally {
            readLock.unlock();
            timeCtx.stop();
        }
    }

    public void addStatisticsCollectorCallback(final StatisticsCollectorCallback callback) {
        if (this.statisticsCollector == null || this.statistics == null) {
            throw new IllegalStateException("statistics collector is not running");
        }
        this.statisticsCollector.addStatsCollectorInput(new StatsCollectorInput(this.statistics, callback));
    }

    private LocalFileMeta backupDB(final String backupDBPath) {
        final Timer.Context timeCtx = getTimeContext("BACKUP_DB");
        final Lock writeLock = this.readWriteLock.writeLock();
        writeLock.lock();
        try (final BackupableDBOptions backupOptions = createBackupDBOptions(backupDBPath);
             final BackupEngine backupEngine = BackupEngine.open(this.options.getEnv(), backupOptions)) {
            backupEngine.createNewBackup(this.db, true);
            final List<BackupInfo> backupInfoList = backupEngine.getBackupInfo();
            if (backupInfoList.isEmpty()) {
                LOG.warn("Fail to do backup at {}, empty backup info.", backupDBPath);
                return null;
            }
            // chose the backupInfo who has max backupId
            final BackupInfo backupInfo = Collections.max(backupInfoList, Comparator.comparingInt(BackupInfo::backupId));
            final RocksDBBackupInfo rocksBackupInfo = new RocksDBBackupInfo(backupInfo);
            final LocalFileMeta.Builder fb = LocalFileMeta.newBuilder();
            fb.setUserMeta(ByteString.copyFrom(this.serializer.writeObject(rocksBackupInfo)));
            LOG.info("Backup rocksDB into {} with backupInfo {}.", backupDBPath, rocksBackupInfo);
            return fb.build();
        } catch (final RocksDBException e) {
            throw new StorageException("Fail to do backup at path: " + backupDBPath, e);
        } finally {
            writeLock.unlock();
            timeCtx.stop();
        }
    }

    private void restoreBackup(final String backupDBPath, final LocalFileMeta meta) {
        final Timer.Context timeCtx = getTimeContext("RESTORE_BACKUP");
        final Lock writeLock = this.readWriteLock.writeLock();
        writeLock.lock();
        closeRocksDB();
        try (final BackupableDBOptions options = createBackupDBOptions(backupDBPath);
             final RestoreOptions restoreOptions = new RestoreOptions(false);
             final BackupEngine backupEngine = BackupEngine.open(this.options.getEnv(), options)) {
            final ByteString userMeta = meta.getUserMeta();
            final RocksDBBackupInfo rocksBackupInfo = this.serializer
                    .readObject(userMeta.toByteArray(), RocksDBBackupInfo.class);
            final String dbPath = this.opts.getDbPath();
            backupEngine.restoreDbFromBackup(
                    rocksBackupInfo.getBackupId(),
                    dbPath,
                    dbPath,
                    restoreOptions);
            LOG.info("Restored rocksDB from {} with {}.", backupDBPath, rocksBackupInfo);
            // reopen the db
            openRocksDB(this.opts);
        } catch (final RocksDBException e) {
            throw new StorageException("Fail to do restore from path: " + backupDBPath, e);
        } finally {
            writeLock.unlock();
            timeCtx.stop();
        }
    }

    private void writeSnapshot(final String snapshotPath) {
        final Timer.Context timeCtx = getTimeContext("WRITE_SNAPSHOT");
        final Lock writeLock = this.readWriteLock.writeLock();
        writeLock.lock();
        try (final Checkpoint checkpoint = Checkpoint.create(this.db)) {
            final File tempFile = new File(snapshotPath);
            if (tempFile.exists()) {
                FileUtils.deleteDirectory(tempFile);
            }
            checkpoint.createCheckpoint(snapshotPath);
        } catch (final Exception e) {
            throw new StorageException("Fail to do write snapshot at path: " + snapshotPath, e);
        } finally {
            writeLock.unlock();
            timeCtx.stop();
        }
    }

    private void readSnapshot(final String snapshotPath) {
        final Timer.Context timeCtx = getTimeContext("READ_SNAPSHOT");
        final Lock writeLock = this.readWriteLock.writeLock();
        writeLock.lock();
        try {
            final File file = new File(snapshotPath);
            if (!file.exists()) {
                LOG.error("Snapshot file [{}] not exists.", snapshotPath);
                return;
            }
            closeRocksDB();
            final String dbPath = this.opts.getDbPath();
            final File dbFile = new File(dbPath);
            if (dbFile.exists()) {
                FileUtils.deleteDirectory(dbFile);
            }
            if (!file.renameTo(new File(dbPath))) {
                throw new StorageException("Fail to rename [" + snapshotPath + "] to [" + dbPath + "].");
            }
            // reopen the db
            openRocksDB(this.opts);
        } catch (final Exception e) {
            throw new StorageException("Fail to read snapshot from path: " + snapshotPath, e);
        } finally {
            writeLock.unlock();
            timeCtx.stop();
        }
    }

    private ColumnFamilyHandle findColumnFamilyHandle(final SstColumnFamily sstColumnFamily) {
        switch (sstColumnFamily) {
            case DEFAULT:
                return this.defaultHandle;
            case SEQUENCE:
                return this.sequenceHandle;
            case LOCKING:
                return this.lockingHandle;
            case FENCING:
                return this.fencingHandle;
            default:
                throw new IllegalArgumentException("illegal sstColumnFamily: " + sstColumnFamily.name());
        }
    }

    private void openRocksDB(final RocksDBOptions opts) throws RocksDBException {
        final List<ColumnFamilyHandle> cfHandles = Lists.newArrayList();
        this.databaseVersion.incrementAndGet();
        this.db = RocksDB.open(this.options, opts.getDbPath(), this.cfDescriptors, cfHandles);
        this.defaultHandle = cfHandles.get(0);
        this.sequenceHandle = cfHandles.get(1);
        this.lockingHandle = cfHandles.get(2);
        this.fencingHandle = cfHandles.get(3);
    }

    private void closeRocksDB() {
        if (this.db != null) {
            this.db.close();
        }
    }

    // Creates the config for plain table sst format.
    private static BlockBasedTableConfig createTableConfig() {
        return new BlockBasedTableConfig() //
            .setBlockSize(4 * SizeUnit.KB) //
            .setFilter(new BloomFilter(16, false)) //
            .setCacheIndexAndFilterBlocks(true) //
            .setBlockCacheSize(512 * SizeUnit.MB) //
            .setCacheNumShardBits(8);
    }

    // Creates the rocksDB options, the user must take care
    // to close it after closing db.
    private static DBOptions createDBOptions() {
        Env env = Env.getDefault() //
            .setBackgroundThreads(ENV_BACKGROUND_FLUSH_THREADS, Env.FLUSH_POOL) //
            .setBackgroundThreads(ENV_BACKGROUND_COMPACTION_THREADS, Env.COMPACTION_POOL);

        // Turn based on https://github.com/facebook/rocksdb/wiki/RocksDB-Tuning-Guide
        return new DBOptions() //
            .setEnv(env) //
            .setCreateIfMissing(true) //
            .setCreateMissingColumnFamilies(true) //
            .setMaxOpenFiles(MAX_OPEN_FILES) //
            .setMaxBackgroundJobs(MAX_BACKGROUND_JOBS) //
            .setMaxLogFileSize(MAX_LOG_FILE_SIZE);
    }

    // Creates the column family options to control the behavior
    // of a database.
    private static ColumnFamilyOptions createColumnFamilyOptions(final MergeOperator mergeOperator) {
        BlockBasedTableConfig tableConfig = createTableConfig();
        return new ColumnFamilyOptions() //
            .setTableFormatConfig(tableConfig) //
            .setWriteBufferSize(WRITE_BUFFER_SIZE) //
            .setMaxWriteBufferNumber(MAX_WRITE_BUFFER_NUMBER) //
            .setMinWriteBufferNumberToMerge(MIN_WRITE_BUFFER_NUMBER_TO_MERGE) //
            .setCompressionType(CompressionType.LZ4_COMPRESSION) //
            .setCompactionStyle(CompactionStyle.LEVEL) //
            .optimizeLevelStyleCompaction() //
            .setLevel0FileNumCompactionTrigger(LEVEL0_FILE_NUM_COMPACTION_TRIGGER) //
            .setLevel0SlowdownWritesTrigger(LEVEL0_SLOWDOWN_WRITES_TRIGGER) //
            .setLevel0StopWritesTrigger(LEVEL0_STOP_WRITES_TRIGGER) //
            .setMaxBytesForLevelBase(MAX_BYTES_FOR_LEVEL_BASE) //
            .setTargetFileSizeBase(TARGET_FILE_SIZE_BASE) //
            .setMergeOperator(mergeOperator) //
            .setMemtablePrefixBloomSizeRatio(0.125);
    }

    // Creates the backupable db options to control the behavior of
    // a backupable database.
    private static BackupableDBOptions createBackupDBOptions(final String backupDBPath) {
        return new BackupableDBOptions(backupDBPath) //
            .setShareTableFiles(false); // don't share data between backups
    }
}
