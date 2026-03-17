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

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Represents the result of a {@link ConnectionAcquisitionStrategy#acquire(ClientRequestContext, ConnectionPool)}
 * invocation. There are three possible outcomes:
 * <ul>
 *   <li>{@link #useExisting(Connection)} - Reuse an existing connection from the pool.</li>
 *   <li>{@link #createNew()} - Create a new connection.</li>
 *   <li>{@link #pendingWait()} - Wait for a connection to become available.</li>
 * </ul>
 */
@UnstableApi
public abstract class AcquisitionDecision {

    private static final AcquisitionDecision CREATE_NEW = new CreateNew();
    private static final AcquisitionDecision WAIT = new Wait();

    /**
     * Returns a new {@link AcquisitionDecision} that indicates the given existing {@link Connection}
     * should be reused.
     */
    public static AcquisitionDecision useExisting(Connection connection) {
        requireNonNull(connection, "connection");
        return new UseExisting(connection);
    }

    /**
     * Returns the {@link AcquisitionDecision} that indicates a new connection should be created.
     */
    public static AcquisitionDecision createNew() {
        return CREATE_NEW;
    }

    /**
     * Returns the {@link AcquisitionDecision} that indicates the caller should wait until
     * an existing connection becomes available.
     * This is typically returned when the pool has reached its maximum number of connections.
     */
    public static AcquisitionDecision pendingWait() {
        return WAIT;
    }

    // Prevent external subclassing.
    private AcquisitionDecision() {}

    /**
     * Returns the type of this decision.
     */
    public abstract Type type();

    /**
     * Returns the {@link Connection} to reuse. This method is only valid when
     * {@link #type()} returns {@link Type#USE_EXISTING}.
     *
     * @throws IllegalStateException if the decision type is not {@link Type#USE_EXISTING}
     */
    public Connection connection() {
        throw new IllegalStateException(
                "connection() is only valid for USE_EXISTING decisions, but was: " + type());
    }

    @Override
    public String toString() {
        return "AcquisitionDecision(" + type() + ')';
    }

    /**
     * The type of {@link AcquisitionDecision}.
     */
    public enum Type {
        /**
         * Reuse an existing connection from the pool.
         */
        USE_EXISTING,
        /**
         * Create a new connection.
         */
        CREATE_NEW,
        /**
         * Wait for a connection to become available.
         */
        WAIT
    }

    private static final class UseExisting extends AcquisitionDecision {
        private final Connection connection;

        UseExisting(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Type type() {
            return Type.USE_EXISTING;
        }

        @Override
        public Connection connection() {
            return connection;
        }

        @Override
        public String toString() {
            return "AcquisitionDecision(USE_EXISTING, " + connection + ')';
        }
    }

    private static final class CreateNew extends AcquisitionDecision {
        @Override
        public Type type() {
            return Type.CREATE_NEW;
        }
    }

    private static final class Wait extends AcquisitionDecision {
        @Override
        public Type type() {
            return Type.WAIT;
        }
    }
}
