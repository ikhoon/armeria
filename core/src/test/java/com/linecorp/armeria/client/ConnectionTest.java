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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.embedded.EmbeddedChannel;

class ConnectionTest {

    @Test
    void basicProperties() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final Connection conn = new Connection(channel, SessionProtocol.H2, 100);

        assertThat(conn.channel()).isSameAs(channel);
        assertThat(conn.protocol()).isEqualTo(SessionProtocol.H2);
        assertThat(conn.maxConcurrentRequests()).isEqualTo(100);
        assertThat(conn.activeRequests()).isZero();
        assertThat(conn.isAvailable()).isTrue();
        assertThat(conn.isHealthy()).isTrue();
        assertThat(conn.attrs()).isNotNull();
    }

    @Test
    void h1Connection() {
        final Connection conn = new Connection(new EmbeddedChannel(), SessionProtocol.H1, 1);

        assertThat(conn.maxConcurrentRequests()).isEqualTo(1);
        assertThat(conn.isAvailable()).isTrue();

        conn.incrementActiveRequests();
        // H1 with 1 active = at max, not available
        assertThat(conn.activeRequests()).isEqualTo(1);
        assertThat(conn.isAvailable()).isFalse();

        conn.decrementActiveRequests();
        assertThat(conn.activeRequests()).isZero();
        assertThat(conn.isAvailable()).isTrue();
    }

    @Test
    void h2Connection() {
        final Connection conn = new Connection(new EmbeddedChannel(), SessionProtocol.H2, 100);

        // Still available after several requests
        conn.incrementActiveRequests();
        conn.incrementActiveRequests();
        conn.incrementActiveRequests();
        assertThat(conn.activeRequests()).isEqualTo(3);
        assertThat(conn.isAvailable()).isTrue();
    }

    @Test
    void healthyWhenChannelActive() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final Connection conn = new Connection(channel, SessionProtocol.H2, 100);

        assertThat(conn.isHealthy()).isTrue();

        channel.close();
        assertThat(conn.isHealthy()).isFalse();
        assertThat(conn.isAvailable()).isFalse();
    }

    @Test
    void idleDuration() throws Exception {
        final Connection conn = new Connection(new EmbeddedChannel(), SessionProtocol.H2, 100);
        // idleDurationNanos should increase over time
        Thread.sleep(10);
        assertThat(conn.idleDurationNanos()).isGreaterThan(0);

        // Activity resets idle duration
        conn.incrementActiveRequests();
        assertThat(conn.idleDurationNanos()).isLessThan(1_000_000_000L); // < 1 second
    }

    @Test
    void toStringContainsInfo() {
        final Connection conn = new Connection(new EmbeddedChannel(), SessionProtocol.H2C, 100);
        final String str = conn.toString();
        assertThat(str).contains("h2c");
        assertThat(str).contains("maxConcurrentRequests=100");
    }
}
