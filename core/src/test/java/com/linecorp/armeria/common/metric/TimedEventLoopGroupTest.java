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

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.internal.TimedExecutorService;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;

@Timeout(15)
class TimedEventLoopGroupTest {

    @Test
    void outerWrapperDoesNotMeasureChannelTasks() throws Exception {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            final EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
            try {
                final TimedExecutorService timed =
                        new TimedExecutorService(registry, group, "outer", "", Tags.empty());
                timed.submit(() -> {}).get(5, SECONDS);
                final Timer execution = registry.get("executor").tag("name", "outer").timer();
                await().untilAsserted(() -> assertThat(execution.count()).isOne());

                final Channel channel = registerChannel(group);
                try {
                    channel.eventLoop().submit(() -> {}).get(5, SECONDS);
                    assertThat(execution.count()).isOne();
                    assertThat(channel.eventLoop()).isSameAs(group.next());
                } finally {
                    channel.close().sync();
                }
            } finally {
                group.shutdownGracefully(0, 5, SECONDS).sync();
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void childLoopMeasuresQueueDelayAndExecutionTime() throws Exception {
        final MockClock clock = new MockClock();
        final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        try {
            final EventLoopGroup group = new TimedEventLoopGroup(1, registry, "pool");
            final CountDownLatch started = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            try {
                group.execute(() -> {
                    started.countDown();
                    try {
                        if (!release.await(5, SECONDS)) {
                            throw new IllegalStateException("Blocker was not released");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
                assertThat(started.await(5, SECONDS)).isTrue();
                final Future<?> task =
                        group.next().submit(() -> clock.add(50, MILLISECONDS));
                clock.add(200, MILLISECONDS);
                release.countDown();
                task.get(5, SECONDS);

                final Timer execution = timer(registry, "pool", "executor");
                final Timer idle = timer(registry, "pool", "executor.idle");
                await().untilAsserted(() -> assertThat(execution.count()).isEqualTo(2));
                assertThat(idle.count()).isEqualTo(2);
                assertThat(idle.totalTime(MILLISECONDS)).isEqualTo(200);
                assertThat(execution.totalTime(MILLISECONDS)).isEqualTo(250);
            } finally {
                release.countDown();
                group.shutdownGracefully(0, 5, SECONDS).sync();
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void channelUsesTheInstrumentedChildLoop() throws Exception {
        final MockClock clock = new MockClock();
        final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        try {
            final EventLoopGroup group = new TimedEventLoopGroup(1, registry, "channel-pool");
            try {
                final Channel channel = registerChannel(group);
                try {
                    final Timer execution = timer(registry, "channel-pool", "executor");
                    await().untilAsserted(() -> assertThat(execution.count()).isOne());
                    assertThat(channel.eventLoop()).isSameAs(group.next());
                    channel.eventLoop().submit(() -> clock.add(75, MILLISECONDS)).get(5, SECONDS);
                    await().untilAsserted(() -> assertThat(execution.count()).isEqualTo(2));
                    assertThat(execution.totalTime(MILLISECONDS)).isEqualTo(75);
                } finally {
                    channel.close().sync();
                }
            } finally {
                group.shutdownGracefully(0, 5, SECONDS).sync();
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void nullTaskIsRejectedBeforeEnqueueing() throws Exception {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final EventLoopGroup group = new TimedEventLoopGroup(1, registry, "null-pool");
        try {
            assertThatThrownBy(() -> group.next().execute(null)).isInstanceOf(NullPointerException.class);
            assertThat(timer(registry, "null-pool", "executor").count()).isZero();
            assertThat(timer(registry, "null-pool", "executor.idle").count()).isZero();
        } finally {
            group.shutdownGracefully(0, 5, SECONDS).sync();
            registry.close();
        }
    }

    @Test
    void scheduledCallbackCreatedOnLoopIsNotMeasured() throws Exception {
        final MockClock clock = new MockClock();
        final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        final EventLoopGroup group = new TimedEventLoopGroup(1, registry, "scheduled-pool");
        try {
            final Future<?> scheduled = group.submit(() -> group.next().schedule(
                    () -> clock.add(100, MILLISECONDS), 100, MILLISECONDS)).get(5, SECONDS);
            final Timer execution = timer(registry, "scheduled-pool", "executor");
            await().untilAsserted(() -> assertThat(execution.count()).isOne());
            scheduled.get(5, SECONDS);
            assertThat(execution.count()).isOne();
            assertThat(execution.totalTime(MILLISECONDS)).isZero();
        } finally {
            group.shutdownGracefully(0, 5, SECONDS).sync();
            registry.close();
        }
    }

    @Test
    void failedSubmittedTaskIsMeasuredAndLoopKeepsRunning() throws Exception {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            final EventLoopGroup group = new TimedEventLoopGroup(1, registry, "failure-pool");
            try {
                final Future<?> failed = group.submit(() -> {
                    throw new IllegalArgumentException("test failure");
                });
                assertThatThrownBy(() -> failed.get(5, SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(IllegalArgumentException.class);
                assertThat(group.submit(() -> 42).get(5, SECONDS)).isEqualTo(42);
                await().untilAsserted(() ->
                        assertThat(timer(registry, "failure-pool", "executor").count()).isEqualTo(2));
            } finally {
                group.shutdownGracefully(0, 5, SECONDS).sync();
            }
        } finally {
            registry.close();
        }
    }

    private static Channel registerChannel(EventLoopGroup group) throws InterruptedException {
        return new Bootstrap().group(group)
                              .channel(NioSocketChannel.class)
                              .handler(new ChannelInboundHandlerAdapter())
                              .register().sync().channel();
    }

    private static Timer timer(SimpleMeterRegistry registry, String pool, String name) {
        return registry.get(name).tag("name", pool).tag("loop", "0").timer();
    }
}
