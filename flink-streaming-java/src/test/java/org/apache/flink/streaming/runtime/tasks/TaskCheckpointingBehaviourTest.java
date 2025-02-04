/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.tasks;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.core.fs.CloseableRegistry;
import org.apache.flink.core.testutils.OneShotLatch;
import org.apache.flink.runtime.blob.VoidPermanentBlobService;
import org.apache.flink.runtime.broadcast.BroadcastVariableManager;
import org.apache.flink.runtime.checkpoint.CheckpointException;
import org.apache.flink.runtime.checkpoint.CheckpointMetaData;
import org.apache.flink.runtime.checkpoint.CheckpointMetrics;
import org.apache.flink.runtime.checkpoint.CheckpointMetricsBuilder;
import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.checkpoint.TaskStateSnapshot;
import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.deployment.InputGateDeploymentDescriptor;
import org.apache.flink.runtime.deployment.ResultPartitionDeploymentDescriptor;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.execution.ExecutionState;
import org.apache.flink.runtime.execution.librarycache.TestingClassLoaderLease;
import org.apache.flink.runtime.executiongraph.ExecutionAttemptID;
import org.apache.flink.runtime.executiongraph.JobInformation;
import org.apache.flink.runtime.executiongraph.TaskInformation;
import org.apache.flink.runtime.externalresource.ExternalResourceInfoProvider;
import org.apache.flink.runtime.filecache.FileCache;
import org.apache.flink.runtime.io.disk.iomanager.IOManager;
import org.apache.flink.runtime.io.network.NettyShuffleEnvironmentBuilder;
import org.apache.flink.runtime.io.network.TaskEventDispatcher;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.jobgraph.tasks.InputSplitProvider;
import org.apache.flink.runtime.memory.MemoryManager;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.apache.flink.runtime.query.KvStateRegistry;
import org.apache.flink.runtime.shuffle.ShuffleEnvironment;
import org.apache.flink.runtime.state.CheckpointStateOutputStream;
import org.apache.flink.runtime.state.CheckpointStreamFactory;
import org.apache.flink.runtime.state.DefaultOperatorStateBackend;
import org.apache.flink.runtime.state.DefaultOperatorStateBackendBuilder;
import org.apache.flink.runtime.state.OperatorStateBackend;
import org.apache.flink.runtime.state.OperatorStateCheckpointOutputStream;
import org.apache.flink.runtime.state.OperatorStateHandle;
import org.apache.flink.runtime.state.SnapshotExecutionType;
import org.apache.flink.runtime.state.SnapshotResources;
import org.apache.flink.runtime.state.SnapshotResult;
import org.apache.flink.runtime.state.SnapshotStrategy;
import org.apache.flink.runtime.state.SnapshotStrategyRunner;
import org.apache.flink.runtime.state.StateBackend;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.runtime.state.StreamStateHandle;
import org.apache.flink.runtime.state.TestTaskStateManager;
import org.apache.flink.runtime.state.memory.MemoryStateBackend;
import org.apache.flink.runtime.state.testutils.BackendForTestStream;
import org.apache.flink.runtime.taskexecutor.KvStateService;
import org.apache.flink.runtime.taskexecutor.PartitionProducerStateChecker;
import org.apache.flink.runtime.taskexecutor.TestGlobalAggregateManager;
import org.apache.flink.runtime.taskmanager.CheckpointResponder;
import org.apache.flink.runtime.taskmanager.NoOpTaskOperatorEventGateway;
import org.apache.flink.runtime.taskmanager.Task;
import org.apache.flink.runtime.taskmanager.TaskManagerActions;
import org.apache.flink.runtime.util.EnvironmentInformation;
import org.apache.flink.runtime.util.TestingTaskManagerRuntimeInfo;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.operators.StreamFilter;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.runtime.tasks.mailbox.MailboxDefaultAction;
import org.apache.flink.util.SerializedValue;
import org.apache.flink.util.TestLogger;
import org.apache.flink.util.concurrent.Executors;

import org.junit.Assert;
import org.junit.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.RunnableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

/**
 * This test checks that task checkpoints that block and do not react to thread interrupts. It also
 * checks correct working of different policies how tasks deal with checkpoint failures (fail task,
 * decline checkpoint and continue).
 */
