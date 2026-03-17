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

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A factory that creates {@link ConnectionPool} instances for each unique combination of
 * endpoint, proxy configuration, TLS settings, and {@link SessionProtocol}.
 *
 * <p>This is the primary extension point for per-endpoint pool customization.
 * Users can implement this interface to provide different pool configurations
 * depending on the target endpoint and protocol.
 *
 * <h2>Example: Per-endpoint maxNumConnections</h2>
 * <pre>{@code
 * ClientFactory factory = ClientFactory.builder()
 *     .connectionPoolFactory((poolKey, protocol, builder) -> {
 *         if (poolKey.endpoint().host().equals("critical-service")) {
 *             builder.maxNumConnections(10);
 *         }
 *     })
 *     .build();
 * }</pre>
 *
 * <h2>Example: Custom strategy per endpoint</h2>
 * <pre>{@code
 * ClientFactory factory = ClientFactory.builder()
 *     .connectionPoolFactory((poolKey, protocol, builder) -> {
 *         if (protocol.isMultiplex()) {
 *             builder.acquisitionStrategy(myHttp2Strategy);
 *         } else {
 *             builder.acquisitionStrategy(myHttp1Strategy);
 *         }
 *     })
 *     .build();
 * }</pre>
 *
 * @see ConnectionPool
 * @see ConnectionPoolBuilder
 */
@UnstableApi
@FunctionalInterface
public interface ConnectionPoolFactory {

    /**
     * Returns the default {@link ConnectionPoolFactory} that creates pools using the default
     * configuration from the {@link ClientFactory}.
     */
    static ConnectionPoolFactory ofDefault() {
        return (poolKey, protocol, builder) -> {
            // No customization; use defaults from ClientFactory.
        };
    }

    /**
     * Customizes a {@link ConnectionPoolBuilder} for the given pool key and protocol.
     * The builder is pre-initialized with the defaults from the {@link ClientFactory} configuration.
     * Implementations can selectively override settings by calling methods on the builder.
     *
     * <p>A pool is created per unique combination of {@code poolKey} and {@code protocol}.
     * The {@code protocol} is always an explicit protocol (one of {@link SessionProtocol#H1},
     * {@link SessionProtocol#H1C}, {@link SessionProtocol#H2}, {@link SessionProtocol#H2C}).
     *
     * @param poolKey  the key identifying the endpoint, proxy configuration, TLS settings,
     *                 and local address
     * @param protocol the explicit {@link SessionProtocol} for this pool
     * @param builder  the {@link ConnectionPoolBuilder} to customize
     */
    void customize(PoolKey poolKey, SessionProtocol protocol, ConnectionPoolBuilder builder);
}
