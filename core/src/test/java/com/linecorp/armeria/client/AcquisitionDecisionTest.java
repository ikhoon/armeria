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
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.AcquisitionDecision.Type;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;

class AcquisitionDecisionTest {

    @Test
    void useExisting() {
        final Channel channel = new EmbeddedChannel();
        final Connection connection = new Connection(channel, com.linecorp.armeria.common.SessionProtocol.H2, 100);
        final AcquisitionDecision decision = AcquisitionDecision.useExisting(connection);

        assertThat(decision.type()).isEqualTo(Type.USE_EXISTING);
        assertThat(decision.connection()).isSameAs(connection);
        assertThat(decision.toString()).contains("USE_EXISTING");
    }

    @Test
    void createNew() {
        final AcquisitionDecision decision = AcquisitionDecision.createNew();

        assertThat(decision.type()).isEqualTo(Type.CREATE_NEW);
        assertThatThrownBy(decision::connection)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("USE_EXISTING");
        assertThat(decision.toString()).contains("CREATE_NEW");
    }

    @Test
    void pendingWait() {
        final AcquisitionDecision decision = AcquisitionDecision.pendingWait();

        assertThat(decision.type()).isEqualTo(Type.WAIT);
        assertThatThrownBy(decision::connection)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("USE_EXISTING");
        assertThat(decision.toString()).contains("WAIT");
    }

    @Test
    void singletonInstances() {
        // createNew() and pendingWait() should return singleton instances.
        assertThat(AcquisitionDecision.createNew()).isSameAs(AcquisitionDecision.createNew());
        assertThat(AcquisitionDecision.pendingWait()).isSameAs(AcquisitionDecision.pendingWait());
    }

    @Test
    void useExistingRequiresNonNull() {
        assertThatThrownBy(() -> AcquisitionDecision.useExisting(null))
                .isInstanceOf(NullPointerException.class);
    }
}
