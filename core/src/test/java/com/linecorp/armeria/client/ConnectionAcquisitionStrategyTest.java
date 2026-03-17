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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.AcquisitionDecision.Type;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.loadbalancer.LoadBalancer;

import io.netty.channel.embedded.EmbeddedChannel;

class ConnectionAcquisitionStrategyTest {

    private static ClientRequestContext newContext() {
        return ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                   .build();
    }

    private static Connection newH2Connection(int maxConcurrent) {
        return new Connection(new EmbeddedChannel(), SessionProtocol.H2, maxConcurrent);
    }

    private static Connection newH1Connection() {
        return new Connection(new EmbeddedChannel(), SessionProtocol.H1, 1);
    }

    // --- Default strategy tests ---

    @Test
    void defaultStrategy_selectsLeastLoaded() {
        final ConnectionAcquisitionStrategy strategy = ConnectionAcquisitionStrategy.ofDefault();

        final Connection conn1 = newH2Connection(100);
        final Connection conn2 = newH2Connection(100);
        conn1.incrementActiveRequests();
        conn1.incrementActiveRequests();
        // conn2 has 0 active requests

        final StubConnectionPool pool = new StubConnectionPool(
                10, com.google.common.collect.ImmutableList.of(conn1, conn2));

        final AcquisitionDecision decision = strategy.acquire(newContext(), pool);
        assertThat(decision.type()).isEqualTo(Type.USE_EXISTING);
        assertThat(decision.connection()).isSameAs(conn2);
    }

    @Test
    void defaultStrategy_createsNewWhenNoneAvailable() {
        final ConnectionAcquisitionStrategy strategy = ConnectionAcquisitionStrategy.ofDefault();

        final StubConnectionPool pool = new StubConnectionPool(
                10, Collections.emptyList());

        final AcquisitionDecision decision = strategy.acquire(newContext(), pool);
        assertThat(decision.type()).isEqualTo(Type.CREATE_NEW);
    }

    @Test
    void defaultStrategy_waitsAtMaxConnections() {
        final ConnectionAcquisitionStrategy strategy = ConnectionAcquisitionStrategy.ofDefault();

        final Connection conn = newH1Connection();
        conn.incrementActiveRequests(); // H1 is now full

        final StubConnectionPool pool = new StubConnectionPool(
                1, com.google.common.collect.ImmutableList.of(conn));

        final AcquisitionDecision decision = strategy.acquire(newContext(), pool);
        assertThat(decision.type()).isEqualTo(Type.WAIT);
    }

    @Test
    void defaultStrategy_h1SelectsIdleOnly() {
        final ConnectionAcquisitionStrategy strategy = ConnectionAcquisitionStrategy.ofDefault();

        final Connection busy = newH1Connection();
        busy.incrementActiveRequests(); // Now at max (1)

        final Connection idle = newH1Connection();
        // idle has 0 active requests

        final StubConnectionPool pool = new StubConnectionPool(
                5, com.google.common.collect.ImmutableList.of(busy, idle));

        final AcquisitionDecision decision = strategy.acquire(newContext(), pool);
        assertThat(decision.type()).isEqualTo(Type.USE_EXISTING);
        assertThat(decision.connection()).isSameAs(idle);
    }

    @Test
    void defaultStrategy_considersPendingConnections() {
        final ConnectionAcquisitionStrategy strategy = ConnectionAcquisitionStrategy.ofDefault();

        // Pool with max 2 connections, 1 existing, 1 pending
        final StubConnectionPool pool = new StubConnectionPool(
                2, Collections.emptyList());
        pool.setPendingConnectionCount(1);
        pool.setNumConnections(1);

        // Since numConnections(1) + pending(1) = 2 = max(2), should wait
        final AcquisitionDecision decision = strategy.acquire(newContext(), pool);
        assertThat(decision.type()).isEqualTo(Type.WAIT);
    }

    // --- fromLoadBalancer tests ---

