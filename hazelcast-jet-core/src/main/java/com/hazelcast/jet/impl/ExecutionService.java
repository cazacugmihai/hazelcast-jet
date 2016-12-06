/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.JetEngineConfig;
import com.hazelcast.logging.ILogger;
import com.hazelcast.util.concurrent.BackoffIdleStrategy;
import com.hazelcast.util.concurrent.IdleStrategy;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.partitioningBy;

class ExecutionService {

    private static final IdleStrategy IDLER =
            new BackoffIdleStrategy(0, 0, MICROSECONDS.toNanos(1), MILLISECONDS.toNanos(1));
    private final ExecutorService blockingTaskletExecutor = newCachedThreadPool(new BlockingTaskThreadFactory());
    private final ClassLoader contextClassLoader;
    private final NonBlockingWorker[] workers;
    private final Thread[] threads;
    private final String hzInstanceName;
    private final String name;
    private final ILogger logger;

    ExecutionService(HazelcastInstance hz, String name, JetEngineConfig cfg, ClassLoader contextClassLoader) {
        this.hzInstanceName = hz.getName();
        this.name = name;
        this.workers = new NonBlockingWorker[cfg.getParallelism()];
        this.threads = new Thread[cfg.getParallelism()];
        this.contextClassLoader = contextClassLoader;
        this.logger = hz.getLoggingService().getLogger(ExecutionService.class);
    }

    ExecutionService(HazelcastInstance hz, String name, JetEngineConfig cfg) {
        this(hz, name, cfg, null);
    }

    /**
     * @return instance of {@code java.util.concurrent.CompletableFuture}
     */
    CompletionStage<Void> execute(List<? extends Tasklet> tasklets) {
        ensureStillRunning();
        final JobFuture jobFuture = new JobFuture(tasklets.size());
        final Map<Boolean, List<Tasklet>> byBlocking = tasklets.stream().collect(partitioningBy(Tasklet::isBlocking));
        submitBlockingTasklets(jobFuture, byBlocking.get(true));
        submitNonblockingTasklets(jobFuture, byBlocking.get(false));
        return jobFuture;
    }

    void shutdown() {
        blockingTaskletExecutor.shutdown();
        synchronized (this) {
            for (NonBlockingWorker worker : workers) {
                if (worker != null) {
                    worker.isShutdown = true;
                }
            }
        }
    }

    private void ensureStillRunning() {
        if (blockingTaskletExecutor.isShutdown()) {
            throw new IllegalStateException("Execution service was ordered to shut down");
        }
    }

    private void submitBlockingTasklets(JobFuture jobFuture, List<Tasklet> tasklets) {
        jobFuture.blockingFutures = tasklets.stream()
                                            .map(t -> new BlockingWorker(new TaskletTracker(t, jobFuture)))
                                            .map(blockingTaskletExecutor::submit)
                                            .collect(Collectors.toList());
    }

    private void submitNonblockingTasklets(JobFuture jobFuture, List<Tasklet> tasklets) {
        ensureThreadsStarted();
        final List<TaskletTracker>[] trackersByThread = new List[workers.length];
        Arrays.setAll(trackersByThread, i -> new ArrayList());
        int i = 0;
        for (Tasklet t : tasklets) {
            if (initPropagatingFailure(t, jobFuture)) {
                trackersByThread[i++ % trackersByThread.length].add(new TaskletTracker(t, jobFuture));
            }
        }
        for (i = 0; i < trackersByThread.length; i++) {
            workers[i].trackers.addAll(trackersByThread[i]);
        }
        Arrays.stream(threads).forEach(LockSupport::unpark);
    }

    private synchronized void ensureThreadsStarted() {
        if (workers[0] != null) {
            return;
        }
        Arrays.setAll(workers, i -> new NonBlockingWorker(workers));
        Arrays.setAll(threads, i -> createThread(workers[i], "nonblocking-executor", i));
        Arrays.stream(threads).forEach(Thread::start);
    }

    private Thread createThread(Runnable r, String executorName, int seq) {
        Thread t = new Thread(r, threadNamePrefix() + executorName + ".thread-" + seq);
        if (contextClassLoader != null) {
            t.setContextClassLoader(contextClassLoader);
        }
        return t;
    }

    private String threadNamePrefix() {
        return "hz." + hzInstanceName + ".jet-engine." + name + '.';
    }

    private static boolean initPropagatingFailure(Tasklet t, JobFuture jobFuture) {
        try {
            t.init();
            return true;
        } catch (Throwable e) {
            jobFuture.completeExceptionally(e);
            return false;
        }
    }

