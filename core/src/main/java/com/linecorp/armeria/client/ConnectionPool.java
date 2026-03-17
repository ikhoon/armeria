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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SafeCloseable;

/**
 * A pool of {@link Connection}s for a specific endpoint.
 * Each pool manages connections to a single destination, identified by a combination of
 * {@link Endpoint}, proxy configuration, and TLS settings.
 *
 * <p>A single pool may contain connections with different protocols (e.g., HTTP/1 and HTTP/2).
 * The protocol is determined during connection establishment and is a property of each
 * individual {@link Connection}.
 *
 * <p>The pool delegates connection selection logic to a {@link ConnectionAcquisitionStrategy},
 * which can be customized via {@link ConnectionPoolBuilder#http1AcquisitionStrategy(ConnectionAcquisitionStrategy)}
 * and {@link ConnectionPoolBuilder#http2AcquisitionStrategy(ConnectionAcquisitionStrategy)}.
 *
 * <h2>State query methods</h2>
 * <p>This interface exposes read-only state that {@link ConnectionAcquisitionStrategy} implementations
 * can use to make decisions:
 * <ul>
 *   <li>{@link #connections()} - All connections in this pool</li>
 *   <li>{@link #availableConnections()} - Connections that can accept more requests</li>
 *   <li>{@link #numConnections()} - Current number of connections</li>
 *   <li>{@link #maxNumConnections()} - Maximum number of connections allowed</li>
 *   <li>{@link #pendingConnectionCount()} - Connections currently being created</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ConnectionPool pool = ConnectionPool.builder()
 *     .maxNumConnections(50)
 *     .http2AcquisitionStrategy(myHttp2Strategy)
 *     .build();
 * }</pre>
 *
 * @see ConnectionAcquisitionStrategy
 * @see ConnectionPoolBuilder
 * @see ConnectionPoolFactory
 */
@UnstableApi
public interface ConnectionPool extends SafeCloseable {

    /**
     * Returns a new {@link ConnectionPoolBuilder}.
     */
    static ConnectionPoolBuilder builder() {
        return new ConnectionPoolBuilder();
    }

    // --- State query methods (used by ConnectionAcquisitionStrategy) ---

    /**
     * Returns all {@link Connection}s currently managed by this pool, including both
     * available and busy connections. The returned list is a snapshot and may not reflect
     * concurrent modifications.
     */
    List<Connection> connections();

    /**
     * Returns the {@link Connection}s that are currently available to accept new requests.
     * A connection is available when it is {@linkplain Connection#isHealthy() healthy} and
     * its {@linkplain Connection#activeRequests() active request count} is below
     * {@linkplain Connection#maxConcurrentRequests() its maximum}.
     *
     * <p>The returned list is a snapshot and may not reflect concurrent modifications.
     */
    List<Connection> availableConnections();

    /**
     * Returns the total number of connections currently managed by this pool.
     */
    int numConnections();

    /**
     * Returns the maximum number of connections this pool is allowed to maintain.
     */
    int maxNumConnections();

    /**
     * Returns the number of connections that are currently being established (in-progress).
     * This is useful for {@link ConnectionAcquisitionStrategy} to consider pending connections
     * when deciding whether to create a new one.
     */
    int pendingConnectionCount();

    // --- Operations ---

    /**
     * Acquires a {@link Connection} from this pool. The returned {@link CompletableFuture}
     * is completed with a {@link Connection} when one becomes available.
     *
     * <p>Internally, this method delegates to the configured {@link ConnectionAcquisitionStrategy}
     * to decide whether to reuse an existing connection, create a new one, or wait.
     *
     * @param ctx the {@link ClientRequestContext} for the current request
     * @return a future that completes with the acquired {@link Connection}
     */
    CompletableFuture<Connection> acquire(ClientRequestContext ctx);

    /**
     * Releases a {@link Connection} back to this pool after a request is complete.
     * This decrements the connection's active request count and may notify waiting
     * callers that a connection has become available.
     *
     * @param connection the {@link Connection} to release
     */
    void release(Connection connection);
}