    @Test
    void fromLoadBalancer_usesPick() {
        final Connection conn = newH2Connection(100);
        final LoadBalancer<Connection, ClientRequestContext> lb =
                new LoadBalancer<Connection, ClientRequestContext>() {
                    @Override
                    public Connection pick(ClientRequestContext ctx) {
                        return conn;
                    }
                };

        final ConnectionAcquisitionStrategy strategy =
                ConnectionAcquisitionStrategy.fromLoadBalancer(lb);

        final StubConnectionPool pool = new StubConnectionPool(
                10, com.google.common.collect.ImmutableList.of(conn));

        final AcquisitionDecision decision = strategy.acquire(newContext(), pool);
        assertThat(decision.type()).isEqualTo(Type.USE_EXISTING);
        assertThat(decision.connection()).isSameAs(conn);
    }

    @Test
    void fromLoadBalancer_createsNewWhenPickReturnsNull() {
        final LoadBalancer<Connection, ClientRequestContext> lb =
                new LoadBalancer<Connection, ClientRequestContext>() {
                    @Override
                    public Connection pick(ClientRequestContext ctx) {
                        return null;
                    }
                };

        final ConnectionAcquisitionStrategy strategy =
                ConnectionAcquisitionStrategy.fromLoadBalancer(lb);

        final StubConnectionPool pool = new StubConnectionPool(
                10, Collections.emptyList());

        final AcquisitionDecision decision = strategy.acquire(newContext(), pool);
        assertThat(decision.type()).isEqualTo(Type.CREATE_NEW);
    }

    @Test
    void fromLoadBalancer_waitsWhenPickReturnsNullAndAtMax() {
        final LoadBalancer<Connection, ClientRequestContext> lb =
                new LoadBalancer<Connection, ClientRequestContext>() {
                    @Override
                    public Connection pick(ClientRequestContext ctx) {
                        return null;
                    }
                };

        final ConnectionAcquisitionStrategy strategy =
                ConnectionAcquisitionStrategy.fromLoadBalancer(lb);

        final StubConnectionPool pool = new StubConnectionPool(
                1, Collections.emptyList());
        pool.setNumConnections(1);

        final AcquisitionDecision decision = strategy.acquire(newContext(), pool);
        assertThat(decision.type()).isEqualTo(Type.WAIT);
    }

    @Test
    void defaultStrategySingleton() {
        assertThat(ConnectionAcquisitionStrategy.ofDefault())
                .isSameAs(ConnectionAcquisitionStrategy.ofDefault());
    }

    /**
     * Stub implementation of ConnectionPool for unit testing strategies in isolation.
     */
    private static class StubConnectionPool implements ConnectionPool {
        private final int maxNumConnections;
        private final List<Connection> allConnections;
        private int numConnections;
        private int pendingConnectionCount;

        StubConnectionPool(int maxNumConnections, List<Connection> connections) {
            this.maxNumConnections = maxNumConnections;
            this.allConnections = new ArrayList<>(connections);
            this.numConnections = connections.size();
        }

        @Override
        public List<Connection> connections() {
            return Collections.unmodifiableList(allConnections);
        }

        @Override
        public List<Connection> availableConnections() {
            final List<Connection> available = new ArrayList<>();
            for (Connection c : allConnections) {
                if (c.isAvailable()) {
                    available.add(c);
                }
            }
            return available;
        }

        @Override
        public int numConnections() {
            return numConnections;
        }

        @Override
        public int maxNumConnections() {
            return maxNumConnections;
        }

        @Override
        public int pendingConnectionCount() {
            return pendingConnectionCount;
        }

        @Override
        public CompletableFuture<Connection> acquire(ClientRequestContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void release(Connection connection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {}

        void setPendingConnectionCount(int count) {
            this.pendingConnectionCount = count;
        }

        void setNumConnections(int count) {
            this.numConnections = count;
        }
    }
}
