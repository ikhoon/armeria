/*
 * Copyright 2024 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.server;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.AbstractTlsConfig;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.TlsVersion;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.TlsEngineType;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContextBuilder;

/**
 * Provides server-side TLS configuration for {@link TlsProvider}.
 */
@UnstableApi
public final class ServerTlsConfig extends AbstractTlsConfig {

    /**
     * Returns a new {@link ServerTlsConfigBuilder}.
     */
    public static ServerTlsConfigBuilder builder() {
        return new ServerTlsConfigBuilder();
    }

    private final ClientAuth clientAuth;

    ServerTlsConfig(TlsEngineType tlsEngineType, List<TlsVersion> tlsVersions, List<String> ciphers,
                    boolean allowsUnsafeCiphers, @Nullable MeterIdPrefix meterIdPrefix, ClientAuth clientAuth,
                    Consumer<SslContextBuilder> tlsCustomizer) {
        super(tlsEngineType, tlsVersions, ciphers, allowsUnsafeCiphers, meterIdPrefix, tlsCustomizer);
        this.clientAuth = clientAuth;
    }

    /**
     * Returns the client authentication mode.
     */
    public ClientAuth clientAuth() {
        return clientAuth;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {return true;}
        if (!(o instanceof ServerTlsConfig)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        final ServerTlsConfig tlsConfig = (ServerTlsConfig) o;
        return clientAuth == tlsConfig.clientAuth;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), clientAuth);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("tlsVersions", tlsVersions())
                          .add("ciphers", ciphers())
                          .add("allowsUnsafeCiphers", allowsUnsafeCiphers())
                          .add("meterIdPrefix", meterIdPrefix())
                          .add("clientAuth", clientAuth)
                          .add("tlsCustomizer", tlsCustomizer())
                          .toString();
    }
}
