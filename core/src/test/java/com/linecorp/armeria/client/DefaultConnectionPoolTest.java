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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.embedded.EmbeddedChannel;

class DefaultConnectionPoolTest {

    private DefaultConnectionPool pool;

    @BeforeEach
    void setUp() {
        pool = new DefaultConnectionPool(
                SessionProtocol.H2,
                3, // maxNumConnections
                ConnectionAcquisitionStrategy.ofDefault(),
                null // no listener
        );
    }

    @AfterEach
    void tearDown() {
        pool.close();
    }

    private static Connection newH2Connection() {
        return new Connection(new EmbeddedChannel(), SessionProtocol.H2, 100);
    }

    private static Connection newH1Connection() {
        return new Connection(new EmbeddedChannel(), SessionProtocol.H1, 1);
    }

    private static ClientRequestContext newContext() {
        return ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                   .build();
    }

    @Test
    void initialState() {
        assertThat(pool.numConnections()).isZero();
        assertThat(pool.connections()).isEmpty();
        assertThat(pool.availableConnections()).isEmpty();
        assertThat(pool.maxNumConnections()).isEqualTo(3);
        assertThat(pool.pendingConnectionCount()).isZero();
        assertThat(pool.protocol()).isEqualTo(SessionProtocol.H2);
    }

    @Test
    void acquireCreatesNewWhenEmpty() {
        final CompletableFuture<Connection> future = pool.acquire(newContext());

        // Should be waiting for a new connection (CREATE_NEW decision)
        assertThat(future).isNotDone();
        assertThat(pool.pendingConnectionCount()).isEqualTo(1);

        // Simulate connection creation
        final Connection conn = newH2Connection();
        pool.addConnection(conn);

        assertThat(future).isDone();
        assertThat(future.join()).isSameAs(conn);
        assertThat(conn.activeRequests()).isEqualTo(1);
        assertThat(pool.numConnections()).isEqualTo(1);
    }

    @Test
    void acquireReusesExistingH2Connection() {
        // Add a connection with available capacity
        final Connection conn = newH2Connection();
        pool.addConnection(conn);

        // First request should not have created pending since we just manually added
        // Let's directly test by acquiring
        final CompletableFuture<Connection> future = pool.acquire(newContext());

        assertThat(future).isDone();
        assertThat(future.join()).isSameAs(conn);
        assertThat(conn.activeRequests()).isEqualTo(1);
    }

    @Test
    void acquireSelectsLeastLoaded() {
        final Connection conn1 = newH2Connection();
        final Connection conn2 = newH2Connection();
        pool.addConnection(conn1);
        pool.addConnection(conn2);

        // Add some load to conn1
        conn1.incrementActiveRequests();
        conn1.incrementActiveRequests();

        // Should select conn2 (least loaded, 0 active requests)
        final CompletableFuture<Connection> future = pool.acquire(newContext());
        assertThat(future).isDone();
        assertThat(future.join()).isSameAs(conn2);
    }

    @Test
    void acquireWaitsWhenAtMaxConnections() {
        // Fill up the pool
        final Connection conn1 = newH1Connection();
        final Connection conn2 = newH1Connection();
        final Connection conn3 = newH1Connection();
        pool.addConnection(conn1);
        pool.addConnection(conn2);
        pool.addConnection(conn3);

        // Make all connections busy (H1 has max 1 concurrent request)
        conn1.incrementActiveRequests();
        conn2.incrementActiveRequests();
        conn3.incrementActiveRequests();

        // Should wait because all at max capacity and at connection limit
        final CompletableFuture<Connection> future = pool.acquire(newContext());
        assertThat(future).isNotDone();

        // Release conn2
        pool.release(conn2);

        // The waiter should now be satisfied
        assertThat(future).isDone();
        assertThat(future.join()).isSameAs(conn2);
    }

