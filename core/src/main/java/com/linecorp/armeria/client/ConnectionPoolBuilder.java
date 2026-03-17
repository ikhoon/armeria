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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Builds a {@link ConnectionPool} with custom configuration.
 * Options that are not explicitly set will inherit the defaults from
 * the {@link ClientFactory} configuration.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * ConnectionPool pool = ConnectionPool.builder()
 *     .maxNumConnections(50)
 *     .acquisitionStrategy(ConnectionAcquisitionStrategy.ofDefault())
 *     .connectionPoolListener(ConnectionPoolListener.logging())
 *     .build();
 * }</pre>
 *
 * @see ConnectionPool
 * @see ConnectionPoolFactory
 */
@UnstableApi
public final class ConnectionPoolBuilder {

    static final int DEFAULT_MAX_NUM_CONNECTIONS = Integer.MAX_VALUE;

    private int maxNumConnections = DEFAULT_MAX_NUM_CONNECTIONS;
    @Nullable
    private ConnectionAcquisitionStrategy acquisitionStrategy;
    private long idleTimeoutMillis = -1;
    private long maxConnectionAgeMillis = -1;
    @Nullable
    private ConnectionPoolListener connectionPoolListener;

    ConnectionPoolBuilder() {}

    /**
     * Sets the maximum number of connections this pool is allowed to maintain.
     * If the pool reaches this limit and no connections are available, the
     * {@link ConnectionAcquisitionStrategy} may return {@link AcquisitionDecision#wait()}.
     *
     * <p>The default is {@link Integer#MAX_VALUE} (no limit).
     *
     * @param maxNumConnections the maximum number of connections
     * @throws IllegalArgumentException if {@code maxNumConnections} is not positive
     */
    public ConnectionPoolBuilder maxNumConnections(int maxNumConnections) {
        checkArgument(maxNumConnections > 0,
                      "maxNumConnections: %s (expected: > 0)", maxNumConnections);
        this.maxNumConnections = maxNumConnections;
        return this;
    }

    /**
     * Sets the {@link ConnectionAcquisitionStrategy} that determines how connections are
     * selected from the pool. If not set, {@link ConnectionAcquisitionStrategy#ofDefault()} is used.
     *
     * @param acquisitionStrategy the strategy to use
     * @see ConnectionAcquisitionStrategy#ofDefault()
     * @see ConnectionAcquisitionStrategy#fromLoadBalancer(com.linecorp.armeria.common.loadbalancer.LoadBalancer)
     */
    public ConnectionPoolBuilder acquisitionStrategy(ConnectionAcquisitionStrategy acquisitionStrategy) {
        this.acquisitionStrategy = requireNonNull(acquisitionStrategy, "acquisitionStrategy");
        return this;
    }

    /**
     * Sets the idle timeout for connections in this pool. A connection that has been idle
     * (no active requests) for longer than this duration may be closed.
     *
     * <p>If not set, inherits the value from the {@link ClientFactory} configuration.
     *
     * @param idleTimeout the idle timeout
     * @throws IllegalArgumentException if {@code idleTimeout} is negative
     */
    public ConnectionPoolBuilder idleTimeout(Duration idleTimeout) {
        return idleTimeoutMillis(requireNonNull(idleTimeout, "idleTimeout").toMillis());
    }

    /**
     * Sets the idle timeout in milliseconds.
     *
     * @param idleTimeoutMillis the idle timeout in milliseconds. {@code 0} disables the timeout.
     * @throws IllegalArgumentException if {@code idleTimeoutMillis} is negative
     * @see #idleTimeout(Duration)
     */
    public ConnectionPoolBuilder idleTimeoutMillis(long idleTimeoutMillis) {
        checkArgument(idleTimeoutMillis >= 0,
                      "idleTimeoutMillis: %s (expected: >= 0)", idleTimeoutMillis);
        this.idleTimeoutMillis = idleTimeoutMillis;
        return this;
    }

    /**
     * Sets the maximum age of connections in this pool. A connection older than this
     * duration (since establishment) may be closed gracefully.
     *
     * <p>If not set, inherits the value from the {@link ClientFactory} configuration.
     *
     * @param maxConnectionAge the maximum connection age. {@code Duration.ZERO} disables the limit.
     * @throws IllegalArgumentException if {@code maxConnectionAge} is negative
     */
    public ConnectionPoolBuilder maxConnectionAge(Duration maxConnectionAge) {
        return maxConnectionAgeMillis(
                requireNonNull(maxConnectionAge, "maxConnectionAge").toMillis());
    }

    /**
     * Sets the maximum connection age in milliseconds.
     *
     * @param maxConnectionAgeMillis the maximum connection age in milliseconds.
     *                               {@code 0} disables the limit.
     * @throws IllegalArgumentException if {@code maxConnectionAgeMillis} is negative
     * @see #maxConnectionAge(Duration)
     */
    public ConnectionPoolBuilder maxConnectionAgeMillis(long maxConnectionAgeMillis) {
        checkArgument(maxConnectionAgeMillis >= 0,
                      "maxConnectionAgeMillis: %s (expected: >= 0)", maxConnectionAgeMillis);
        this.maxConnectionAgeMillis = maxConnectionAgeMillis;
        return this;
    }

    /**
     * Sets the {@link ConnectionPoolListener} for this pool. If not set, inherits the listener
     * from the {@link ClientFactory} configuration.
     *
     * @param connectionPoolListener the listener to notify of pool events
     */
    public ConnectionPoolBuilder connectionPoolListener(ConnectionPoolListener connectionPoolListener) {
        this.connectionPoolListener = requireNonNull(connectionPoolListener, "connectionPoolListener");
        return this;
    }

    // Accessors for internal use

    int maxNumConnections() {
        return maxNumConnections;
    }

    @Nullable
    ConnectionAcquisitionStrategy acquisitionStrategy() {
        return acquisitionStrategy;
    }

    long idleTimeoutMillis() {
        return idleTimeoutMillis;
    }

    long maxConnectionAgeMillis() {
        return maxConnectionAgeMillis;
    }

    @Nullable
    ConnectionPoolListener connectionPoolListener() {
        return connectionPoolListener;
    }
}
