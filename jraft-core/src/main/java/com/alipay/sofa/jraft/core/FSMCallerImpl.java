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
package com.alipay.sofa.jraft.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.FSMCaller;
import com.alipay.sofa.jraft.StateMachine;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.closure.ClosureQueue;
import com.alipay.sofa.jraft.closure.LoadSnapshotClosure;
import com.alipay.sofa.jraft.closure.SaveSnapshotClosure;
import com.alipay.sofa.jraft.closure.TaskClosure;
import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.conf.ConfigurationEntry;
import com.alipay.sofa.jraft.entity.EnumOutter;
import com.alipay.sofa.jraft.entity.LeaderChangeContext;
import com.alipay.sofa.jraft.entity.LogId;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.entity.RaftOutter;
import com.alipay.sofa.jraft.error.RaftError;
import com.alipay.sofa.jraft.error.RaftException;
import com.alipay.sofa.jraft.option.FSMCallerOptions;
import com.alipay.sofa.jraft.storage.LogManager;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotReader;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotWriter;
import com.alipay.sofa.jraft.util.LogExceptionHandler;
import com.alipay.sofa.jraft.util.NamedThreadFactory;
import com.alipay.sofa.jraft.util.OnlyForTest;
import com.alipay.sofa.jraft.util.Requires;
import com.alipay.sofa.jraft.util.Utils;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventTranslator;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;

/**
 * The finite state machine caller implementation.
 *
 * @author boyan (boyan@alibaba-inc.com)
 *
 * 2018-Apr-03 11:12:14 AM
 */
public class FSMCallerImpl implements FSMCaller {

    private static final Logger LOG = LoggerFactory.getLogger(FSMCallerImpl.class);

    /**
     * Task type
     * @author boyan (boyan@alibaba-inc.com)
     *
     * 2018-Apr-03 11:12:25 AM
     */
    private enum TaskType {
        IDLE, //
        COMMITTED, //
        SNAPSHOT_SAVE, //
        SNAPSHOT_LOAD, //
        LEADER_STOP, //
        LEADER_START, //
        START_FOLLOWING, //
        STOP_FOLLOWING, //
        SHUTDOWN, //
        FLUSH, //
        ERROR;

        private String metricName;

        public String metricName() {
            if (this.metricName == null) {
                this.metricName = "fsm-" + this.name().toLowerCase().replaceAll("_", "-");
            }
            return this.metricName;
        }
    }

    /**
     * Apply task for disruptor.
     *
     * @author boyan (boyan@alibaba-inc.com)
     *
     * 2018-Apr-03 11:12:35 AM
     */
    private static class ApplyTask {
        TaskType            type;
        // union fields
        long                committedIndex;
        long                term;
        Status              status;
        LeaderChangeContext leaderChangeCtx;
        Closure             done;
        CountDownLatch      shutdownLatch;

        public void reset() {
            this.type = null;
            this.committedIndex = 0;
            this.term = 0;
            this.status = null;
            this.leaderChangeCtx = null;
            this.done = null;
            this.shutdownLatch = null;
        }
    }

    private static class ApplyTaskFactory implements EventFactory<ApplyTask> {

        @Override
        public ApplyTask newInstance() {
            return new ApplyTask();
        }

    }

    private class ApplyTaskHandler implements EventHandler<ApplyTask> {
        /* max committed index in current batch,reset to -1 every batch */
        private long maxCommittedIndex = -1;

        @Override
        public void onEvent(ApplyTask event, long sequence, boolean endOfBatch) throws Exception {
            maxCommittedIndex = runApplyTask(event, maxCommittedIndex, endOfBatch);
        }
    }

    private LogManager                                              logManager;
    private StateMachine                                            fsm;
    private ClosureQueue                                            closureQueue;
    private final AtomicLong                                        lastAppliedIndex;
    private long                                                    lastAppliedTerm;
    private Closure                                                 afterShutdown;
    private NodeImpl                                                node;
    private volatile TaskType                                       currTask;
    private final AtomicLong                                        applyingIndex;
    private RaftException                                           error;

