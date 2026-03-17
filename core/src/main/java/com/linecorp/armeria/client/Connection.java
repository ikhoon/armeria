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

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.client.HttpSession;

import io.netty.channel.Channel;
import io.netty.util.AttributeMap;

/**
 * Represents a connection in a {@link ConnectionPool}.
 * A {@link Connection} wraps a Netty {@link Channel} and its associated {@link HttpSession},
 * providing a unified view of connection state for use by {@link ConnectionAcquisitionStrategy}.
 *
 * <p>This class exposes read-only state information about the connection, such as the number
 * of active requests, health status, and protocol information.
 */
@UnstableApi
public final class Connection {

    private final Channel channel;
    private final SessionProtocol protocol;
    private final int maxConcurrentRequests;
    private int activeRequests;
    private long lastActivityTimeNanos;

    /**
     * Creates a new {@link Connection}.
     *
     * @param channel              the Netty {@link Channel} for this connection
     * @param protocol             the negotiated {@link SessionProtocol} (one of H1, H1C, H2, H2C)
     * @param maxConcurrentRequests the maximum number of concurrent requests this connection supports.
     *                              HTTP/1 connections have a max of 1; HTTP/2 connections use the
     *                              server's MAX_CONCURRENT_STREAMS setting.
     */
    Connection(Channel channel, SessionProtocol protocol, int maxConcurrentRequests) {
        this.channel = requireNonNull(channel, "channel");
        this.protocol = requireNonNull(protocol, "protocol");
        this.maxConcurrentRequests = maxConcurrentRequests;
        lastActivityTimeNanos = System.nanoTime();
    }

    /**
     * Returns the Netty {@link Channel} associated with this connection.
     */
    public Channel channel() {
        return channel;
    }

    /**
     * Returns the negotiated {@link SessionProtocol} for this connection.
     * This is always an explicit protocol (one of {@link SessionProtocol#H1},
     * {@link SessionProtocol#H1C}, {@link SessionProtocol#H2}, {@link SessionProtocol#H2C}).
     */
    public SessionProtocol protocol() {
        return protocol;
    }

    /**
     * Returns the number of currently active (in-flight) requests on this connection.
     */
    public int activeRequests() {
        return activeRequests;
    }

    /**
     * Returns the maximum number of concurrent requests this connection can handle.
     * <ul>
     *   <li>HTTP/1: Always 1 (no multiplexing).</li>
     *   <li>HTTP/2: The server's {@code MAX_CONCURRENT_STREAMS} value.</li>
     * </ul>
     */
    public int maxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    /**
     * Returns {@code true} if this connection can accept more requests.
     * A connection is available when:
     * <ul>
     *   <li>It is {@linkplain #isHealthy() healthy}, and</li>
     *   <li>{@link #activeRequests()} is less than {@link #maxConcurrentRequests()}</li>
     * </ul>
     */
    public boolean isAvailable() {
        return activeRequests < maxConcurrentRequests && isHealthy();
    }

    /**
     * Returns {@code true} if the underlying channel is active and the session is acquirable.
     */
    public boolean isHealthy() {
        if (!channel.isActive()) {
            return false;
        }
        final HttpSession session = HttpSession.get(channel);
        // If no HttpSession handler is in the pipeline, only check channel activity.
        if (session == HttpSession.INACTIVE) {
            return true;
        }
        return session.isAcquirable();
    }

    /**
     * Returns the duration in nanoseconds since the last activity (request send or response receive)
     * on this connection.
     */
    public long idleDurationNanos() {
        return System.nanoTime() - lastActivityTimeNanos;
    }

    /**
     * Returns the remote address of this connection.
     */
    @Nullable
    public SocketAddress remoteAddress() {
        return channel.remoteAddress();
    }

    /**
     * Returns the local address of this connection.
     */
    @Nullable
    public SocketAddress localAddress() {
        return channel.localAddress();
    }

    /**
     * Returns the {@link AttributeMap} of the underlying {@link Channel}.
     * Users can store custom attributes for use in {@link ConnectionAcquisitionStrategy} implementations.
     */
    public AttributeMap attrs() {
        return channel;
    }

    /**
     * Returns the {@link HttpSession} for this connection.
     */
    HttpSession session() {
        return HttpSession.get(channel);
    }

    void incrementActiveRequests() {
        activeRequests++;
        lastActivityTimeNanos = System.nanoTime();
    }

    void decrementActiveRequests() {
        activeRequests--;
        lastActivityTimeNanos = System.nanoTime();
    }

    @Override
    public String toString() {
        final MoreObjects.ToStringHelper helper = MoreObjects.toStringHelper(this)
                                                             .omitNullValues();
        helper.add("protocol", protocol)
              .add("activeRequests", activeRequests)
              .add("maxConcurrentRequests", maxConcurrentRequests);
        final SocketAddress remote = remoteAddress();
        if (remote instanceof InetSocketAddress) {
            helper.add("remoteAddress", remote);
        }
        helper.add("healthy", isHealthy());
        return helper.toString();
    }
}
