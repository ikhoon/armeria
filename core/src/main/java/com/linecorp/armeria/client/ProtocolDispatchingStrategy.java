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

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.linecorp.armeria.common.SessionProtocol;

/**
 * A {@link ConnectionAcquisitionStrategy} that dispatches to per-protocol strategies.
 *
 * <p>This strategy partitions the pool's connections by protocol and delegates to
 * the appropriate per-protocol strategy. The view presented to each sub-strategy
 * contains only connections of the relevant protocol type.
 */
final class ProtocolDispatchingStrategy implements ConnectionAcquisitionStrategy {

    private final ConnectionAcquisitionStrategy http1Strategy;
    private final ConnectionAcquisitionStrategy http2Strategy;
    private final ConnectionAcquisitionStrategy fallbackStrategy;

    ProtocolDispatchingStrategy(ConnectionAcquisitionStrategy http1Strategy,
                                ConnectionAcquisitionStrategy http2Strategy,
                                ConnectionAcquisitionStrategy fallbackStrategy) {
        this.http1Strategy = requireNonNull(http1Strategy, "http1Strategy");
        this.http2Strategy = requireNonNull(http2Strategy, "http2Strategy");
        this.fallbackStrategy = requireNonNull(fallbackStrategy, "fallbackStrategy");
    }

    @Override
    public AcquisitionDecision acquire(ClientRequestContext ctx, ConnectionPool pool) {
        // Try H2 connections first (preferred for new connections)
        final List<Connection> h2Connections = filterByProtocol(pool.availableConnections(), true);
        if (!h2Connections.isEmpty()) {
            final ConnectionPool h2View = new FilteredConnectionPoolView(pool, true);
            final AcquisitionDecision decision = http2Strategy.acquire(ctx, h2View);
            if (decision.type() == AcquisitionDecision.Type.USE_EXISTING) {
                return decision;
            }
        }

        // Try H1 connections
        final List<Connection> h1Connections = filterByProtocol(pool.availableConnections(), false);
        if (!h1Connections.isEmpty()) {
            final ConnectionPool h1View = new FilteredConnectionPoolView(pool, false);
            final AcquisitionDecision decision = http1Strategy.acquire(ctx, h1View);
            if (decision.type() == AcquisitionDecision.Type.USE_EXISTING) {
                return decision;
            }
        }

        // No existing connection found. Decide whether to create new or wait.
        if (pool.numConnections() + pool.pendingConnectionCount() < pool.maxNumConnections()) {
            return AcquisitionDecision.createNew();
        }
        return AcquisitionDecision.pendingWait();
    }

    private static List<Connection> filterByProtocol(List<Connection> connections, boolean h2) {
        final List<Connection> result = new ArrayList<>();
        for (int i = 0; i < connections.size(); i++) {
            final Connection c = connections.get(i);
            if (isHttp2(c.protocol()) == h2) {
                result.add(c);
            }
        }
        return result;
    }

    private static boolean isHttp2(SessionProtocol protocol) {
        return protocol == SessionProtocol.H2 || protocol == SessionProtocol.H2C;
    }

    @Override
    public String toString() {
        return "ProtocolDispatchingStrategy{" +
               "http1=" + http1Strategy +
               ", http2=" + http2Strategy +
               '}';
    }

    /**
     * A read-only view of a {@link ConnectionPool} filtered to only show connections
     * of a specific protocol type (H1 or H2).
     */
    private static final class FilteredConnectionPoolView implements ConnectionPool {
        private final ConnectionPool delegate;
        private final boolean h2;

        FilteredConnectionPoolView(ConnectionPool delegate, boolean h2) {
            this.delegate = delegate;
            this.h2 = h2;
        }

        @Override
        public List<Connection> connections() {
            return filterByProtocol(delegate.connections(), h2);
        }

        @Override
        public List<Connection> availableConnections() {
            return filterByProtocol(delegate.availableConnections(), h2);
        }

        @Override
        public int numConnections() {
            return filterByProtocol(delegate.connections(), h2).size();
        }

        @Override
        public int maxNumConnections() {
            return delegate.maxNumConnections();
        }

        @Override
        public int pendingConnectionCount() {
            return delegate.pendingConnectionCount();
        }

        @Override
        public java.util.concurrent.CompletableFuture<Connection> acquire(ClientRequestContext ctx) {
            return delegate.acquire(ctx);
        }

        @Override
        public void release(Connection connection) {
            delegate.release(connection);
        }

        @Override
        public void close() {
            // Do not close the delegate - this is a view
        }
    }
}