    /**
     * apply的任务队列，在onCommit时入队，出队时response给client并且apply到状态机
     */
    private Disruptor<ApplyTask>                                    disruptor;
    private RingBuffer<ApplyTask>                                   taskQueue;
    private volatile CountDownLatch                                 shutdownLatch;
    private NodeMetrics                                             nodeMetrics;
    private final CopyOnWriteArrayList<LastAppliedLogIndexListener> lastAppliedLogIndexListeners = new CopyOnWriteArrayList<>();

    public FSMCallerImpl() {
        super();
        this.currTask = TaskType.IDLE;
        this.lastAppliedIndex = new AtomicLong(0);
        this.applyingIndex = new AtomicLong(0);
    }

    @Override
    public boolean init(FSMCallerOptions opts) {
        this.logManager = opts.getLogManager();
        this.fsm = opts.getFsm();
        this.closureQueue = opts.getClosureQueue();
        this.afterShutdown = opts.getAfterShutdown();
        this.node = opts.getNode();
        this.nodeMetrics = this.node.getNodeMetrics();
        this.lastAppliedIndex.set(opts.getBootstrapId().getIndex());
        this.notifyLastAppliedIndexUpdated(lastAppliedIndex.get());
        this.lastAppliedTerm = opts.getBootstrapId().getTerm();
        this.disruptor = new Disruptor<>(new ApplyTaskFactory(), opts.getDisruptorBufferSize(),
                new NamedThreadFactory("Jraft-FSMCaller-disruptor-", true));
        this.disruptor.handleEventsWith(new ApplyTaskHandler());
        this.disruptor.setDefaultExceptionHandler(new LogExceptionHandler<Object>(this.getClass().getSimpleName()));
        this.disruptor.start();
        this.taskQueue = this.disruptor.getRingBuffer();
        this.error = new RaftException(EnumOutter.ErrorType.ERROR_TYPE_NONE);
        LOG.info("Starts FSMCaller successfully.");
        return true;
    }

    @Override
    public synchronized void shutdown() {
        if (this.shutdownLatch != null) {
            return;
        }
        LOG.info("Shutting down FSMCaller...");

        if (this.taskQueue != null) {
            final CountDownLatch latch = new CountDownLatch(1);
            this.enqueueTask((task, sequence) -> {
                task.reset();
                task.type = TaskType.SHUTDOWN;
                task.shutdownLatch = latch;
            });
            this.shutdownLatch = latch;
        }
        this.doShutdown();
    }

    @Override
    public void addLastAppliedLogIndexListener(LastAppliedLogIndexListener listener) {
        this.lastAppliedLogIndexListeners.add(listener);
    }

    private boolean enqueueTask(EventTranslator<ApplyTask> tpl) {
        if (this.shutdownLatch != null) {
            //Shutting down
            LOG.warn("FSMCaller is stopped, can not apply new task.");
            return false;
        }
        taskQueue.publishEvent(tpl);
        return true;
    }

    @Override
    public boolean onCommitted(final long committedIndex) {
        return enqueueTask((task, sequence) -> {
            task.type = TaskType.COMMITTED;
            task.committedIndex = committedIndex;
        });
    }

