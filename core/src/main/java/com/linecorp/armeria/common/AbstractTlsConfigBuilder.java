/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.function.Consumer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.netty.handler.ssl.SslContextBuilder;

/**
 * A skeletal builder implementation for {@link TlsProvider}.
 */
@UnstableApi
public abstract class AbstractTlsConfigBuilder<SELF extends AbstractTlsConfigBuilder<SELF>> {

    private static final Consumer<SslContextBuilder> NOOP = b -> {};

    private boolean allowsUnsafeCiphers;
    private Consumer<SslContextBuilder> tlsCustomizer = NOOP;
    @Nullable
    private MeterIdPrefix meterIdPrefix;

    private TlsEngineType tlsEngineType = Flags.tlsEngineType();
    private List<TlsVersion> tlsVersions;
    private List<String> ciphers;

    /**
     * Creates a new instance.
     */
    protected AbstractTlsConfigBuilder(TlsCipherSuitePreset tlsCipherSuitePreset) {
        tlsVersions = tlsCipherSuitePreset.tlsVersions();
        ciphers = tlsCipherSuitePreset.ciphers();
    }

    /**
     * Sets the {@link TlsEngineType}.
     *
     * <p>If unspecified, {@link Flags#tlsEngineType()} is used.
     */
    public SELF tlsEngineType(TlsEngineType tlsEngineType) {
        requireNonNull(tlsEngineType, "tlsEngineType");
        this.tlsEngineType = tlsEngineType;
        return self();
    }

    /**
     * Returns the {@link TlsEngineType}.
     */
    protected final TlsEngineType tlsEngineType() {
        return tlsEngineType;
    }

    /**
     * Sets the {@link TlsVersion}s to enable for the TLS handshake.
     *
     * <p>If unspecified, {@link Flags#serverTlsCipherSuitePreset()} is used for the server side and
     * {@link Flags#clientTlsCipherSuitePreset()} is used for the client side.
     */
    public SELF tlsVersions(TlsVersion... tlsVersions) {
        requireNonNull(tlsVersions, "tlsVersions");
        return tlsVersions(ImmutableList.copyOf(tlsVersions));
    }

    /**
     * Sets the {@link TlsVersion}s to enable for the TLS handshake.
     *
     * <p>If unspecified, {@link Flags#serverTlsCipherSuitePreset()} is used for the server side and
     * {@link Flags#clientTlsCipherSuitePreset()} is used for the client side.
     */
    public SELF tlsVersions(Iterable<TlsVersion> tlsVersions) {
        requireNonNull(tlsVersions, "tlsVersions");
        this.tlsVersions = ImmutableList.copyOf(tlsVersions);
        return self();
    }

    /**
     * Returns the {@link TlsVersion}s to enable for the TLS handshake.
     */
    protected final List<TlsVersion> tlsVersions() {
        return tlsVersions;
    }

    /**
     * Sets the cipher suites to enable, in the order of preference.
     *
     * <p>If unspecified, {@link Flags#serverTlsCipherSuitePreset()} is used for the server side and
     * {@link Flags#clientTlsCipherSuitePreset()} is used for the client side.
     */
    public SELF ciphers(String... cipherSuites) {
        requireNonNull(cipherSuites, "cipherSuites");
        return ciphers(ImmutableList.copyOf(cipherSuites));
    }

    /**
     * Sets the cipher suites to enable, in the order of preference.
     *
     * <p>If unspecified, {@link Flags#serverTlsCipherSuitePreset()} is used for the server side and
     * {@link Flags#clientTlsCipherSuitePreset()} is used for the client side.
     */
    public SELF ciphers(Iterable<String> ciphers) {
        requireNonNull(ciphers, "ciphers");
        this.ciphers = ImmutableList.copyOf(ciphers);
        return self();
    }

    /**
     * Returns the ciphers to enable for the TLS handshake.
     */
    protected final List<String> ciphers() {
        return ciphers;
    }

    /**
     * Allows the bad cipher suites listed in
     * <a href="https://datatracker.ietf.org/doc/html/rfc7540#appendix-A">RFC7540</a> for TLS handshake.
     *
     * <p>Note that enabling this option increases the security risk of your connection.
     * Use it only when you must communicate with a legacy system that does not support
     * secure cipher suites.
     * See <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-9.2.2">Section 9.2.2, RFC7540</a> for
     * more information. This option is disabled by default.
     *
     * @param allowsUnsafeCiphers Whether to allow the unsafe ciphers
     *
     * @deprecated It's not recommended to enable this option. Use it only when you have no other way to
     *             communicate with an insecure peer than this.
     */
    @Deprecated
    public SELF allowsUnsafeCiphers(boolean allowsUnsafeCiphers) {
        this.allowsUnsafeCiphers = allowsUnsafeCiphers;
        return self();
    }

    /**
     * Returns whether to allow the bad cipher suites listed in
     * <a href="https://datatracker.ietf.org/doc/html/rfc7540#appendix-A">RFC7540</a> for TLS handshake.
     */
    protected final boolean allowsUnsafeCiphers() {
        return allowsUnsafeCiphers;
    }

    /**
     * Adds the {@link Consumer} which can arbitrarily configure the {@link SslContextBuilder} that will be
     * applied to the SSL session. For example, use {@link SslContextBuilder#trustManager(TrustManagerFactory)}
     * to configure a custom server CA or {@link SslContextBuilder#keyManager(KeyManagerFactory)} to configure
     * a client certificate for SSL authorization.
     */
    public SELF tlsCustomizer(Consumer<? super SslContextBuilder> tlsCustomizer) {
        requireNonNull(tlsCustomizer, "tlsCustomizer");
        if (this.tlsCustomizer == NOOP) {
            //noinspection unchecked
            this.tlsCustomizer = (Consumer<SslContextBuilder>) tlsCustomizer;
        } else {
            this.tlsCustomizer = this.tlsCustomizer.andThen(tlsCustomizer);
        }
        return self();
    }

    /**
     * Returns the {@link Consumer} which can arbitrarily configure the {@link SslContextBuilder}.
     */
    @Deprecated
    protected final Consumer<SslContextBuilder> tlsCustomizer() {
        return tlsCustomizer;
    }

    /**
     * Sets the {@link MeterIdPrefix} for the TLS metrics.
     */
    public SELF meterIdPrefix(MeterIdPrefix meterIdPrefix) {
        this.meterIdPrefix = requireNonNull(meterIdPrefix, "meterIdPrefix");
        return self();
    }

    /**
     * Returns the {@link MeterIdPrefix} for TLS metrics.
     */
    @Nullable
    protected final MeterIdPrefix meterIdPrefix() {
        return meterIdPrefix;
    }

    @SuppressWarnings("unchecked")
    private SELF self() {
        return (SELF) this;
    }
}
