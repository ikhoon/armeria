/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.internal.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.cert.CertificateException;

import org.junit.jupiter.api.Test;

class SelfSignedCertificateTest {
    @Test
    void fqdnAsteriskDoesNotThrowTest() throws CertificateException {
        new SelfSignedCertificate("*.netty.io", "EC", 256);
        new SelfSignedCertificate("*.netty.io", "RSA", 2048);
    }

    @Test
    void fqdnAsteriskFileNameTest() throws CertificateException {
        final SelfSignedCertificate ssc = new SelfSignedCertificate("*.netty.io", "EC", 256);
        assertThat(ssc.certificate().getName()).doesNotContain("*");
        assertThat(ssc.privateKey().getName()).doesNotContain("*");
    }
}
