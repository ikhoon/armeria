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
/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.armeria.common;

import static com.linecorp.armeria.common.TlsVersion.TLSv1;
import static com.linecorp.armeria.common.TlsVersion.TLSv1_1;
import static com.linecorp.armeria.common.TlsVersion.TLSv1_2;
import static com.linecorp.armeria.common.TlsVersion.TLSv1_3;

import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.util.SslContextUtil;
import com.linecorp.armeria.server.Server;

/**
 * A set of predefined TLS cipher suites and TLS versions.
 */
@UnstableApi
public enum TlsCipherSuitePreset {
    // Forked from https://github.com/square/okhttp/blob/f1e6d01aea30844f0ddc7394208ec13d59d5e6f8/okhttp/src/main/kotlin/okhttp3/ConnectionSpec.kt#L319C1-L342

    /**
     * A secure TLS connection that requires a recent client platform and a recent server.
     * This is {@link Server}'s default configuration.
     */
    RESTRICT(ImmutableList.of(TLSv1_3, TLSv1_2), SslContextUtil.DEFAULT_CIPHERS),
    /**
     * A modern TLS configuration that works on most client platforms and can connect to most servers.
     * This is {@link Client}'s default configuration.
     */
    MODERN(ImmutableList.of(TLSv1_3, TLSv1_2), SslContextUtil.APPROVED_CIPHERS),
    /**
     * A backwards-compatible fallback configuration that works on obsolete client platforms and can
     * connect to obsolete servers. When possible, prefer to upgrade your client platform or server
     * rather than using this configuration.*
     */
    COMPATIBLE(ImmutableList.of(TLSv1_3, TLSv1_2, TLSv1_1, TLSv1), SslContextUtil.APPROVED_CIPHERS);

    private final List<TlsVersion> tlsVersions;
    private final List<String> ciphers;

    TlsCipherSuitePreset(List<TlsVersion> tlsVersions, List<String> ciphers) {
        this.tlsVersions = tlsVersions;
        this.ciphers = ciphers;
    }

    public List<TlsVersion> tlsVersions() {
        return tlsVersions;
    }

    public List<String> ciphers() {
        return ciphers;
    }
}
