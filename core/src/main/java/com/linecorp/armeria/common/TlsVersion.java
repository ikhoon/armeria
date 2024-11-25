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

package com.linecorp.armeria.common;

public enum TlsVersion {
    TLSv1_3("TLSv1.3"),
    TLSv1_2("TLSv1.2"),
    TLSv1_1("TLSv1.1"),
    TLSv1("TLSv1");

    private final String tlsVersion;

    TlsVersion(String tlsVersion) {
        this.tlsVersion = tlsVersion;
    }

    public String asString() {
        return tlsVersion;
    }
}
