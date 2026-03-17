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
import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.proxy.ProxyConfig;
import com.linecorp.armeria.client.proxy.ProxyType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.DomainSocketAddress;

/**
 * A key that uniquely identifies a connection pool destination.
 * It consists of the target {@link Endpoint}, proxy configuration, TLS settings,
 * and optional local bind address.
 *
 * @see ConnectionPool
 * @see ConnectionPoolFactory
 */
@UnstableApi
public final class PoolKey {

    private final Endpoint endpoint;
    private final ProxyConfig proxyConfig;
    @Nullable
    private final ClientTlsSpec tlsSpec;
    @Nullable
    private final InetSocketAddress localAddress;
    private final int hashCode;

    /**
     * Creates a new {@link PoolKey}.
     */
    PoolKey(Endpoint endpoint, ProxyConfig proxyConfig, @Nullable ClientTlsSpec tlsSpec,
            @Nullable InetSocketAddress localAddress) {
        this.endpoint = requireNonNull(endpoint, "endpoint");
        this.proxyConfig = requireNonNull(proxyConfig, "proxyConfig");
        this.tlsSpec = tlsSpec;
        this.localAddress = localAddress;
        hashCode = Objects.hash(endpoint, proxyConfig, tlsSpec, localAddress);
    }

    /**
     * Returns the target {@link Endpoint}.
     */
    public Endpoint endpoint() {
        return endpoint;
    }

    /**
     * Returns the {@link ProxyConfig} to use for connecting to the endpoint.
     */
    public ProxyConfig proxyConfig() {
        return proxyConfig;
    }

    /**
     * Returns the local address to bind to, or {@code null} if the default should be used.
     */
    @Nullable
    public InetSocketAddress localAddress() {
        return localAddress;
    }

    /**
     * Returns the remote address derived from the endpoint.
     */
    SocketAddress toRemoteAddress() {
        final InetSocketAddress remoteAddr = endpoint.toSocketAddress(-1);
        if (endpoint.isDomainSocket()) {
            return ((DomainSocketAddress) remoteAddr).asNettyAddress();
        }

        assert !remoteAddr.isUnresolved() || proxyConfig.proxyType().isForwardProxy()
                : remoteAddr + ", " + proxyConfig;

        return remoteAddr;
    }

    @Nullable
    ClientTlsSpec tlsSpec() {
        return tlsSpec;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof PoolKey)) {
            return false;
        }

        final PoolKey that = (PoolKey) o;
        return hashCode == that.hashCode &&
               endpoint.equals(that.endpoint) &&
               proxyConfig.equals(that.proxyConfig) &&
               Objects.equals(tlsSpec, that.tlsSpec) &&
               Objects.equals(localAddress, that.localAddress);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        final MoreObjects.ToStringHelper helper =
                MoreObjects.toStringHelper(this).omitNullValues();
        helper.add("endpoint", endpoint);
        if (proxyConfig.proxyType() != ProxyType.DIRECT) {
            helper.add("proxyConfig", proxyConfig);
        }
        helper.add("tlsSpec", tlsSpec)
              .add("localAddress", localAddress);
        return helper.toString();
    }
}
