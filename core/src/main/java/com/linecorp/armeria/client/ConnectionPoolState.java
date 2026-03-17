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

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Holds the protocol-related state for a {@link ConnectionPool}.
 *
 * <p>A {@link ConnectionPoolState} provides two categories of information:
 * <ul>
 *   <li><b>Pre-handshake</b>: {@link #desiredProtocol()} — the protocol requested by the client
 *       (e.g., {@link SessionProtocol#HTTP}, {@link SessionProtocol#H2C}). This is determined
 *       from the URI scheme ({@code http://}, {@code h2c://}, etc.) and is known before any
 *       connection is established.</li>
 *   <li><b>Post-handshake</b>: {@link #negotiatedProtocol()} — the actual protocol negotiated
 *       with the server (e.g., {@link SessionProtocol#H2}, {@link SessionProtocol#H1}).
 *       This becomes available after the first successful connection (TLS/ALPN negotiation).</li>
 * </ul>
 *
 * <p>The distinction matters because:
 * <ul>
 *   <li>{@code http://} may resolve to either H1C or H2C depending on server capabilities.</li>
 *   <li>{@code https://} may resolve to either H1 or H2 via ALPN negotiation.</li>
 *   <li>{@code h2c://} or {@code h2://} explicitly request a specific protocol.</li>
 * </ul>
 *
 * <p>{@link ConnectionAcquisitionStrategy} implementations can use this state to make
 * protocol-aware decisions. For example, a strategy might prefer connection reuse over
 * creation when the negotiated protocol supports multiplexing (H2).
 *
 * @see ConnectionPool#state()
 * @see ConnectionAcquisitionStrategy
 */
@UnstableApi
public final class ConnectionPoolState {

    private final SessionProtocol desiredProtocol;
    @Nullable
    private volatile SessionProtocol negotiatedProtocol;

    /**
     * Creates a new {@link ConnectionPoolState}.
     *
     * @param desiredProtocol the protocol requested by the client (e.g., {@link SessionProtocol#HTTP},
     *                        {@link SessionProtocol#H2C})
     */
    ConnectionPoolState(SessionProtocol desiredProtocol) {
        this.desiredProtocol = requireNonNull(desiredProtocol, "desiredProtocol");
    }

    /**
     * Returns the protocol originally requested by the client. This corresponds to the URI scheme
     * used in the request:
     * <ul>
     *   <li>{@code http://} → {@link SessionProtocol#HTTP}</li>
     *   <li>{@code https://} → {@link SessionProtocol#HTTPS}</li>
     *   <li>{@code h1://} → {@link SessionProtocol#H1}</li>
     *   <li>{@code h1c://} → {@link SessionProtocol#H1C}</li>
     *   <li>{@code h2://} → {@link SessionProtocol#H2}</li>
     *   <li>{@code h2c://} → {@link SessionProtocol#H2C}</li>
     * </ul>
     *
     * <p>This value is always available and never changes after pool creation.
     */
    public SessionProtocol desiredProtocol() {
        return desiredProtocol;
    }

    /**
     * Returns the actual protocol negotiated with the server after the first successful connection,
     * or {@code null} if no connection has been established yet.
     *
     * <p>This is always one of the concrete protocols:
     * {@link SessionProtocol#H1}, {@link SessionProtocol#H1C},
     * {@link SessionProtocol#H2}, or {@link SessionProtocol#H2C}.
     *
     * <p>For example, if {@link #desiredProtocol()} is {@link SessionProtocol#HTTPS} and the server
     * advertises {@code h2} via ALPN, this method returns {@link SessionProtocol#H2}.
     */
    @Nullable
    public SessionProtocol negotiatedProtocol() {
        return negotiatedProtocol;
    }

    /**
     * Returns {@code true} if the desired protocol is one that requires TLS.
     */
    public boolean isTlsRequired() {
        return desiredProtocol.isTls();
    }

    /**
     * Returns {@code true} if the negotiated protocol supports multiplexing (HTTP/2).
     * Returns {@code false} if the negotiated protocol is HTTP/1 or if no protocol
     * has been negotiated yet.
     */
    public boolean isMultiplex() {
        final SessionProtocol negotiated = negotiatedProtocol;
        return negotiated != null && negotiated.isMultiplex();
    }

    /**
     * Sets the negotiated protocol. Only the first invocation takes effect;
     * subsequent calls are ignored.
     */
    void setNegotiatedProtocol(SessionProtocol negotiatedProtocol) {
        requireNonNull(negotiatedProtocol, "negotiatedProtocol");
        if (this.negotiatedProtocol == null) {
            this.negotiatedProtocol = negotiatedProtocol;
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("desiredProtocol", desiredProtocol)
                          .add("negotiatedProtocol", negotiatedProtocol)
                          .toString();
    }
}