    @Test
    void releaseNotifiesWaiters() {
        final Connection conn = newH1Connection();
        pool.addConnection(conn);
        conn.incrementActiveRequests();

        // Pool has 1 connection at capacity, at max 3 connections though.
        // With only 1 connection and max 3, strategy should create new.
        // But let's simulate full pool scenario:
        final DefaultConnectionPool fullPool = new DefaultConnectionPool(
                SessionProtocol.H1, 1,
                ConnectionAcquisitionStrategy.ofDefault(), null);
        final Connection c = newH1Connection();
        fullPool.addConnection(c);
        c.incrementActiveRequests();

        // Now acquire should wait
        final CompletableFuture<Connection> future = fullPool.acquire(newContext());
        assertThat(future).isNotDone();

        // Release
        fullPool.release(c);
        assertThat(future).isDone();
        assertThat(future.join()).isSameAs(c);

        fullPool.close();
    }

    @Test
    void removeConnection() {
        final Connection conn = newH2Connection();
        pool.addConnection(conn);
        assertThat(pool.numConnections()).isEqualTo(1);

        pool.removeConnection(conn);
        assertThat(pool.numConnections()).isZero();
    }

    @Test
    void connectionFailed() {
        // Trigger a CREATE_NEW
        final CompletableFuture<Connection> future = pool.acquire(newContext());
        assertThat(future).isNotDone();
        assertThat(pool.pendingConnectionCount()).isEqualTo(1);

        // Simulate failure
        pool.connectionFailed(new RuntimeException("connect failed"));
        assertThat(pool.pendingConnectionCount()).isZero();
        assertThat(future).isCompletedExceptionally();
    }

    @Test
    void closeFailsWaiters() {
        final CompletableFuture<Connection> future = pool.acquire(newContext());
        assertThat(future).isNotDone();

        pool.close();

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void acquireAfterClose() {
        pool.close();

        final CompletableFuture<Connection> future = pool.acquire(newContext());
        assertThat(future).isCompletedExceptionally();
    }

    @Test
    void closedPoolIsIdempotent() {
        pool.close();
        pool.close(); // Should not throw
    }

    @Test
    void h2Multiplexing() {
        // H2 pool with max 1 connection but high concurrency
        final DefaultConnectionPool h2Pool = new DefaultConnectionPool(
                SessionProtocol.H2, 1,
                ConnectionAcquisitionStrategy.ofDefault(), null);

        final Connection conn = new Connection(new EmbeddedChannel(), SessionProtocol.H2, 100);
        h2Pool.addConnection(conn);

        // Should reuse the same connection for multiple requests
        final CompletableFuture<Connection> f1 = h2Pool.acquire(newContext());
        final CompletableFuture<Connection> f2 = h2Pool.acquire(newContext());
        final CompletableFuture<Connection> f3 = h2Pool.acquire(newContext());

        assertThat(f1.join()).isSameAs(conn);
        assertThat(f2.join()).isSameAs(conn);
        assertThat(f3.join()).isSameAs(conn);
        assertThat(conn.activeRequests()).isEqualTo(3);

        h2Pool.close();
    }

    @Test
    void customStrategy() {
        // Custom strategy that always creates new connections
        final ConnectionAcquisitionStrategy alwaysNew = (ctx, p) -> {
            if (p.numConnections() + p.pendingConnectionCount() < p.maxNumConnections()) {
                return AcquisitionDecision.createNew();
            }
            return AcquisitionDecision.pendingWait();
        };

        final DefaultConnectionPool customPool = new DefaultConnectionPool(
                SessionProtocol.H2, 2, alwaysNew, null);

        final Connection conn1 = newH2Connection();
        customPool.addConnection(conn1);

        // Even though conn1 is available, the custom strategy should request a new connection
        final CompletableFuture<Connection> future = customPool.acquire(newContext());
        assertThat(future).isNotDone();
        assertThat(customPool.pendingConnectionCount()).isEqualTo(1);

        // Add the new connection
        final Connection conn2 = newH2Connection();
        customPool.addConnection(conn2);
        assertThat(future).isDone();
        assertThat(future.join()).isSameAs(conn2);

        customPool.close();
    }
}