public class TaskCheckpointingBehaviourTest extends TestLogger {

    private static final OneShotLatch IN_CHECKPOINT_LATCH = new OneShotLatch();

    @Test
    public void testDeclineOnCheckpointErrorInSyncPart() throws Exception {
        TestDeclinedCheckpointResponder checkpointResponder = new TestDeclinedCheckpointResponder();
        Task task =
                createTask(
                        new FilterOperator(),
                        new SyncFailureInducingStateBackend(),
                        checkpointResponder);
        runTaskExpectFailure(task);
    }

    @Test
    public void testDeclineOnCheckpointErrorInAsyncPart() throws Exception {
        TestDeclinedCheckpointResponder checkpointResponder = new TestDeclinedCheckpointResponder();
        Task task =
                createTask(
                        new FilterOperator(),
                        new AsyncFailureInducingStateBackend(),
                        checkpointResponder);
        runTaskExpectCheckpointDeclined(task, checkpointResponder);
    }

    @Test
    public void testBlockingNonInterruptibleCheckpoint() throws Exception {

        StateBackend lockingStateBackend = new BackendForTestStream(LockingOutputStream::new);

        Task task =
                createTask(
                        new TestOperator(), lockingStateBackend, mock(CheckpointResponder.class));

        // start the task and wait until it is in "restore"
        task.startTaskThread();
        IN_CHECKPOINT_LATCH.await();

        // cancel the task and wait. unless cancellation properly closes
        // the streams, this will never terminate
        task.cancelExecution();
        task.getExecutingThread().join();

        assertEquals(ExecutionState.CANCELED, task.getExecutionState());
        assertNull(task.getFailureCause());
    }

    private void runTaskExpectCheckpointDeclined(
            Task task, TestDeclinedCheckpointResponder checkpointResponder) throws Exception {
        // start the task and wait until it is in "restore"
        task.startTaskThread();

        checkpointResponder.declinedLatch.await();

        Assert.assertEquals(ExecutionState.RUNNING, task.getExecutionState());

        task.cancelExecution();
        task.getExecutingThread().join();
    }

    private void runTaskExpectFailure(Task task) throws Exception {
        task.startTaskThread();
        task.getExecutingThread().join();
        Assert.assertEquals(ExecutionState.FAILED, task.getExecutionState());
    }

    // ------------------------------------------------------------------------
    //  Utilities
    // ------------------------------------------------------------------------

    private static Task createTask(
            StreamOperator<?> op, StateBackend backend, CheckpointResponder checkpointResponder)
            throws IOException {

        Configuration taskConfig = new Configuration();
        StreamConfig cfg = new StreamConfig(taskConfig);
        cfg.setStreamOperator(op);
        cfg.setOperatorID(new OperatorID());
        cfg.setStateBackend(backend);

        ExecutionConfig executionConfig = new ExecutionConfig();

        JobInformation jobInformation =
                new JobInformation(
                        new JobID(),
                        "test job name",
                        new SerializedValue<>(executionConfig),
                        new Configuration(),
                        Collections.emptyList(),
                        Collections.emptyList());

        TaskInformation taskInformation =
                new TaskInformation(
                        new JobVertexID(),
                        "test task name",
                        1,
                        11,
                        TestStreamTask.class.getName(),
                        taskConfig);

        ShuffleEnvironment<?, ?> shuffleEnvironment = new NettyShuffleEnvironmentBuilder().build();

        return new Task(
                jobInformation,
                taskInformation,
                new ExecutionAttemptID(),
                new AllocationID(),
                0,
                0,
                Collections.<ResultPartitionDeploymentDescriptor>emptyList(),
                Collections.<InputGateDeploymentDescriptor>emptyList(),
                mock(MemoryManager.class),
                mock(IOManager.class),
                shuffleEnvironment,
                new KvStateService(new KvStateRegistry(), null, null),
                mock(BroadcastVariableManager.class),
                new TaskEventDispatcher(),
                ExternalResourceInfoProvider.NO_EXTERNAL_RESOURCES,
                new TestTaskStateManager(),
                mock(TaskManagerActions.class),
                mock(InputSplitProvider.class),
                checkpointResponder,
                new NoOpTaskOperatorEventGateway(),
                new TestGlobalAggregateManager(),
                TestingClassLoaderLease.newBuilder().build(),
                new FileCache(
                        new String[] {EnvironmentInformation.getTemporaryFileDirectory()},
                        VoidPermanentBlobService.INSTANCE),
                new TestingTaskManagerRuntimeInfo(),
                UnregisteredMetricGroups.createUnregisteredTaskMetricGroup(),
                mock(PartitionProducerStateChecker.class),
                Executors.directExecutor());
    }

