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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

/**
 * Default implementation of {@link ConnectionPool}.
 *
 * <p>This pool uses a {@link ConnectionAcquisitionStrategy} to decide how to acquire connections
 * and manages the lifecycle of {@link Connection}s including creation, release, and cleanup.
 *
 * <p>Concurrency: All state mutations are guarded by a {@link ReentrantShortLock}. The lock is
 * held only for in-memory operations (microsecond-level). Connection creation (TCP connect,
 * TLS handshake, protocol negotiation) happens asynchronously outside the lock.
 */
final class DefaultConnectionPool implements ConnectionPool {

    private static final Logger logger = LoggerFactory.getLogger(DefaultConnectionPool.class);

    private final int maxNumConnections;
    private final ConnectionAcquisitionStrategy strategy;
    @Nullable
    private final ConnectionPoolListener listener;

    private final ReentrantLock lock = new ReentrantShortLock();
    private final List<Connection> connections = new ArrayList<>();
    private final Deque<PendingAcquisition> waitQueue = new ArrayDeque<>();
    private int pendingConnectionCount;
    private boolean closed;

    DefaultConnectionPool(int maxNumConnections,
                          ConnectionAcquisitionStrategy strategy,
                          @Nullable ConnectionPoolListener listener) {
        this.maxNumConnections = maxNumConnections;
        this.strategy = strategy;
        this.listener = listener;
    }

    // --- ConnectionPool state query methods ---

