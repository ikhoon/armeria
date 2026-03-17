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
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.AcquisitionDecision.Type;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.embedded.EmbeddedChannel;

class ProtocolDispatchingStrategyTest {

    private static ClientRequestContext newContext() {
        return ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                   .build();
    }

    private static Connection newH2Connection() {
        return new Connection(new EmbeddedChannel(), SessionProtocol.H2, 100);
    }

    private static Connection newH1Connection() {
        return new Connection(new EmbeddedChannel(), SessionProtocol.H1, 1);
    }

    @Test
    void dispatchesToH2Strategy() {
        final AtomicReference<String> calledStrategy = new AtomicReference<>();

        final ConnectionAcquisitionStrategy h1Strategy = (ctx, pool) -> {
            calledStrategy.set("h1");
            return ConnectionAcquisitionStrategy.ofDefault().acquire(ctx, pool);
        };
        final ConnectionAcquisitionStrategy h2Strategy = (ctx, pool) -> {
            calledStrategy.set("h2");
            return ConnectionAcquisitionStrategy.ofDefault().acquire(ctx, pool);
        };

        final ProtocolDispatchingStrategy strategy =
                new ProtocolDispatchingStrategy(h1Strategy, h2Strategy,
                                                ConnectionAcquisitionStrategy.ofDefault());

        final DefaultConnectionPool pool = new DefaultConnectionPool(
                10, strategy, null);

        final Connection h2Conn = newH2Connection();
        pool.addConnection(h2Conn);

        final CompletableFuture<Connection> future = pool.acquire(newContext());
        assertThat(future).isDone();
        assertThat(future.join()).isSameAs(h2Conn);
        assertThat(calledStrategy.get()).isEqualTo("h2");

        pool.close();
    }

    @Test
    void dispatchesToH1Strategy() {
        final AtomicReference<String> calledStrategy = new AtomicReference<>();

        final ConnectionAcquisitionStrategy h1Strategy = (ctx, pool) -> {
            calledStrategy.set("h1");
            return ConnectionAcquisitionStrategy.ofDefault().acquire(ctx, pool);
        };
        final ConnectionAcquisitionStrategy h2Strategy = (ctx, pool) -> {
            calledStrategy.set("h2");
            return ConnectionAcquisitionStrategy.ofDefault().acquire(ctx, pool);
        };

        final ProtocolDispatchingStrategy strategy =
                new ProtocolDispatchingStrategy(h1Strategy, h2Strategy,
                                                ConnectionAcquisitionStrategy.ofDefault());

        final DefaultConnectionPool pool = new DefaultConnectionPool(
                10, strategy, null);

        final Connection h1Conn = newH1Connection();
        pool.addConnection(h1Conn);

        final CompletableFuture<Connection> future = pool.acquire(newContext());
        assertThat(future).isDone();
        assertThat(future.join()).isSameAs(h1Conn);
        assertThat(calledStrategy.get()).isEqualTo("h1");

        pool.close();
    }

    @Test
    void prefersH2OverH1() {
        // When both H1 and H2 connections are available, H2 should be tried first
        final ProtocolDispatchingStrategy strategy =
                new ProtocolDispatchingStrategy(
                        ConnectionAcquisitionStrategy.ofDefault(),
                        ConnectionAcquisitionStrategy.ofDefault(),
                        ConnectionAcquisitionStrategy.ofDefault());

        final DefaultConnectionPool pool = new DefaultConnectionPool(
                10, strategy, null);

        final Connection h1Conn = newH1Connection();
        final Connection h2Conn = newH2Connection();
        pool.addConnection(h1Conn);
        pool.addConnection(h2Conn);

        final CompletableFuture<Connection> future = pool.acquire(newContext());
        assertThat(future).isDone();
        assertThat(future.join()).isSameAs(h2Conn);

        pool.close();
    }

    @Test
    void fallsBackToH1WhenH2Full() {
        final ProtocolDispatchingStrategy strategy =
                new ProtocolDispatchingStrategy(
                        ConnectionAcquisitionStrategy.ofDefault(),
                        ConnectionAcquisitionStrategy.ofDefault(),
                        ConnectionAcquisitionStrategy.ofDefault());

        final DefaultConnectionPool pool = new DefaultConnectionPool(
                10, strategy, null);

        // H2 connection is fully loaded (100 active on max 100)
        final Connection h2Conn = new Connection(new EmbeddedChannel(), SessionProtocol.H2, 100);
        for (int i = 0; i < 100; i++) {
            h2Conn.incrementActiveRequests();
        }
        pool.addConnection(h2Conn);

        // H1 connection is idle
        final Connection h1Conn = newH1Connection();
        pool.addConnection(h1Conn);

        final CompletableFuture<Connection> future = pool.acquire(newContext());
        assertThat(future).isDone();
        assertThat(future.join()).isSameAs(h1Conn);

        pool.close();
    }

    @Test
    void createsNewWhenNoConnectionsAvailable() {
        final ProtocolDispatchingStrategy strategy =
                new ProtocolDispatchingStrategy(
                        ConnectionAcquisitionStrategy.ofDefault(),
                        ConnectionAcquisitionStrategy.ofDefault(),
                        ConnectionAcquisitionStrategy.ofDefault());

        final DefaultConnectionPool pool = new DefaultConnectionPool(
                5, strategy, null);

        // Empty pool should trigger CREATE_NEW
        final CompletableFuture<Connection> future = pool.acquire(newContext());
        assertThat(future).isNotDone();
        assertThat(pool.pendingConnectionCount()).isEqualTo(1);

        pool.close();
    }

    @Test
    void waitsWhenAtMaxAndNoAvailable() {
        final ProtocolDispatchingStrategy strategy =
                new ProtocolDispatchingStrategy(
                        ConnectionAcquisitionStrategy.ofDefault(),
                        ConnectionAcquisitionStrategy.ofDefault(),
                        ConnectionAcquisitionStrategy.ofDefault());

        final DefaultConnectionPool pool = new DefaultConnectionPool(
                1, strategy, null);

        // Add a busy H1 connection
        final Connection h1Conn = newH1Connection();
        pool.addConnection(h1Conn);
        h1Conn.incrementActiveRequests();

        // Should wait (at max, all full)
        final CompletableFuture<Connection> future = pool.acquire(newContext());
        assertThat(future).isNotDone();

        pool.close();
    }
}