    /**
     * Flush all events in disruptor.
     */
    @OnlyForTest
    void flush() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        enqueueTask((task, sequence) -> {
            task.type = TaskType.FLUSH;
            task.shutdownLatch = latch;
        });
        latch.await();
    }

    @Override
    public boolean onSnapshotLoad(LoadSnapshotClosure done) {
        return enqueueTask((task, sequence) -> {
            task.type = TaskType.SNAPSHOT_LOAD;
            task.done = done;
        });
    }

    @Override
    public boolean onSnapshotSave(final SaveSnapshotClosure done) {
        return enqueueTask((task, sequence) -> {
            task.type = TaskType.SNAPSHOT_SAVE;
            task.done = done;
        });
    }

    @Override
    public boolean onLeaderStop(final Status status) {
        return this.enqueueTask((task, sequence) -> {
            task.type = TaskType.LEADER_STOP;
            task.status = new Status(status);
        });
    }

    @Override
    public boolean onLeaderStart(final long term) {
        return this.enqueueTask((task, sequence) -> {
            task.type = TaskType.LEADER_START;
            task.term = term;
        });
    }

    @Override
    public boolean onStartFollowing(LeaderChangeContext ctx) {
        return this.enqueueTask((task, sequence) -> {
            task.type = TaskType.START_FOLLOWING;
            task.leaderChangeCtx = new LeaderChangeContext(ctx.getLeaderId(), ctx.getTerm(), ctx.getStatus());
        });
    }

    @Override
    public boolean onStopFollowing(LeaderChangeContext ctx) {
        return this.enqueueTask((task, sequence) -> {
            task.type = TaskType.STOP_FOLLOWING;
            task.leaderChangeCtx = new LeaderChangeContext(ctx.getLeaderId(), ctx.getTerm(), ctx.getStatus());
        });
    }

    /**
     * Closure runs with an error.
     * @author boyan (boyan@alibaba-inc.com)
     *
     * 2018-Apr-04 2:20:31 PM
     */
    public class OnErrorClosure implements Closure {
        private RaftException error;

        public OnErrorClosure(RaftException error) {
            super();
            this.error = error;
        }

        public RaftException getError() {
            return this.error;
        }

        public void setError(RaftException error) {
            this.error = error;
        }

        @Override
        public void run(Status st) {
        }

    }

    @Override
    public boolean onError(RaftException error) {
        final OnErrorClosure c = new OnErrorClosure(error);
        return this.enqueueTask((task, sequence) -> {
            task.type = TaskType.ERROR;
            task.done = c;
        });
    }

    @Override
    public long getLastAppliedIndex() {
        return this.lastAppliedIndex.get();
    }

    @Override
    public synchronized void join() throws InterruptedException {
        if (this.shutdownLatch != null) {
            this.shutdownLatch.await();
            this.disruptor.shutdown();
            this.shutdownLatch = null;
        }
    }

    @SuppressWarnings("ConstantConditions")
    private long runApplyTask(ApplyTask task, long maxCommittedIndex, boolean endOfBatch) {
        CountDownLatch shutdown = null;
        if (task.type == TaskType.COMMITTED) {
            if (task.committedIndex > maxCommittedIndex) {
                maxCommittedIndex = task.committedIndex;
            }
        } else {
            if (maxCommittedIndex >= 0) {
                this.currTask = TaskType.COMMITTED;
                this.doCommitted(maxCommittedIndex);
                maxCommittedIndex = -1L; //reset maxCommittedIndex
            }
            final long startMs = Utils.monotonicMs();
            try {
                switch (task.type) {
                    case COMMITTED:
                        Requires.requireTrue(false, "Impossible");
                        break;
                    case SNAPSHOT_SAVE:
                        this.currTask = TaskType.SNAPSHOT_SAVE;
                        if (this.passByStatus(task.done)) {
                            this.doSnapshotSave((SaveSnapshotClosure) task.done);
                        }
                        break;
                    case SNAPSHOT_LOAD:
                        this.currTask = TaskType.SNAPSHOT_LOAD;
                        if (this.passByStatus(task.done)) {
                            this.doSnapshotLoad((LoadSnapshotClosure) task.done);
                        }
                        break;
                    case LEADER_STOP:
                        this.currTask = TaskType.LEADER_STOP;
                        this.doLeaderStop(task.status);
                        break;
                    case LEADER_START:
                        this.currTask = TaskType.LEADER_START;
                        this.doLeaderStart(task.term);
                        break;
                    case START_FOLLOWING:
                        this.currTask = TaskType.START_FOLLOWING;
                        this.doStartFollowing(task.leaderChangeCtx);
                        break;
                    case STOP_FOLLOWING:
                        this.currTask = TaskType.STOP_FOLLOWING;
                        this.doStopFollowing(task.leaderChangeCtx);
                        break;
                    case ERROR:
                        this.currTask = TaskType.ERROR;
                        this.doOnError((OnErrorClosure) task.done);
                        break;
                    case IDLE:
                        Requires.requireTrue(false, "Can't reach here");
                        break;
                    case SHUTDOWN:
                        this.currTask = TaskType.SHUTDOWN;
                        shutdown = task.shutdownLatch;
                        break;
                    case FLUSH:
                        this.currTask = TaskType.FLUSH;
                        shutdown = task.shutdownLatch;
                        break;
                }
            } finally {
                nodeMetrics.recordLatency(task.type.metricName(), Utils.monotonicMs() - startMs);
            }
        }
        try {
            if (endOfBatch && maxCommittedIndex >= 0) {
                this.currTask = TaskType.COMMITTED;
                this.doCommitted(maxCommittedIndex);// 这里一般情况下，maxCommittedIndex就是最新的lastCommittedIndex
                maxCommittedIndex = -1L; //reset maxCommittedIndex
            }
            this.currTask = TaskType.IDLE;
            return maxCommittedIndex;
        } finally {
            if (shutdown != null) {
                shutdown.countDown();
            }
        }
    }

    private void doShutdown() {
        if (node != null) {
            node = null;
        }
        if (this.fsm != null) {
            this.fsm.onShutdown();
        }
        if (afterShutdown != null) {
            afterShutdown.run(Status.OK());
            this.afterShutdown = null;
        }
    }

    private void notifyLastAppliedIndexUpdated(long lastAppliedIndex) {
        for (final LastAppliedLogIndexListener listener : this.lastAppliedLogIndexListeners) {
            listener.onApplied(lastAppliedIndex);
        }
    }

    private void doCommitted(long committedIndex) {
        if (!this.error.getStatus().isOk()) {
            return;
        }
        final long lastAppliedIndex = this.lastAppliedIndex.get();
        // We can tolerate the disorder of committed_index
        if (lastAppliedIndex >= committedIndex) {
            return;
        }
        final long startMs = Utils.monotonicMs();
        try {
            final List<Closure> closures = new ArrayList<>();
            final long firstClosureIndex = this.closureQueue.popClosureUntil(committedIndex, closures);

            // calls TaskClosure#onCommitted if necessary
            onTaskCommitted(closures);

            Requires.requireTrue(firstClosureIndex >= 0, "Invalid firstClosureIndex");
            final IteratorImpl iterImpl = new IteratorImpl(fsm, this.logManager, closures, firstClosureIndex,
                lastAppliedIndex, committedIndex, this.applyingIndex);
            while (iterImpl.isGood()) {
                if (iterImpl.entry().getType() != EnumOutter.EntryType.ENTRY_TYPE_DATA) {
                    if (iterImpl.entry().getType() == EnumOutter.EntryType.ENTRY_TYPE_CONFIGURATION) {
                        if (iterImpl.entry().getOldPeers() != null && !iterImpl.entry().getOldPeers().isEmpty()) {
                            //Joint stage is not supposed to be noticeable by end users.
                            fsm.onConfigurationCommitted(new Configuration(iterImpl.entry().getPeers()));
                        }
                    }
                    if (iterImpl.done() != null) {
                        // For other entries, we have nothing to do besides flush the
                        // pending tasks and run this closure to notify the caller that the
                        // entries before this one were successfully committed and applied.
                        iterImpl.done().run(Status.OK());
                    }
                    iterImpl.next();
                    continue;
                }

                // apply data task to user state machine
                // 这里会拿到上次apply成功的，到最新lastCommittedIndex之间的raft log
                // 获取raft log，既可能从memory中获取，也可能从disk中获取
                this.doApplyTasks(iterImpl);
            }

            if (iterImpl.hasError()) {
                setError(iterImpl.getError());
                iterImpl.runTheRestClosureWithError();
            }

            // 如果doApplyTasks顺利，这里就更新appliedIndex相关的累计值
            // 如果失败，抛出异常的话，就不会到达这里！下一次commit仍然可以再次提交
            final long lastIndex = iterImpl.getIndex() - 1;
            final long lastTerm = this.logManager.getTerm(lastIndex);
            final LogId lastAppliedId = new LogId(lastIndex, lastTerm);
            this.lastAppliedIndex.set(committedIndex);
            this.lastAppliedTerm = lastTerm;
            this.logManager.setAppliedId(lastAppliedId);
            this.notifyLastAppliedIndexUpdated(committedIndex);
        } finally {
            nodeMetrics.recordLatency("fsm-commit", Utils.monotonicMs() - startMs);
        }
    }

    private void onTaskCommitted(final List<Closure> closures) {
        final int closureListSize = closures.size();
        for (int i = 0; i < closureListSize; i++) {
            final Closure done = closures.get(i);
            if (done != null && done instanceof TaskClosure) {
                ((TaskClosure) done).onCommitted();
            }
        }
    }

    private void doApplyTasks(IteratorImpl iterImpl) {
        final IteratorWrapper iter = new IteratorWrapper(iterImpl);
        final long startApplyMs = Utils.monotonicMs();
        final long startIndex = iter.getIndex();
        try {
            fsm.onApply(iter);
        } finally {
            nodeMetrics.recordLatency("fsm-apply-tasks", Utils.monotonicMs() - startApplyMs);
            nodeMetrics.recordSize("fsm-apply-tasks-count", iter.getIndex() - startIndex);
        }
        if (iter.hasNext()) {
            LOG.error("Iterator is still valid, did you return before iterator reached the end?");
        }
        // Try move to next in case that we pass the same log twice.
        iter.next();
    }

    private void doSnapshotSave(SaveSnapshotClosure done) {
        Requires.requireNonNull(done, "SaveSnapshotClosure is null");

        // 根据lastAppliedIndex确定本次snapshot的元数据：index、term、config
        // 元数据会在业务写入snapshot成功后，再写入文件
        final long lastAppliedIndex = this.lastAppliedIndex.get();
        final RaftOutter.SnapshotMeta.Builder metaBuilder = RaftOutter.SnapshotMeta.newBuilder().setLastIncludedIndex(lastAppliedIndex)
                .setLastIncludedTerm(this.lastAppliedTerm);
        final ConfigurationEntry confEntry = logManager.getConfiguration(lastAppliedIndex);
        if (confEntry == null || confEntry.isEmpty()) {
            LOG.error("Empty conf entry for lastAppliedIndex={}", lastAppliedIndex);
            Utils.runClosureInThread(done,
                new Status(RaftError.EINVAL, "Empty conf entry for lastAppliedIndex=%s", lastAppliedIndex));
            return;
        }
        for (final PeerId peer : confEntry.getConf()) {
            metaBuilder.addPeers(peer.toString());
        }
        if (confEntry.getOldConf() != null) {
            for (final PeerId peer : confEntry.getOldConf()) {
                metaBuilder.addOldPeers(peer.toString());
            }
        }
        final SnapshotWriter writer = done.start(metaBuilder.build());
        if (writer == null) {
            done.run(new Status(RaftError.EINVAL, "snapshot_storage create SnapshotWriter failed"));
            return;
        }
        this.fsm.onSnapshotSave(writer, done);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("StateMachine [");
        switch (currTask) {
            case IDLE:
                sb.append("Idle");
                break;
            case COMMITTED:
                sb.append("Applying logIndex=").append(this.applyingIndex);
                break;
            case SNAPSHOT_SAVE:
                sb.append("Saving snapshot");
                break;
            case SNAPSHOT_LOAD:
                sb.append("Loading snapshot");
                break;
            case ERROR:
                sb.append("Notifying error");
                break;
            case LEADER_STOP:
                sb.append("Notifying leader stop");
                break;
            case LEADER_START:
                sb.append("Notifying leader start");
                break;
            case START_FOLLOWING:
                sb.append("Notifying start following");
                break;
            case STOP_FOLLOWING:
                sb.append("Notifying stop following");
                break;
            case SHUTDOWN:
                sb.append("Shutting down");
                break;
            default:
                break;
        }
        return sb.append("]").toString();
    }

    private void doSnapshotLoad(LoadSnapshotClosure done) {
        Requires.requireNonNull(done, "LoadSnapshotClosure is null");
        final SnapshotReader reader = done.start();
        if (reader == null) {
            done.run(new Status(RaftError.EINVAL, "open SnapshotReader failed"));
            return;
        }
        final RaftOutter.SnapshotMeta meta = reader.load();
        if (meta == null) {
            done.run(new Status(RaftError.EINVAL, "SnapshotReader load meta failed"));
            if (reader.getRaftError() == RaftError.EIO) {
                final RaftException err = new RaftException(EnumOutter.ErrorType.ERROR_TYPE_SNAPSHOT, RaftError.EIO,
                        "Fail to load snapshot meta");
                setError(err);
            }
            return;
        }
        final LogId lastAppliedId = new LogId(lastAppliedIndex.get(), lastAppliedTerm);
        final LogId snapshotId = new LogId(meta.getLastIncludedIndex(), meta.getLastIncludedTerm());
        if (lastAppliedId.compareTo(snapshotId) > 0) {
            done.run(new Status(RaftError.ESTALE,
                "Loading a stale snapshot last_applied_index=%d last_applied_term=%d snapshot_index=%d snapshot_term=%d",
                lastAppliedId.getIndex(), lastAppliedId.getTerm(), snapshotId.getIndex(), snapshotId.getTerm()));
            return;
        }
        if (!this.fsm.onSnapshotLoad(reader)) {
            done.run(new Status(-1, "StateMachine onSnapshotLoad failed"));
            final RaftException e = new RaftException(EnumOutter.ErrorType.ERROR_TYPE_STATE_MACHINE, RaftError.ESTATEMACHINE,
                    "StateMachine onSnapshotLoad failed");
            setError(e);
            return;
        }
        if (meta.getOldPeersCount() == 0) {
            // Joint stage is not supposed to be noticeable by end users.
            final Configuration conf = new Configuration();
            for (int i = 0; i < meta.getPeersCount(); i++) {
                final PeerId peer = new PeerId();
                Requires.requireTrue(peer.parse(meta.getPeers(i)), "Parse peer failed");
                conf.addPeer(peer);
            }
            fsm.onConfigurationCommitted(conf);
        }
        lastAppliedIndex.set(meta.getLastIncludedIndex());
        lastAppliedTerm = meta.getLastIncludedTerm();
        done.run(Status.OK());
    }

    private void doOnError(OnErrorClosure done) {
        setError(done.getError());
    }

    private void doLeaderStop(Status status) {
        this.fsm.onLeaderStop(status);
    }

    private void doLeaderStart(long term) {
        this.fsm.onLeaderStart(term);
    }

    private void doStartFollowing(LeaderChangeContext ctx) {
        this.fsm.onStartFollowing(ctx);
    }

    private void doStopFollowing(LeaderChangeContext ctx) {
        this.fsm.onStopFollowing(ctx);
    }

    private void setError(RaftException e) {
        if (this.error.getType() != EnumOutter.ErrorType.ERROR_TYPE_NONE) {
            //already report
            return;
        }
        this.error = e;
        if (this.fsm != null) {
            this.fsm.onError(this.error);
        }
        if (this.node != null) {
            this.node.onError(this.error);
        }
    }

    @OnlyForTest
    RaftException getError() {
        return this.error;
    }

    private boolean passByStatus(Closure done) {
        if (!this.error.getStatus().isOk()) {
            if (done != null) {
                done.run(new Status(RaftError.EINVAL, "FSMCaller is in bad status=`%s`", this.error.getStatus()));
                return false;
            }
        }
        return true;
    }
}