    // ------------------------------------------------------------------------
    //  checkpoint responder that records a call to decline.
    // ------------------------------------------------------------------------
    private static class TestDeclinedCheckpointResponder implements CheckpointResponder {

        final OneShotLatch declinedLatch = new OneShotLatch();

        @Override
        public void acknowledgeCheckpoint(
                JobID jobID,
                ExecutionAttemptID executionAttemptID,
                long checkpointId,
                CheckpointMetrics checkpointMetrics,
                TaskStateSnapshot subtaskState) {

            throw new RuntimeException("Unexpected call.");
        }

        @Override
        public void reportCheckpointMetrics(
                JobID jobID,
                ExecutionAttemptID executionAttemptID,
                long checkpointId,
                CheckpointMetrics checkpointMetrics) {}

        @Override
        public void declineCheckpoint(
                JobID jobID,
                ExecutionAttemptID executionAttemptID,
                long checkpointId,
                CheckpointException checkpointException) {

            declinedLatch.trigger();
        }

        public OneShotLatch getDeclinedLatch() {
            return declinedLatch;
        }
    }

    // ------------------------------------------------------------------------
    //  state backends that trigger errors in checkpointing.
    // ------------------------------------------------------------------------

    private static class SyncFailureInducingStateBackend extends MemoryStateBackend {

        private static final long serialVersionUID = -1915780414440060539L;

        @Override
        public OperatorStateBackend createOperatorStateBackend(
                Environment env,
                String operatorIdentifier,
                @Nonnull Collection<OperatorStateHandle> stateHandles,
                CloseableRegistry cancelStreamRegistry)
                throws Exception {
            return new DefaultOperatorStateBackendBuilder(
                    env.getUserCodeClassLoader().asClassLoader(),
                    env.getExecutionConfig(),
                    true,
                    stateHandles,
                    cancelStreamRegistry) {
                @Override
                @SuppressWarnings("unchecked")
                public DefaultOperatorStateBackend build() {
                    return new DefaultOperatorStateBackend(
                            executionConfig,
                            cancelStreamRegistry,
                            new HashMap<>(),
                            new HashMap<>(),
                            new HashMap<>(),
                            new HashMap<>(),
                            mock(SnapshotStrategyRunner.class)) {
                        @Nonnull
                        @Override
                        public RunnableFuture<SnapshotResult<OperatorStateHandle>> snapshot(
                                long checkpointId,
                                long timestamp,
                                @Nonnull CheckpointStreamFactory streamFactory,
                                @Nonnull CheckpointOptions checkpointOptions)
                                throws Exception {

                            throw new Exception("Sync part snapshot exception.");
                        }
                    };
                }
            }.build();
        }

        @Override
        public SyncFailureInducingStateBackend configure(
                ReadableConfig configuration, ClassLoader classLoader) {
            // retain this instance, no re-configuration!
            return this;
        }
    }

    private static class AsyncFailureInducingStateBackend extends MemoryStateBackend {
        private static final long serialVersionUID = -7613628662587098470L;

