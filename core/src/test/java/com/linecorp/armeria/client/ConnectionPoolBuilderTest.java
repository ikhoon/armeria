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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ConnectionPoolBuilderTest {

    @Test
    void defaults() {
        final ConnectionPoolBuilder builder = ConnectionPool.builder();
        assertThat(builder.maxNumConnections()).isEqualTo(ConnectionPoolBuilder.DEFAULT_MAX_NUM_CONNECTIONS);
        assertThat(builder.acquisitionStrategy()).isNull();
        assertThat(builder.idleTimeoutMillis()).isEqualTo(-1L);
        assertThat(builder.maxConnectionAgeMillis()).isEqualTo(-1L);
        assertThat(builder.connectionPoolListener()).isNull();
    }

    @Test
    void maxNumConnections() {
        final ConnectionPoolBuilder builder = ConnectionPool.builder()
                                                            .maxNumConnections(50);
        assertThat(builder.maxNumConnections()).isEqualTo(50);
    }

    @Test
    void maxNumConnections_rejectsNonPositive() {
        assertThatThrownBy(() -> ConnectionPool.builder().maxNumConnections(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectionPool.builder().maxNumConnections(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acquisitionStrategy() {
        final ConnectionAcquisitionStrategy strategy = ConnectionAcquisitionStrategy.ofDefault();
        final ConnectionPoolBuilder builder = ConnectionPool.builder()
                                                            .acquisitionStrategy(strategy);
        assertThat(builder.acquisitionStrategy()).isSameAs(strategy);
    }

    @Test
    void idleTimeout() {
        final ConnectionPoolBuilder builder = ConnectionPool.builder()
                                                            .idleTimeout(Duration.ofSeconds(30));
        assertThat(builder.idleTimeoutMillis()).isEqualTo(30_000L);
    }

    @Test
    void idleTimeoutMillis_rejectsNegative() {
        assertThatThrownBy(() -> ConnectionPool.builder().idleTimeoutMillis(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maxConnectionAge() {
        final ConnectionPoolBuilder builder = ConnectionPool.builder()
                                                            .maxConnectionAge(Duration.ofMinutes(5));
        assertThat(builder.maxConnectionAgeMillis()).isEqualTo(300_000L);
    }

    @Test
    void connectionPoolListener() {
        final ConnectionPoolListener listener = ConnectionPoolListener.noop();
        final ConnectionPoolBuilder builder = ConnectionPool.builder()
                                                            .connectionPoolListener(listener);
        assertThat(builder.connectionPoolListener()).isSameAs(listener);
    }
}
