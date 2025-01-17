/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.execution;

import com.facebook.presto.execution.scheduler.NodeSchedulerConfig;
import com.facebook.presto.metadata.AllNodes;
import com.facebook.presto.metadata.InternalNodeManager;
import com.facebook.presto.server.ServerConfig;
import com.facebook.presto.spi.Node;
import com.facebook.presto.spi.PrestoException;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.airlift.units.Duration;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.concurrent.GuardedBy;
import javax.inject.Inject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

import static com.facebook.airlift.concurrent.Threads.threadsNamed;
import static com.facebook.presto.spi.StandardErrorCode.GENERIC_INSUFFICIENT_RESOURCES;
import static com.facebook.presto.spi.StandardErrorCode.NO_CPP_SIDECARS;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class ClusterSizeMonitor
{
    private final InternalNodeManager nodeManager;
    private final boolean includeCoordinator;
    private final int workerMinCount;
    private final int workerMinCountActive;
    private final Duration executionMaxWait;
    private final int coordinatorMinCount;
    private final int coordinatorMinCountActive;
    private final Duration coordinatorMaxWait;

    private final Duration coordinatorSidecarMaxWait;
    private final int resourceManagerMinCountActive;
    private final ScheduledExecutorService executor;

    private final Consumer<AllNodes> listener = this::updateAllNodes;
    private final boolean isCoordinatorSidecarEnabled;

    @GuardedBy("this")
    private int currentWorkerCount;

    @GuardedBy("this")
    private int currentCoordinatorCount;

    @GuardedBy("this")
    private int currentResourceManagerCount;

    @GuardedBy("this")
    private int currentCoordinatorSidecarCount;

    @GuardedBy("this")
    private final List<SettableFuture<?>> workerSizeFutures = new ArrayList<>();

    @GuardedBy("this")
    private final List<SettableFuture<?>> coordinatorSizeFutures = new ArrayList<>();

    @GuardedBy("this")
    private final List<SettableFuture<?>> coordinatorSidecarSizeFutures = new ArrayList<>();

    @Inject
    public ClusterSizeMonitor(InternalNodeManager nodeManager, NodeSchedulerConfig nodeSchedulerConfig, QueryManagerConfig queryManagerConfig, NodeResourceStatusConfig nodeResourceStatusConfig, ServerConfig serverConfig)
    {
        this(
                nodeManager,
                requireNonNull(nodeSchedulerConfig, "nodeSchedulerConfig is null").isIncludeCoordinator(),
                requireNonNull(queryManagerConfig, "queryManagerConfig is null").getRequiredWorkers(),
                requireNonNull(nodeResourceStatusConfig, "nodeResourceStatusConfig is null").getRequiredWorkersActive(),
                queryManagerConfig.getRequiredWorkersMaxWait(),
                queryManagerConfig.getRequiredCoordinators(),
                nodeResourceStatusConfig.getRequiredCoordinatorsActive(),
                queryManagerConfig.getRequiredCoordinatorsMaxWait(),
                queryManagerConfig.getRequiredCoordinatorSidecarsMaxWait(),
                nodeResourceStatusConfig.getRequiredResourceManagersActive(),
                serverConfig.isCoordinatorSidecarEnabled());
    }

    public ClusterSizeMonitor(
            InternalNodeManager nodeManager,
            boolean includeCoordinator,
            int workerMinCount,
            int workerMinCountActive,
            Duration executionMaxWait,
            int coordinatorMinCount,
            int coordinatorMinCountActive,
            Duration coordinatorMaxWait,
            Duration coordinatorSidecarMaxWait,
            int resourceManagerMinCountActive,
            boolean isCoordinatorSidecarEnabled)
    {
        this.nodeManager = requireNonNull(nodeManager, "nodeManager is null");
        this.includeCoordinator = includeCoordinator;
        checkArgument(workerMinCount >= 0, "executionMinCount is negative");
        this.workerMinCount = workerMinCount;
        checkArgument(workerMinCountActive >= 0, "executionMinCountActive is negative");
        this.workerMinCountActive = workerMinCountActive;
        this.executionMaxWait = requireNonNull(executionMaxWait, "executionMaxWait is null");
        checkArgument(coordinatorMinCount >= 0, "coordinatorMinCount is negative");
        this.coordinatorMinCount = coordinatorMinCount;
        checkArgument(coordinatorMinCountActive >= 0, "coordinatorMinCountActive is negative");
        this.coordinatorMinCountActive = coordinatorMinCountActive;
        this.coordinatorMaxWait = requireNonNull(coordinatorMaxWait, "coordinatorMaxWait is null");
        this.coordinatorSidecarMaxWait = requireNonNull(coordinatorSidecarMaxWait, "coordinatorSidecarMaxWait is null");
        checkArgument(resourceManagerMinCountActive >= 0, "resourceManagerMinCountActive is negative");
        this.resourceManagerMinCountActive = resourceManagerMinCountActive;
        this.executor = newSingleThreadScheduledExecutor(threadsNamed("node-monitor-%s"));
        this.isCoordinatorSidecarEnabled = isCoordinatorSidecarEnabled;
    }

    @PostConstruct
    public void start()
    {
        nodeManager.addNodeChangeListener(listener);
        updateAllNodes(nodeManager.getAllNodes());
    }

    @PreDestroy
    public void stop()
    {
        nodeManager.removeNodeChangeListener(listener);
        executor.shutdownNow();
    }

    /**
     * @return true when the current worker count is greater or equals to
     * minimum worker count for Coordinator.
     */
    public boolean hasRequiredWorkers()
    {
        return currentWorkerCount >= workerMinCountActive;
    }

    /**
     * @return true when the current resource manager count is greater or equals to
     * minimum resource manager count for Coordinator.
     */
    public boolean hasRequiredResourceManagers()
    {
        return currentResourceManagerCount >= resourceManagerMinCountActive;
    }

    /**
     * @return true when the current coordinator count in a cluster is greater or equals to
     * minimum coordinator count for a given Coordinator.
     */
    public boolean hasRequiredCoordinators()
    {
        return currentCoordinatorCount >= coordinatorMinCountActive;
    }

    /**
     * @return true when the current coordinator sidecars count in a cluster is greater or equals to
     * minimum coordinator sidecars count for a given Coordinator.
     */
    public boolean hasRequiredCoordinatorSidecars()
    {
        return currentCoordinatorSidecarCount > 0;
    }

    /**
     * Returns a listener that completes when the minimum number of workers for the cluster has been met.
     * Note: caller should not add a listener using the direct executor, as this can delay the
     * notifications for other listeners.
     */
    public synchronized ListenableFuture<?> waitForMinimumWorkers()
    {
        if (currentWorkerCount >= workerMinCount) {
            return immediateFuture(null);
        }

        SettableFuture<?> future = SettableFuture.create();
        workerSizeFutures.add(future);

        // if future does not finish in wait period, complete with an exception
        ScheduledFuture<?> timeoutTask = executor.schedule(
                () -> {
                    synchronized (this) {
                        future.setException(new PrestoException(
                                GENERIC_INSUFFICIENT_RESOURCES,
                                format("Insufficient active worker nodes. Waited %s for at least %s workers, but only %s workers are active", executionMaxWait, workerMinCount, currentWorkerCount)));
                    }
                },
                executionMaxWait.toMillis(),
                MILLISECONDS);

        // remove future if finished (e.g., canceled, timed out)
        future.addListener(() -> {
            timeoutTask.cancel(true);
            removeWorkerFuture(future);
        }, executor);

        return future;
    }

    public synchronized ListenableFuture<?> waitForMinimumCoordinators()
    {
        if (currentCoordinatorCount >= coordinatorMinCount) {
            return immediateFuture(null);
        }

        SettableFuture<?> future = SettableFuture.create();
        coordinatorSizeFutures.add(future);

        // if future does not finish in wait period, complete with an exception
        ScheduledFuture<?> timeoutTask = executor.schedule(
                () -> {
                    synchronized (this) {
                        future.setException(new PrestoException(
                                GENERIC_INSUFFICIENT_RESOURCES,
                                format("Insufficient active coordinator nodes. Waited %s for at least %s coordinators, but only %s coordinators are active", executionMaxWait, 2, currentCoordinatorCount)));
                    }
                },
                coordinatorMaxWait.toMillis(),
                MILLISECONDS);

        // remove future if finished (e.g., canceled, timed out)
        future.addListener(() -> {
            timeoutTask.cancel(true);
            removeCoordinatorFuture(future);
        }, executor);

        return future;
    }

    public synchronized ListenableFuture<?> waitForMinimumCoordinatorSidecars()
    {
        if (currentCoordinatorSidecarCount > 0 || !isCoordinatorSidecarEnabled) {
            return immediateFuture(null);
        }

        SettableFuture<?> future = SettableFuture.create();
        coordinatorSidecarSizeFutures.add(future);

        // if future does not finish in wait period, complete with an exception
        ScheduledFuture<?> timeoutTask = executor.schedule(
                () -> {
                    synchronized (this) {
                        future.setException(new PrestoException(
                                NO_CPP_SIDECARS,
                                format("Insufficient active coordinator sidecar nodes. Waited %s for at least 1 coordinator sidecars, but only 0 coordinator sidecars are active", coordinatorSidecarMaxWait)));
                    }
                },
                coordinatorSidecarMaxWait.toMillis(),
                MILLISECONDS);

        // remove future if finished (e.g., canceled, timed out)
        future.addListener(() -> {
            timeoutTask.cancel(true);
            removeCoordinatorSidecarFuture(future);
        }, executor);

        return future;
    }

    private synchronized void removeWorkerFuture(SettableFuture<?> future)
    {
        workerSizeFutures.remove(future);
    }

    private synchronized void removeCoordinatorFuture(SettableFuture<?> future)
    {
        coordinatorSizeFutures.remove(future);
    }

    private synchronized void removeCoordinatorSidecarFuture(SettableFuture<?> future)
    {
        coordinatorSidecarSizeFutures.remove(future);
    }

    private synchronized void updateAllNodes(AllNodes allNodes)
    {
        if (includeCoordinator) {
            currentWorkerCount = allNodes.getActiveNodes().size();
        }
        else {
            Set<Node> activeNodes = new HashSet<>(allNodes.getActiveNodes());
            activeNodes.removeAll(allNodes.getActiveCoordinators());
            activeNodes.removeAll(allNodes.getActiveResourceManagers());
            currentWorkerCount = activeNodes.size();
        }
        currentCoordinatorCount = allNodes.getActiveCoordinators().size();
        currentResourceManagerCount = allNodes.getActiveResourceManagers().size();
        currentCoordinatorSidecarCount = allNodes.getActiveCoordinatorSidecars().size();
        if (currentWorkerCount >= workerMinCount) {
            List<SettableFuture<?>> listeners = ImmutableList.copyOf(workerSizeFutures);
            workerSizeFutures.clear();
            executor.submit(() -> listeners.forEach(listener -> listener.set(null)));
        }
        if (currentCoordinatorCount >= coordinatorMinCount) {
            List<SettableFuture<?>> listeners = ImmutableList.copyOf(coordinatorSizeFutures);
            coordinatorSizeFutures.clear();
            executor.submit(() -> listeners.forEach(listener -> listener.set(null)));
        }
        if (currentCoordinatorSidecarCount == 1) {
            List<SettableFuture<?>> listeners = ImmutableList.copyOf(coordinatorSidecarSizeFutures);
            coordinatorSidecarSizeFutures.clear();
            executor.submit(() -> listeners.forEach(listener -> listener.set(null)));
        }
    }
}