        @Override
        public OperatorStateBackend createOperatorStateBackend(
                Environment env,
                String operatorIdentifier,
                @Nonnull Collection<OperatorStateHandle> stateHandles,
                CloseableRegistry cancelStreamRegistry)
                throws Exception {
            return new DefaultOperatorStateBackendBuilder(
                    env.getUserCodeClassLoader().asClassLoader(),
                    env.getExecutionConfig(),
                    true,
                    stateHandles,
                    cancelStreamRegistry) {
                @Override
                public DefaultOperatorStateBackend build() {
                    CloseableRegistry registryForStateBackend = new CloseableRegistry();
                    return new DefaultOperatorStateBackend(
                            executionConfig,
                            registryForStateBackend,
                            new HashMap<>(),
                            new HashMap<>(),
                            new HashMap<>(),
                            new HashMap<>(),
                            new SnapshotStrategyRunner<>(
                                    "Failing strategy",
                                    FAILING_STRATEGY,
                                    registryForStateBackend,
                                    SnapshotExecutionType.ASYNCHRONOUS));
                }
            }.build();
        }

        public static final SnapshotStrategy<OperatorStateHandle, SnapshotResources>
                FAILING_STRATEGY =
                        new SnapshotStrategy<OperatorStateHandle, SnapshotResources>() {
                            @Override
                            public SnapshotResources syncPrepareResources(long checkpointId)
                                    throws Exception {
                                return null;
                            }

                            @Override
                            public SnapshotResultSupplier<OperatorStateHandle> asyncSnapshot(
                                    SnapshotResources syncPartResource,
                                    long checkpointId,
                                    long timestamp,
                                    @Nonnull CheckpointStreamFactory streamFactory,
                                    @Nonnull CheckpointOptions checkpointOptions) {
                                return (snapshotCloseableRegistry) -> {
                                    throw new Exception("Async part snapshot exception.");
                                };
                            }
                        };

        @Override
        public AsyncFailureInducingStateBackend configure(
                ReadableConfig config, ClassLoader classLoader) {
            // retain this instance, no re-configuration!
            return this;
        }
    }

    // ------------------------------------------------------------------------
    //  locking output stream.
    // ------------------------------------------------------------------------

    private static final class LockingOutputStream extends CheckpointStateOutputStream {

        private final Object lock = new Object();
        private volatile boolean closed;

        @Nullable
        @Override
        public StreamStateHandle closeAndGetHandle() throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(int b) throws IOException {
            // this needs to not react to interrupts until the handle is closed
            synchronized (lock) {
                while (!closed) {
                    try {
                        lock.wait();
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            synchronized (lock) {
                closed = true;
                lock.notifyAll();
            }
        }

        @Override
        public long getPos() {
            return 0;
        }

        @Override
        public void flush() {}

        @Override
        public void sync() {}
    }

    // ------------------------------------------------------------------------
    //  test source operator that calls into the locking checkpoint output stream.
    // ------------------------------------------------------------------------

    @SuppressWarnings("serial")
    private static final class FilterOperator extends StreamFilter<Object> {
        private static final long serialVersionUID = 1L;

        public FilterOperator() {
            super(
                    new FilterFunction<Object>() {
                        @Override
                        public boolean filter(Object value) {
                            return false;
                        }
                    });
        }
    }

    @SuppressWarnings("serial")
    private static final class TestOperator extends StreamFilter<Object> {
        private static final long serialVersionUID = 1L;

        public TestOperator() {
            super(
                    new FilterFunction<Object>() {
                        @Override
                        public boolean filter(Object value) {
                            return false;
                        }
                    });
        }

        @Override
        public void snapshotState(StateSnapshotContext context) throws Exception {
            OperatorStateCheckpointOutputStream outStream = context.getRawOperatorStateOutput();

            IN_CHECKPOINT_LATCH.trigger();

            // this should lock
            outStream.write(1);
        }
    }

    /** Stream task that simply triggers a checkpoint. */
    public static final class TestStreamTask extends OneInputStreamTask<Object, Object> {

        public TestStreamTask(Environment env) throws Exception {
            super(env);
        }

        @Override
        public void init() {}

        @Override
        protected void processInput(MailboxDefaultAction.Controller controller) throws Exception {
            triggerCheckpointOnBarrier(
                    new CheckpointMetaData(11L, System.currentTimeMillis()),
                    CheckpointOptions.forCheckpointWithDefaultLocation(),
                    new CheckpointMetricsBuilder());

            while (isRunning()) {
                Thread.sleep(1L);
            }
            controller.suspendDefaultAction();
            mailboxProcessor.suspend();
        }

        @Override
        protected void cleanUpInternal() {}
    }
}
