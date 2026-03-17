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

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A factory that customizes {@link ConnectionPool} configuration for each unique endpoint.
 *
 * <p>This is the primary extension point for per-endpoint pool customization.
 * Users can implement this interface to provide different pool configurations
 * depending on the target endpoint.
 *
 * <h2>Example: Per-endpoint maxNumConnections</h2>
 * <pre>{@code
 * ClientFactory factory = ClientFactory.builder()
 *     .connectionPoolFactory((poolKey, builder) -> {
 *         if (poolKey.endpoint().host().equals("critical-service")) {
 *             builder.maxNumConnections(10);
 *         }
 *     })
 *     .build();
 * }</pre>
 *
 * <h2>Example: Custom strategies per protocol</h2>
 * <pre>{@code
 * ClientFactory factory = ClientFactory.builder()
 *     .connectionPoolFactory((poolKey, builder) -> {
 *         builder.http1AcquisitionStrategy(myHttp1Strategy)
 *                .http2AcquisitionStrategy(myHttp2Strategy);
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
        return (poolKey, builder) -> {
            // No customization; use defaults from ClientFactory.
        };
    }

    /**
     * Customizes a {@link ConnectionPoolBuilder} for the given pool key.
     * The builder is pre-initialized with the defaults from the {@link ClientFactory} configuration.
     * Implementations can selectively override settings by calling methods on the builder.
     *
     * <p>A pool is created per unique {@code poolKey}, which represents a combination of
     * endpoint, proxy configuration, TLS settings, and local address.
     *
     * @param poolKey the key identifying the endpoint, proxy configuration, TLS settings,
     *                and local address
     * @param builder the {@link ConnectionPoolBuilder} to customize
     */
    void customize(PoolKey poolKey, ConnectionPoolBuilder builder);
}