    private final class BlockingWorker implements Runnable {
        private final TaskletTracker tracker;

        private BlockingWorker(TaskletTracker tracker) {
            this.tracker = tracker;
        }

        @Override
        public void run() {
            final Tasklet t = tracker.tasklet;
            try {
                t.init();
                long idleCount = 0;
                for (ProgressState result;
                     !(result = t.call()).isDone() && !tracker.jobFuture.isCompletedExceptionally();
                        ) {
                    if (result.isMadeProgress()) {
                        idleCount = 0;
                    } else {
                        IDLER.idle(++idleCount);
                    }
                }
                tracker.jobFuture.taskletDone();
            } catch (Throwable e) {
                logger.warning("Exception in " + t, e);
                tracker.jobFuture.completeExceptionally(e);
            }
        }
    }

    private class NonBlockingWorker implements Runnable {
        private final List<TaskletTracker> trackers;
        private final NonBlockingWorker[] colleagues;
        private volatile boolean isShutdown;

        NonBlockingWorker(NonBlockingWorker[] colleagues) {
            this.colleagues = colleagues;
            this.trackers = new CopyOnWriteArrayList<>();
        }

        @Override
        public void run() {
            long idleCount = 0;
            while (!isShutdown) {
                boolean madeProgress = false;
                for (TaskletTracker t : trackers) {
                    final NonBlockingWorker stealingWorker = t.stealingWorker.get();
                    if (stealingWorker != null) {
                        t.stealingWorker.set(null);
                        trackers.remove(t);
                        stealingWorker.trackers.add(t);
                        continue;
                    }
                    try {
                        final ProgressState result = t.tasklet.call();
                        if (result.isDone()) {
                            dismissTasklet(t);
                        } else {
                            madeProgress |= result.isMadeProgress();
                        }
                    } catch (Throwable e) {
                        logger.warning("Exception in " + t.tasklet, e);
                        t.jobFuture.completeExceptionally(e);
                    }
                    if (t.jobFuture.isCompletedExceptionally()) {
                        dismissTasklet(t);
                    }
                }
                if (madeProgress) {
                    idleCount = 0;
                } else {
                    IDLER.idle(++idleCount);
                }
            }
            // Best-effort attempt to release all tasklets. A tasklet can still be added
            // to a dead worker through work stealing.
            trackers.clear();
        }

        private void dismissTasklet(TaskletTracker t) {
            t.jobFuture.taskletDone();
            trackers.remove(t);
            stealWork();
        }

        private void stealWork() {
            while (true) {
                // start with own tasklet list, try to find a longer one
                List<TaskletTracker> toStealFrom = trackers;
                for (NonBlockingWorker w : colleagues) {
                    if (w.trackers.size() > toStealFrom.size()) {
                        toStealFrom = w.trackers;
                    }
                }
                // if we couldn't find a list longer by more than one, there's nothing to steal
                if (toStealFrom.size() <= trackers.size() + 1) {
                    return;
                }
                // now we must find a task on this list which isn't already scheduled for moving
                for (TaskletTracker t : toStealFrom) {
                    if (t.stealingWorker.compareAndSet(null, this)) {
                        return;
                    }
                }
            }
        }
    }

    private static final class TaskletTracker {
        final Tasklet tasklet;
        final JobFuture jobFuture;
        final AtomicReference<NonBlockingWorker> stealingWorker = new AtomicReference<>();

        TaskletTracker(Tasklet tasklet, JobFuture jobFuture) {
            this.tasklet = tasklet;
            this.jobFuture = jobFuture;
        }
    }

    private final class BlockingTaskThreadFactory implements ThreadFactory {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            return createThread(r, "blocking-executor", seq.getAndIncrement());
        }
    }

    private static final class JobFuture extends CompletableFuture<Void> {

        private final AtomicInteger completionLatch;
        private List<Future> blockingFutures;

        private JobFuture(int taskletCount) {
            this.completionLatch = new AtomicInteger(taskletCount);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) {
                blockingFutures.forEach(f -> f.cancel(true)); // CompletableFuture.cancel ignores the flag
            }
            return cancelled;
        }

        @SuppressFBWarnings(value = "NP_NONNULL_PARAM_VIOLATION", justification = "CompletableFuture<Void>")
        private void taskletDone() {
            if (completionLatch.decrementAndGet() == 0) {
                complete(null);
            }
        }
    }

}