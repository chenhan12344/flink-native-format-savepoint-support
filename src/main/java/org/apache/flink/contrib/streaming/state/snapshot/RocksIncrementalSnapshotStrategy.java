/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.contrib.streaming.state.snapshot;

import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.contrib.streaming.state.RocksDBKeyedStateBackend.RocksDbKvStateInfo;
import org.apache.flink.contrib.streaming.state.RocksDBStateUploader;
import org.apache.flink.core.fs.CloseableRegistry;
import org.apache.flink.core.memory.DataOutputView;
import org.apache.flink.core.memory.DataOutputViewStreamWrapper;
import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.state.*;
import org.apache.flink.runtime.state.metainfo.StateMetaInfoSnapshot;
import org.apache.flink.util.*;
import org.rocksdb.Checkpoint;
import org.rocksdb.RocksDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.apache.flink.contrib.streaming.state.snapshot.RocksSnapshotUtil.SST_FILE_SUFFIX;

/**
 * Snapshot strategy for {@link org.apache.flink.contrib.streaming.state.RocksDBKeyedStateBackend}
 * that is based on RocksDB's native checkpoints and creates incremental snapshots.
 *
 * @param <K> type of the backend keys.
 */
public class RocksIncrementalSnapshotStrategy<K>
        extends RocksDBSnapshotStrategyBase<
        K, RocksIncrementalSnapshotStrategy.IncrementalRocksDBSnapshotResources> {

    static {
        System.out.println("org.apache.flink.contrib.streaming.state.snapshot.RocksIncrementalSnapshotStrategy修改类已加载");
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(RocksIncrementalSnapshotStrategy.class);

    private static final String DESCRIPTION = "Asynchronous incremental RocksDB snapshot";

    /**
     * Base path of the RocksDB instance.
     */
    @Nonnull
    private final File instanceBasePath;

    /**
     * The state handle ids of all sst files materialized in snapshots for previous checkpoints.
     */
    @Nonnull
    private final UUID backendUID;

    /**
     * Stores the materialized sstable files from all snapshots that build the incremental history.
     */
    @Nonnull
    private final SortedMap<Long, Set<StateHandleID>> materializedSstFiles;

    /**
     * The identifier of the last completed checkpoint.
     */
    private long lastCompletedCheckpointId;

    /**
     * The help class used to upload state files.
     */
    private final RocksDBStateUploader stateUploader;

    /**
     * The local directory name of the current snapshot strategy.
     */
    private final String localDirectoryName;

    public RocksIncrementalSnapshotStrategy(
            @Nonnull RocksDB db,
            @Nonnull ResourceGuard rocksDBResourceGuard,
            @Nonnull TypeSerializer<K> keySerializer,
            @Nonnull LinkedHashMap<String, RocksDbKvStateInfo> kvStateInformation,
            @Nonnull KeyGroupRange keyGroupRange,
            @Nonnegative int keyGroupPrefixBytes,
            @Nonnull LocalRecoveryConfig localRecoveryConfig,
            @Nonnull CloseableRegistry cancelStreamRegistry,
            @Nonnull File instanceBasePath,
            @Nonnull UUID backendUID,
            @Nonnull SortedMap<Long, Set<StateHandleID>> materializedSstFiles,
            @Nonnull RocksDBStateUploader rocksDBStateUploader,
            long lastCompletedCheckpointId) {

        super(
                DESCRIPTION,
                db,
                rocksDBResourceGuard,
                keySerializer,
                kvStateInformation,
                keyGroupRange,
                keyGroupPrefixBytes,
                localRecoveryConfig);

        this.instanceBasePath = instanceBasePath;
        this.backendUID = backendUID;
        this.materializedSstFiles = materializedSstFiles;
        this.stateUploader = rocksDBStateUploader;
        this.lastCompletedCheckpointId = lastCompletedCheckpointId;
        this.localDirectoryName = backendUID.toString().replaceAll("[\\-]", "");
    }

    @Override
    public IncrementalRocksDBSnapshotResources syncPrepareResources(long checkpointId)
            throws Exception {

        final SnapshotDirectory snapshotDirectory = prepareLocalSnapshotDirectory(checkpointId);
        LOG.trace("Local RocksDB checkpoint goes to backup path {}.", snapshotDirectory);

        final List<StateMetaInfoSnapshot> stateMetaInfoSnapshots =
                new ArrayList<>(kvStateInformation.size());
        final Set<StateHandleID> baseSstFiles =
                snapshotMetaData(checkpointId, stateMetaInfoSnapshots);

        takeDBNativeCheckpoint(snapshotDirectory);

        return new IncrementalRocksDBSnapshotResources(
                snapshotDirectory, baseSstFiles, stateMetaInfoSnapshots);
    }

    @Override
    public SnapshotResultSupplier<KeyedStateHandle> asyncSnapshot(
            IncrementalRocksDBSnapshotResources snapshotResources,
            long checkpointId,
            long timestamp,
            @Nonnull CheckpointStreamFactory checkpointStreamFactory,
            @Nonnull CheckpointOptions checkpointOptions) {

        if (snapshotResources.stateMetaInfoSnapshots.isEmpty()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(
                        "Asynchronous RocksDB snapshot performed on empty keyed state at {}. Returning null.",
                        timestamp);
            }
            return registry -> SnapshotResult.empty();
        }

        Set<StateHandleID> baseSstFiles = checkpointOptions.getCheckpointType().isSavepoint() ?
                Collections.emptySet() : snapshotResources.baseSstFiles;

        return new RocksDBIncrementalSnapshotOperation(
                checkpointId,
                checkpointStreamFactory,
                snapshotResources.snapshotDirectory,
                baseSstFiles,
                snapshotResources.stateMetaInfoSnapshots,
                checkpointOptions);
    }

    @Override
    public void notifyCheckpointComplete(long completedCheckpointId) {
        synchronized (materializedSstFiles) {
            // FLINK-23949: materializedSstFiles.keySet().contains(completedCheckpointId) make sure
            // the notified checkpointId is not a savepoint, otherwise next checkpoint will
            // degenerate into a full checkpoint
            if (completedCheckpointId > lastCompletedCheckpointId
                    && materializedSstFiles.keySet().contains(completedCheckpointId)) {
                materializedSstFiles
                        .keySet()
                        .removeIf(checkpointId -> checkpointId < completedCheckpointId);
                lastCompletedCheckpointId = completedCheckpointId;
            }
        }
    }

    @Override
    public void notifyCheckpointAborted(long abortedCheckpointId) {
        synchronized (materializedSstFiles) {
            materializedSstFiles.keySet().remove(abortedCheckpointId);
        }
    }

    @Override
    public void close() {
        stateUploader.close();
    }

    @Nonnull
    private SnapshotDirectory prepareLocalSnapshotDirectory(long checkpointId) throws IOException {

        if (localRecoveryConfig.isLocalRecoveryEnabled()) {
            // create a "permanent" snapshot directory for local recovery.
            LocalRecoveryDirectoryProvider directoryProvider =
                    localRecoveryConfig.getLocalStateDirectoryProvider();
            File directory = directoryProvider.subtaskSpecificCheckpointDirectory(checkpointId);

            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException(
                        "Local state base directory for checkpoint "
                                + checkpointId
                                + " does not exist and could not be created: "
                                + directory);
            }

            // introduces an extra directory because RocksDB wants a non-existing directory for
            // native checkpoints.
            // append localDirectoryName here to solve directory collision problem when two stateful
            // operators chained in one task.
            File rdbSnapshotDir = new File(directory, localDirectoryName);
            if (rdbSnapshotDir.exists()) {
                FileUtils.deleteDirectory(rdbSnapshotDir);
            }

            Path path = rdbSnapshotDir.toPath();
            // create a "permanent" snapshot directory because local recovery is active.
            try {
                return SnapshotDirectory.permanent(path);
            } catch (IOException ex) {
                try {
                    FileUtils.deleteDirectory(directory);
                } catch (IOException delEx) {
                    ex = ExceptionUtils.firstOrSuppressed(delEx, ex);
                }
                throw ex;
            }
        } else {
            // create a "temporary" snapshot directory because local recovery is inactive.
            File snapshotDir = new File(instanceBasePath, "chk-" + checkpointId);
            return SnapshotDirectory.temporary(snapshotDir);
        }
    }

    private Set<StateHandleID> snapshotMetaData(
            long checkpointId, @Nonnull List<StateMetaInfoSnapshot> stateMetaInfoSnapshots) {

        final long lastCompletedCheckpoint;
        final Set<StateHandleID> baseSstFiles;

        // use the last completed checkpoint as the comparison base.
        synchronized (materializedSstFiles) {
            lastCompletedCheckpoint = lastCompletedCheckpointId;
            baseSstFiles = materializedSstFiles.get(lastCompletedCheckpoint);
        }
        LOG.trace(
                "Taking incremental snapshot for checkpoint {}. Snapshot is based on last completed checkpoint {} "
                        + "assuming the following (shared) files as base: {}.",
                checkpointId,
                lastCompletedCheckpoint,
                baseSstFiles);

        // snapshot meta data to save
        for (Map.Entry<String, RocksDbKvStateInfo> stateMetaInfoEntry :
                kvStateInformation.entrySet()) {
            stateMetaInfoSnapshots.add(stateMetaInfoEntry.getValue().metaInfo.snapshot());
        }
        return baseSstFiles;
    }

    private void takeDBNativeCheckpoint(@Nonnull SnapshotDirectory outputDirectory)
            throws Exception {
        // create hard links of living files in the output path
        try (ResourceGuard.Lease ignored = rocksDBResourceGuard.acquireResource();
             Checkpoint checkpoint = Checkpoint.create(db)) {
            checkpoint.createCheckpoint(outputDirectory.getDirectory().toString());
        } catch (Exception ex) {
            try {
                outputDirectory.cleanup();
            } catch (IOException cleanupEx) {
                ex = ExceptionUtils.firstOrSuppressed(cleanupEx, ex);
            }
            throw ex;
        }
    }

    /**
     * Encapsulates the process to perform an incremental snapshot of a RocksDBKeyedStateBackend.
     */
    private final class RocksDBIncrementalSnapshotOperation
            implements SnapshotResultSupplier<KeyedStateHandle> {

        /**
         * Id for the current checkpoint.
         */
        private final long checkpointId;

        /**
         * Stream factory that creates the output streams to DFS.
         */
        @Nonnull
        private final CheckpointStreamFactory checkpointStreamFactory;

        /**
         * The state meta data.
         */
        @Nonnull
        private final List<StateMetaInfoSnapshot> stateMetaInfoSnapshots;

        /**
         * Local directory for the RocksDB native backup.
         */
        @Nonnull
        private final SnapshotDirectory localBackupDirectory;

        /**
         * All sst files that were part of the last previously completed checkpoint.
         */
        @Nullable
        private final Set<StateHandleID> baseSstFiles;

        private final CheckpointOptions checkpointOptions;

        private RocksDBIncrementalSnapshotOperation(
                long checkpointId,
                @Nonnull CheckpointStreamFactory checkpointStreamFactory,
                @Nonnull SnapshotDirectory localBackupDirectory,
                @Nullable Set<StateHandleID> baseSstFiles,
                @Nonnull List<StateMetaInfoSnapshot> stateMetaInfoSnapshots,
                CheckpointOptions checkpointOptions) {

            this.checkpointStreamFactory = checkpointStreamFactory;
            this.baseSstFiles = baseSstFiles;
            this.checkpointId = checkpointId;
            this.localBackupDirectory = localBackupDirectory;
            this.stateMetaInfoSnapshots = stateMetaInfoSnapshots;
            this.checkpointOptions = checkpointOptions;
        }

        @Override
        public SnapshotResult<KeyedStateHandle> get(CloseableRegistry snapshotCloseableRegistry)
                throws Exception {

            boolean completed = false;

            // Handle to the meta data file
            SnapshotResult<StreamStateHandle> metaStateHandle = null;
            // Handles to new sst files since the last completed checkpoint will go here
            final Map<StateHandleID, StreamStateHandle> sstFiles = new HashMap<>();
            // Handles to the misc files in the current snapshot will go here
            final Map<StateHandleID, StreamStateHandle> miscFiles = new HashMap<>();

            Map<StateHandleID, StreamStateHandle> sharedState;
            Map<StateHandleID, StreamStateHandle> privateState;

            try {

                metaStateHandle = materializeMetaData(snapshotCloseableRegistry);

                // Sanity checks - they should never fail
                Preconditions.checkNotNull(metaStateHandle, "Metadata was not properly created.");
                Preconditions.checkNotNull(
                        metaStateHandle.getJobManagerOwnedSnapshot(),
                        "Metadata for job manager was not properly created.");

                uploadSstFiles(sstFiles, miscFiles, snapshotCloseableRegistry);

                synchronized (materializedSstFiles) {
                    // 增量键控状态在Savepoint时属于私有状态，不参与Checkpoint状态生成，因此这里创建远端状态句柄时要将元数据和RocksDB的
                    // sst文件都视作为私有状态文件，避免执行Savepoint时被认为是共享状态文件从而被去重删除
                    if (checkpointOptions.getCheckpointType().isSavepoint()) {
                        // Savepoint所有状态文件均为私有状态
                        privateState = new HashMap<>(miscFiles.size() + sstFiles.size());
                        privateState.putAll(miscFiles);
                        privateState.putAll(sstFiles);
                        // 共享状态文件置空
                        sharedState = Collections.emptyMap();

                        // 物化sst文件列表是增量CK的基础，每次执行CK时都会对比当前CK的sst文件与上一次CK的sst文件
                        // 如果某一个sst文件已经在上一次CK中存在了，那么当前CK无需对该sst文件进行持久化，仅需做一个引用
                        // 但是如果当前执行的是SP，由于SP的状态文件是独占不与其他CK共享，需要保存全量的sst文件
                        // 对于其他CK而言，SP的sst文件是不可引用的。也就是说执行SP后，并没有对之前CK物化的sst文件列表进行增量更新
                        // 那么本次SP之后，物化sst文件列表应该与上一次CK保持一致（即相当于没有CK）。
                        // 否则如果将当前SP的所有sst文件都加入到物化sst文件列表，在下一次CK执行时，由于引用的是独占的SP文件，
                        // 会造成引用异常导致CK失败，故需要将当前SP的物化sst文件列表直接引用上一次CK的物化sst文件列表。
                        Set<StateHandleID> previousStateHandles = materializedSstFiles.getOrDefault(checkpointId - 1, Collections.emptySet());
                        materializedSstFiles.put(checkpointId, previousStateHandles);
                    } else {
                        // Checkpoint时保持原样
                        privateState = miscFiles;
                        sharedState = sstFiles;
                        materializedSstFiles.put(checkpointId, sstFiles.keySet());
                    }
                }


                final IncrementalRemoteKeyedStateHandle jmIncrementalKeyedStateHandle =
                        new IncrementalRemoteKeyedStateHandle(
                                backendUID,
                                keyGroupRange,
                                checkpointId,
                                sharedState,
                                privateState,
                                metaStateHandle.getJobManagerOwnedSnapshot());

                final DirectoryStateHandle directoryStateHandle =
                        localBackupDirectory.completeSnapshotAndGetHandle();
                final SnapshotResult<KeyedStateHandle> snapshotResult;
                if (directoryStateHandle != null
                        && metaStateHandle.getTaskLocalSnapshot() != null) {

                    IncrementalLocalKeyedStateHandle localDirKeyedStateHandle =
                            new IncrementalLocalKeyedStateHandle(
                                    backendUID,
                                    checkpointId,
                                    directoryStateHandle,
                                    keyGroupRange,
                                    metaStateHandle.getTaskLocalSnapshot(),
                                    sstFiles.keySet());

                    snapshotResult =
                            SnapshotResult.withLocalState(
                                    jmIncrementalKeyedStateHandle, localDirKeyedStateHandle);
                } else {
                    snapshotResult = SnapshotResult.of(jmIncrementalKeyedStateHandle);
                }

                completed = true;

                return snapshotResult;
            } finally {
                if (!completed) {
                    final List<StateObject> statesToDiscard =
                            new ArrayList<>(1 + miscFiles.size() + sstFiles.size());
                    statesToDiscard.add(metaStateHandle);
                    statesToDiscard.addAll(miscFiles.values());
                    statesToDiscard.addAll(sstFiles.values());
                    cleanupIncompleteSnapshot(statesToDiscard);
                }
            }
        }

        private void cleanupIncompleteSnapshot(@Nonnull List<StateObject> statesToDiscard) {

            try {
                StateUtil.bestEffortDiscardAllStateObjects(statesToDiscard);
            } catch (Exception e) {
                LOG.warn("Could not properly discard states.", e);
            }

            if (localBackupDirectory.isSnapshotCompleted()) {
                try {
                    DirectoryStateHandle directoryStateHandle =
                            localBackupDirectory.completeSnapshotAndGetHandle();
                    if (directoryStateHandle != null) {
                        directoryStateHandle.discardState();
                    }
                } catch (Exception e) {
                    LOG.warn("Could not properly discard local state.", e);
                }
            }
        }

        private void uploadSstFiles(
                @Nonnull Map<StateHandleID, StreamStateHandle> sstFiles,
                @Nonnull Map<StateHandleID, StreamStateHandle> miscFiles,
                @Nonnull CloseableRegistry snapshotCloseableRegistry)
                throws Exception {

            // write state data
            Preconditions.checkState(localBackupDirectory.exists());

            Map<StateHandleID, Path> sstFilePaths = new HashMap<>();
            Map<StateHandleID, Path> miscFilePaths = new HashMap<>();

            Path[] files = localBackupDirectory.listDirectory();
            if (files != null) {
                createUploadFilePaths(files, sstFiles, sstFilePaths, miscFilePaths);

                // Savepoint状态文件为独占模式，不与Checkpoint共享
                CheckpointedStateScope scope = checkpointOptions.getCheckpointType().isSavepoint() ?
                        CheckpointedStateScope.EXCLUSIVE : CheckpointedStateScope.SHARED;

                sstFiles.putAll(
                        stateUploader.uploadFilesToCheckpointFs(
                                sstFilePaths, checkpointStreamFactory, snapshotCloseableRegistry, scope));
                miscFiles.putAll(
                        stateUploader.uploadFilesToCheckpointFs(
                                miscFilePaths, checkpointStreamFactory, snapshotCloseableRegistry, scope));
            }
        }

        private void createUploadFilePaths(
                Path[] files,
                Map<StateHandleID, StreamStateHandle> sstFiles,
                Map<StateHandleID, Path> sstFilePaths,
                Map<StateHandleID, Path> miscFilePaths) {
            for (Path filePath : files) {
                final String fileName = filePath.getFileName().toString();
                final StateHandleID stateHandleID = new StateHandleID(fileName);

                if (fileName.endsWith(SST_FILE_SUFFIX)) {
                    final boolean existsAlready =
                            baseSstFiles != null && baseSstFiles.contains(stateHandleID);

                    if (existsAlready) {
                        // we introduce a placeholder state handle, that is replaced with the
                        // original from the shared state registry (created from a previous
                        // checkpoint)
                        sstFiles.put(stateHandleID, new PlaceholderStreamStateHandle());
                    } else {
                        sstFilePaths.put(stateHandleID, filePath);
                    }
                } else {
                    miscFilePaths.put(stateHandleID, filePath);
                }
            }
        }

        @Nonnull
        private SnapshotResult<StreamStateHandle> materializeMetaData(
                @Nonnull CloseableRegistry snapshotCloseableRegistry) throws Exception {

            CheckpointStreamWithResultProvider streamWithResultProvider =
                    localRecoveryConfig.isLocalRecoveryEnabled()
                            ? CheckpointStreamWithResultProvider.createDuplicatingStream(
                            checkpointId,
                            CheckpointedStateScope.EXCLUSIVE,
                            checkpointStreamFactory,
                            localRecoveryConfig.getLocalStateDirectoryProvider())
                            : CheckpointStreamWithResultProvider.createSimpleStream(
                            CheckpointedStateScope.EXCLUSIVE, checkpointStreamFactory);

            snapshotCloseableRegistry.registerCloseable(streamWithResultProvider);

            try {
                // no need for compression scheme support because sst-files are already compressed
                KeyedBackendSerializationProxy<K> serializationProxy =
                        new KeyedBackendSerializationProxy<>(
                                keySerializer, stateMetaInfoSnapshots, false);

                DataOutputView out =
                        new DataOutputViewStreamWrapper(
                                streamWithResultProvider.getCheckpointOutputStream());

                serializationProxy.write(out);

                if (snapshotCloseableRegistry.unregisterCloseable(streamWithResultProvider)) {
                    SnapshotResult<StreamStateHandle> result =
                            streamWithResultProvider.closeAndFinalizeCheckpointStreamResult();
                    streamWithResultProvider = null;
                    return result;
                } else {
                    throw new IOException("Stream already closed and cannot return a handle.");
                }
            } finally {
                if (streamWithResultProvider != null) {
                    if (snapshotCloseableRegistry.unregisterCloseable(streamWithResultProvider)) {
                        IOUtils.closeQuietly(streamWithResultProvider);
                    }
                }
            }
        }
    }

    static class IncrementalRocksDBSnapshotResources implements SnapshotResources {
        @Nonnull
        private final SnapshotDirectory snapshotDirectory;
        @Nonnull
        private final Set<StateHandleID> baseSstFiles;
        @Nonnull
        private final List<StateMetaInfoSnapshot> stateMetaInfoSnapshots;

        public IncrementalRocksDBSnapshotResources(
                SnapshotDirectory snapshotDirectory,
                Set<StateHandleID> baseSstFiles,
                List<StateMetaInfoSnapshot> stateMetaInfoSnapshots) {
            this.snapshotDirectory = snapshotDirectory;
            this.baseSstFiles = baseSstFiles;
            this.stateMetaInfoSnapshots = stateMetaInfoSnapshots;
        }

        @Override
        public void release() {
            try {
                if (snapshotDirectory.exists()) {
                    LOG.trace(
                            "Running cleanup for local RocksDB backup directory {}.",
                            snapshotDirectory);
                    boolean cleanupOk = snapshotDirectory.cleanup();

                    if (!cleanupOk) {
                        LOG.debug("Could not properly cleanup local RocksDB backup directory.");
                    }
                }
            } catch (IOException e) {
                LOG.warn("Could not properly cleanup local RocksDB backup directory.", e);
            }
        }
    }
}
