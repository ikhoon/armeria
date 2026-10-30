/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.armeria.common.metric;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.internal.TimedExecutorService;
import io.netty.channel.IoEventLoop;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SingleThreadIoEventLoop;
import io.netty.channel.nio.NioIoHandler;

// PoC: times execute/submit; scheduled, lazy, tail and direct I/O coverage is incomplete.
final class TimedEventLoopGroup extends MultiThreadIoEventLoopGroup {

    TimedEventLoopGroup(int numThreads, MeterRegistry registry, String poolName) {
        super(numThreads, (Executor) null, NioIoHandler.newFactory(),
              registry, poolName, new AtomicInteger());
    }

    @Override
    protected IoEventLoop newChild(Executor executor, IoHandlerFactory ioHandlerFactory, Object... args) {
        return new TimedEventLoop(this, executor, ioHandlerFactory,
                                  (MeterRegistry) args[0], (String) args[1],
                                  ((AtomicInteger) args[2]).getAndIncrement());
    }

    private static final class TimedEventLoop extends SingleThreadIoEventLoop {

        private final TimedExecutorService timed;

        TimedEventLoop(TimedEventLoopGroup parent, Executor executor, IoHandlerFactory ioHandlerFactory,
                       MeterRegistry registry, String poolName, int loopId) {
            super(parent, executor, ioHandlerFactory);
            timed = new TimedExecutorService(registry, new SubmissionExecutor(), poolName, "",
                                             Tags.of("loop", Integer.toString(loopId)));
        }

        @Override
        public void execute(Runnable task) {
            timed.execute(requireNonNull(task, "task"));
        }

        // Call super.execute() to avoid recursing through the instrumented entry point.
        private void enqueue(Runnable task) {
            super.execute(task);
        }

        @SuppressWarnings("deprecation")
        private final class SubmissionExecutor extends AbstractExecutorService {

            @Override
            public void execute(Runnable task) {
                enqueue(task);
            }

            @Override
            public void shutdown() {
                TimedEventLoop.this.shutdown();
            }

            @Override
            public List<Runnable> shutdownNow() {
                return TimedEventLoop.this.shutdownNow();
            }

            @Override
            public boolean isShutdown() {
                return TimedEventLoop.this.isShutdown();
            }

            @Override
            public boolean isTerminated() {
                return TimedEventLoop.this.isTerminated();
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return TimedEventLoop.this.awaitTermination(timeout, unit);
            }
        }
    }
}