    @Override
    public List<Connection> connections() {
        lock.lock();
        try {
            return ImmutableList.copyOf(connections);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Connection> availableConnections() {
        lock.lock();
        try {
            final List<Connection> available = new ArrayList<>();
            for (int i = 0; i < connections.size(); i++) {
                final Connection c = connections.get(i);
                if (c.isAvailable()) {
                    available.add(c);
                }
            }
            return Collections.unmodifiableList(available);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int numConnections() {
        lock.lock();
        try {
            return connections.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int maxNumConnections() {
        return maxNumConnections;
    }

    @Override
    public int pendingConnectionCount() {
        lock.lock();
        try {
            return pendingConnectionCount;
        } finally {
            lock.unlock();
        }
    }

    // --- Operations ---

    @Override
    public CompletableFuture<Connection> acquire(ClientRequestContext ctx) {
        lock.lock();
        try {
            if (closed) {
                return UnmodifiableFuture.exceptionallyCompletedFuture(
                        new IllegalStateException("ConnectionPool is closed"));
            }

            final AcquisitionDecision decision = strategy.acquire(ctx, this);

            switch (decision.type()) {
                case USE_EXISTING: {
                    final Connection conn = decision.connection();
                    conn.incrementActiveRequests();
                    return CompletableFuture.completedFuture(conn);
                }
                case CREATE_NEW: {
                    pendingConnectionCount++;
                    final CompletableFuture<Connection> future = new CompletableFuture<>();
                    // The actual connection creation will be handled by the caller
                    // (ConnectionPoolGroup / HttpClientDelegate) which has access to
                    // the ConnectionFactory (Bootstraps, SSL, etc.)
                    // We return the future and the caller completes it via addConnection().
                    waitQueue.addLast(new PendingAcquisition(ctx, future, true));
                    return future;
                }
                case WAIT: {
                    final CompletableFuture<Connection> future = new CompletableFuture<>();
                    waitQueue.addLast(new PendingAcquisition(ctx, future, false));
                    return future;
                }
                default:
                    return UnmodifiableFuture.exceptionallyCompletedFuture(
                            new IllegalStateException("Unknown decision type: " + decision.type()));
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Adds a newly created connection to this pool after a successful CREATE_NEW
     * and notifies waiting callers.
     */
    void addConnection(Connection connection) {
        lock.lock();
        try {
            connections.add(connection);
            if (pendingConnectionCount > 0) {
                pendingConnectionCount--;
            }
            notifyWaiters(connection);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called when a connection creation fails.
     */
    void connectionFailed(Throwable cause) {
        lock.lock();
        try {
            pendingConnectionCount--;
            // Fail the first CREATE_NEW waiter
            final PendingAcquisition waiter = pollCreateNewWaiter();
            if (waiter != null) {
                waiter.future.completeExceptionally(cause);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void release(Connection connection) {
        lock.lock();
        try {
            connection.decrementActiveRequests();
            // Try to hand this connection to a waiting request
            notifyWaiters(null);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes a connection from this pool (e.g., when it's closed or becomes unhealthy).
     */
    void removeConnection(Connection connection) {
        lock.lock();
        try {
            connections.remove(connection);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Notifies waiting callers that a connection may be available.
     * If {@code newConnection} is non-null, it's a freshly created connection that can be
     * immediately assigned to the first CREATE_NEW waiter.
     */
    private void notifyWaiters(@Nullable Connection newConnection) {
        // First, serve the CREATE_NEW waiter with the new connection if available.
        if (newConnection != null) {
            final PendingAcquisition createWaiter = pollCreateNewWaiter();
            if (createWaiter != null) {
                newConnection.incrementActiveRequests();
                createWaiter.future.complete(newConnection);
            }
        }

        // Then, try to serve WAIT waiters by re-running the strategy.
        while (!waitQueue.isEmpty()) {
            final PendingAcquisition waiter = waitQueue.peekFirst();
            if (waiter == null || waiter.isCreateNew) {
                break; // Only serve WAIT waiters here
            }

            // Re-evaluate whether there's now an available connection
            final List<Connection> available = availableConnectionsNoLock();
            Connection leastLoaded = null;
            int minActive = Integer.MAX_VALUE;
            for (int i = 0; i < available.size(); i++) {
                final Connection c = available.get(i);
                if (c.activeRequests() < minActive) {
                    minActive = c.activeRequests();
                    leastLoaded = c;
                }
            }

            if (leastLoaded != null) {
                waitQueue.pollFirst();
                leastLoaded.incrementActiveRequests();
                waiter.future.complete(leastLoaded);
            } else {
                break; // No available connection for waiters
            }
        }
    }

    @Nullable
    private PendingAcquisition pollCreateNewWaiter() {
        final java.util.Iterator<PendingAcquisition> it = waitQueue.iterator();
        while (it.hasNext()) {
            final PendingAcquisition waiter = it.next();
            if (waiter.isCreateNew) {
                it.remove();
                return waiter;
            }
        }
        return null;
    }

    private List<Connection> availableConnectionsNoLock() {
        final List<Connection> available = new ArrayList<>();
        for (int i = 0; i < connections.size(); i++) {
            final Connection c = connections.get(i);
            if (c.isAvailable()) {
                available.add(c);
            }
        }
        return available;
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;

            // Fail all pending waiters
            final IllegalStateException closedException =
                    new IllegalStateException("ConnectionPool has been closed");
            PendingAcquisition waiter;
            while ((waiter = waitQueue.pollFirst()) != null) {
                waiter.future.completeExceptionally(closedException);
            }

            // Close all connections
            for (int i = 0; i < connections.size(); i++) {
                final Connection conn = connections.get(i);
                conn.channel().close();
            }
            connections.clear();
        } finally {
            lock.unlock();
        }
    }

    @VisibleForTesting
    int waitQueueSize() {
        lock.lock();
        try {
            return waitQueue.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        lock.lock();
        try {
            return "DefaultConnectionPool{" +
                   "connections=" + connections.size() +
                   ", maxNumConnections=" + maxNumConnections +
                   ", pendingConnections=" + pendingConnectionCount +
                   ", waitQueue=" + waitQueue.size() +
                   '}';
        } finally {
            lock.unlock();
        }
    }

    private static final class PendingAcquisition {
        final ClientRequestContext ctx;
        final CompletableFuture<Connection> future;
        final boolean isCreateNew;

        PendingAcquisition(ClientRequestContext ctx, CompletableFuture<Connection> future,
                           boolean isCreateNew) {
            this.ctx = ctx;
            this.future = future;
            this.isCreateNew = isCreateNew;
        }
    }
}
