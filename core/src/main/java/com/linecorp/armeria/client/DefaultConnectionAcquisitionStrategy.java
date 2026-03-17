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

import java.util.List;

/**
 * The default {@link ConnectionAcquisitionStrategy} that selects the least-loaded available connection.
 *
 * <p>This strategy is protocol-agnostic: it uses {@link Connection#maxConcurrentRequests()} to
 * automatically handle both HTTP/1 and HTTP/2:
 * <ul>
 *   <li>HTTP/1 ({@code maxConcurrentRequests=1}): Only idle connections (0 active requests) appear
 *       as available, so this effectively selects an idle connection.</li>
 *   <li>HTTP/2 ({@code maxConcurrentRequests=N}): All connections with spare capacity are candidates,
 *       and the one with the fewest active requests is selected (least-loaded).</li>
 * </ul>
 */
final class DefaultConnectionAcquisitionStrategy implements ConnectionAcquisitionStrategy {

    static final DefaultConnectionAcquisitionStrategy INSTANCE =
            new DefaultConnectionAcquisitionStrategy();

    private DefaultConnectionAcquisitionStrategy() {}

    @Override
    public AcquisitionDecision acquire(ClientRequestContext ctx, ConnectionPool pool) {
        // 1. Find the least-loaded available connection.
        final List<Connection> available = pool.availableConnections();
        Connection leastLoaded = null;
        int minActive = Integer.MAX_VALUE;
        for (int i = 0; i < available.size(); i++) {
            final Connection c = available.get(i);
            if (c.activeRequests() < minActive) {
                minActive = c.activeRequests();
                leastLoaded = c;
            }
        }
        if (leastLoaded != null) {
            return AcquisitionDecision.useExisting(leastLoaded);
        }

        // 2. No available connections. Create a new one if under the limit.
        if (pool.numConnections() + pool.pendingConnectionCount() < pool.maxNumConnections()) {
            return AcquisitionDecision.createNew();
        }

        // 3. At the limit. Wait for a connection to become available.
        return AcquisitionDecision.pendingWait();
    }

    @Override
    public String toString() {
        return "ConnectionAcquisitionStrategy.ofDefault()";
    }
}
