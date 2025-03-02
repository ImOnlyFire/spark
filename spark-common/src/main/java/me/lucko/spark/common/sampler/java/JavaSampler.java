/*
 * This file is part of spark.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.lucko.spark.common.sampler.java;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.command.sender.CommandSender;
import me.lucko.spark.common.sampler.AbstractSampler;
import me.lucko.spark.common.sampler.SamplerSettings;
import me.lucko.spark.common.sampler.node.MergeMode;
import me.lucko.spark.common.sampler.source.ClassSourceLookup;
import me.lucko.spark.common.sampler.window.ProfilingWindowUtils;
import me.lucko.spark.common.sampler.window.WindowStatisticsCollector;
import me.lucko.spark.common.tick.TickHook;
import me.lucko.spark.proto.SparkSamplerProtos.SamplerData;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

/**
 * A sampler implementation using Java (WarmRoast).
 */
public class JavaSampler extends AbstractSampler implements Runnable {
    private static final AtomicInteger THREAD_ID = new AtomicInteger(0);

    /** The worker pool for inserting stack nodes */
    private final ScheduledExecutorService workerPool = Executors.newScheduledThreadPool(
            6, new ThreadFactoryBuilder().setNameFormat("spark-worker-" + THREAD_ID.getAndIncrement() + "-%d").build()
    );

    /** The main sampling task */
    private ScheduledFuture<?> task;

    /** The thread management interface for the current JVM */
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    /** Responsible for aggregating and then outputting collected sampling data */
    private final JavaDataAggregator dataAggregator;

    /** The last window that was profiled */
    private final AtomicInteger lastWindow = new AtomicInteger();
    
    public JavaSampler(SparkPlatform platform, SamplerSettings settings, boolean ignoreSleeping, boolean ignoreNative) {
        super(platform, settings);
        this.dataAggregator = new SimpleDataAggregator(this.workerPool, settings.threadGrouper(), settings.interval(), ignoreSleeping, ignoreNative);
    }

    public JavaSampler(SparkPlatform platform, SamplerSettings settings, boolean ignoreSleeping, boolean ignoreNative, TickHook tickHook, int tickLengthThreshold) {
        super(platform, settings);
        this.dataAggregator = new TickedDataAggregator(this.workerPool, settings.threadGrouper(), settings.interval(), ignoreSleeping, ignoreNative, tickHook, tickLengthThreshold);
    }

    @Override
    public void start() {
        super.start();

        TickHook tickHook = this.platform.getTickHook();
        if (tickHook != null) {
            if (this.dataAggregator instanceof TickedDataAggregator) {
                WindowStatisticsCollector.ExplicitTickCounter counter = this.windowStatisticsCollector.startCountingTicksExplicit(tickHook);
                ((TickedDataAggregator) this.dataAggregator).setTickCounter(counter);
            } else {
                this.windowStatisticsCollector.startCountingTicks(tickHook);
            }
        }

        this.task = this.workerPool.scheduleAtFixedRate(this, 0, this.interval, TimeUnit.MICROSECONDS);
    }

    @Override
    public void stop() {
        super.stop();

        this.task.cancel(false);

        // collect statistics for the final window
        this.windowStatisticsCollector.measureNow(this.lastWindow.get());
    }

    @Override
    public void run() {
        // this is effectively synchronized, the worker pool will not allow this task
        // to concurrently execute.
        try {
            long time = System.currentTimeMillis();

            if (this.autoEndTime != -1 && this.autoEndTime <= time) {
                stop();
                this.future.complete(this);
                return;
            }

            int window = ProfilingWindowUtils.unixMillisToWindow(time);
            ThreadInfo[] threadDumps = this.threadDumper.dumpThreads(this.threadBean);
            this.workerPool.execute(new InsertDataTask(threadDumps, window));
        } catch (Throwable t) {
            stop();
            this.future.completeExceptionally(t);
        }
    }

    private final class InsertDataTask implements Runnable {
        private final ThreadInfo[] threadDumps;
        private final int window;

        InsertDataTask(ThreadInfo[] threadDumps, int window) {
            this.threadDumps = threadDumps;
            this.window = window;
        }

        @Override
        public void run() {
            for (ThreadInfo threadInfo : this.threadDumps) {
                if (threadInfo.getThreadName() == null || threadInfo.getStackTrace() == null) {
                    continue;
                }
                JavaSampler.this.dataAggregator.insertData(threadInfo, this.window);
            }

            // if we have just stepped over into a new window...
            int previousWindow = JavaSampler.this.lastWindow.getAndUpdate(previous -> Math.max(this.window, previous));
            if (previousWindow != 0 && previousWindow != this.window) {

                // collect statistics for the previous window
                JavaSampler.this.windowStatisticsCollector.measureNow(previousWindow);

                // prune data older than the history size
                IntPredicate predicate = ProfilingWindowUtils.keepHistoryBefore(this.window);
                JavaSampler.this.dataAggregator.pruneData(predicate);
                JavaSampler.this.windowStatisticsCollector.pruneStatistics(predicate);
            }
        }
    }

    @Override
    public SamplerData toProto(SparkPlatform platform, CommandSender creator, String comment, MergeMode mergeMode, ClassSourceLookup classSourceLookup) {
        SamplerData.Builder proto = SamplerData.newBuilder();
        writeMetadataToProto(proto, platform, creator, comment, this.dataAggregator);
        writeDataToProto(proto, this.dataAggregator, mergeMode, classSourceLookup);
        return proto.build();
    }

}
