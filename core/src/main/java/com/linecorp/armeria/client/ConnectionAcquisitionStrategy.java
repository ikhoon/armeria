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

import java.util.List;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.loadbalancer.LoadBalancer;

/**
 * A strategy for acquiring a connection from a {@link ConnectionPool}.
 * This is the core extension point that determines how connections are selected, when new ones
 * are created, and when callers should wait.
 *
 * <h2>Built-in strategies</h2>
 * <ul>
 *   <li>{@link #ofDefault()} - A protocol-agnostic strategy that selects the least-loaded
 *       available connection. Works for both HTTP/1 and HTTP/2 by using
 *       {@link Connection#maxConcurrentRequests()} to abstract protocol differences.</li>
 * </ul>
 *
 * <h2>Custom strategy example</h2>
 * <pre>{@code
 * // Sticky session strategy
 * AttributeKey<String> SESSION_KEY = AttributeKey.valueOf("sessionId");
 *
 * ConnectionAcquisitionStrategy sticky = (ctx, pool) -> {
 *     String sessionId = ctx.attr(SESSION_KEY);
 *     if (sessionId != null) {
 *         for (Connection c : pool.availableConnections()) {
 *             if (sessionId.equals(c.attrs().attr(SESSION_KEY).get())) {
 *                 return AcquisitionDecision.useExisting(c);
 *             }
 *         }
 *     }
 *     // Fallback to default strategy.
 *     return ConnectionAcquisitionStrategy.ofDefault().acquire(ctx, pool);
 * };
 * }</pre>
 *
 * <h2>LoadBalancer adapter</h2>
 * <p>If you have an existing {@link LoadBalancer}, you can wrap it using
 * {@link #fromLoadBalancer(LoadBalancer)}.</p>
 *
 * @see AcquisitionDecision
 * @see ConnectionPool
 */
@UnstableApi
@FunctionalInterface
public interface ConnectionAcquisitionStrategy {

    /**
     * Returns the default protocol-agnostic strategy that selects the least-loaded available connection.
     *
     * <p>This strategy uses {@link Connection#maxConcurrentRequests()} to automatically handle both
     * HTTP/1 ({@code maxConcurrentRequests=1}) and HTTP/2 ({@code maxConcurrentRequests=N}) correctly:
     * <ul>
     *   <li>HTTP/1: Only idle connections (0 active requests) are available, so this effectively
     *       selects an idle connection.</li>
     *   <li>HTTP/2: All connections with available capacity are candidates, and the one with the
     *       fewest active requests is selected.</li>
     * </ul>
     */
    static ConnectionAcquisitionStrategy ofDefault() {
        return DefaultConnectionAcquisitionStrategy.INSTANCE;
    }

    /**
     * Returns a {@link ConnectionAcquisitionStrategy} that wraps an existing {@link LoadBalancer}.
     *
     * <p>When the {@link LoadBalancer} returns a non-null {@link Connection}, it is used as-is.
     * When it returns {@code null}, a new connection is created if the pool has capacity;
     * otherwise the caller waits.
     *
     * @param loadBalancer the {@link LoadBalancer} to use for selecting connections
     */
    static ConnectionAcquisitionStrategy fromLoadBalancer(
            LoadBalancer<Connection, ClientRequestContext> loadBalancer) {
        requireNonNull(loadBalancer, "loadBalancer");
        return (ctx, pool) -> {
            final Connection picked = loadBalancer.pick(ctx);
            if (picked != null) {
                return AcquisitionDecision.useExisting(picked);
            }
            if (pool.numConnections() + pool.pendingConnectionCount() < pool.maxNumConnections()) {
                return AcquisitionDecision.createNew();
            }
            return AcquisitionDecision.pendingWait();
        };
    }

    /**
     * Determines how to acquire a connection from the given {@link ConnectionPool}.
     *
     * <p>The returned {@link AcquisitionDecision} tells the pool one of three things:
     * <ul>
     *   <li>{@link AcquisitionDecision#useExisting(Connection)} - Reuse the given connection.</li>
     *   <li>{@link AcquisitionDecision#createNew()} - Create a new connection.</li>
     *   <li>{@link AcquisitionDecision#pendingWait()} - Wait for a connection to become available.</li>
     * </ul>
     *
     * <p><strong>Implementation note:</strong> This method is called while holding a pool lock.
     * Implementations must not perform blocking I/O and should have O(n) or better time complexity
     * where n is the number of connections in the pool.
     *
     * @param ctx  the {@link ClientRequestContext} for the current request
     * @param pool the {@link ConnectionPool} that provides read-only state about current connections
     * @return the {@link AcquisitionDecision} indicating how to proceed
     */
    AcquisitionDecision acquire(ClientRequestContext ctx, ConnectionPool pool);
}
