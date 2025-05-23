/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client.endpoint;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.common.util.Listenable;
import com.linecorp.armeria.internal.client.endpoint.StaticEndpointGroup;
import com.linecorp.armeria.internal.client.endpoint.UndefinedEndpointGroup;

/**
 * A list of {@link Endpoint}s.
 */
public interface EndpointGroup extends Listenable<List<Endpoint>>, EndpointSelector, AsyncCloseable {

    /**
     * Returns a singleton {@link EndpointGroup} which does not contain any {@link Endpoint}s.
     */
    static EndpointGroup of() {
        return StaticEndpointGroup.EMPTY;
    }

    /**
     * Returns an {@link EndpointGroup} that combines all the {@link Endpoint}s of {@code endpointGroups}.
     * {@code endpointGroups} can be instances of {@link Endpoint} as well, any {@link EndpointGroup}s and
     * {@link Endpoint} will all be combined into a single {@link EndpointGroup} that contains the total set.
     * The {@link EndpointGroup} returned by this method will use
     * {@link EndpointSelectionStrategy#weightedRoundRobin()} for selecting an {@link Endpoint}.
     */
    static EndpointGroup of(EndpointGroup... endpointGroups) {
        return of(EndpointSelectionStrategy.weightedRoundRobin(), endpointGroups);
    }

    /**
     * Returns an {@link EndpointGroup} that combines all the {@link Endpoint}s of {@code endpointGroups}.
     * {@code endpointGroups} can be instances of {@link Endpoint} as well, any {@link EndpointGroup}s and
     * {@link Endpoint} will all be combined into a single {@link EndpointGroup} that contains the total set.
     */
    static EndpointGroup of(EndpointSelectionStrategy selectionStrategy, EndpointGroup... endpointGroups) {
        requireNonNull(endpointGroups, "endpointGroups");
        return of(selectionStrategy, ImmutableList.copyOf(endpointGroups));
    }

    /**
     * Returns an {@link EndpointGroup} that combines all the {@link Endpoint}s of {@code endpointGroups}.
     * {@code endpointGroups} can be instances of {@link Endpoint} as well, any {@link EndpointGroup}s and
     * {@link Endpoint} will all be combined into a single {@link EndpointGroup} that contains the total set.
     * The {@link EndpointGroup} returned by this method will use
     * {@link EndpointSelectionStrategy#weightedRoundRobin()} for selecting an {@link Endpoint}.
     */
    static EndpointGroup of(Iterable<? extends EndpointGroup> endpointGroups) {
        return of(EndpointSelectionStrategy.weightedRoundRobin(), endpointGroups);
    }

    /**
     * Returns an {@link EndpointGroup} that combines all the {@link Endpoint}s of {@code endpointGroups}.
     * {@code endpointGroups} can be instances of {@link Endpoint} as well, any {@link EndpointGroup}s and
     * {@link Endpoint} will all be combined into a single {@link EndpointGroup} that contains the total set.
     */
    static EndpointGroup of(EndpointSelectionStrategy selectionStrategy,
                            Iterable<? extends EndpointGroup> endpointGroups) {
        requireNonNull(selectionStrategy, "selectionStrategy");
        requireNonNull(endpointGroups, "endpointGroups");

        final List<EndpointGroup> groups = new ArrayList<>();
        final List<Endpoint> staticEndpoints = new ArrayList<>();
        for (EndpointGroup endpointGroup : endpointGroups) {
            // We merge raw Endpoint and StaticEndpointGroup into one StaticEndpointGroup for a bit of
            // efficiency.
            if (endpointGroup instanceof Endpoint) {
                staticEndpoints.add((Endpoint) endpointGroup);
            } else if (endpointGroup instanceof StaticEndpointGroup) {
                staticEndpoints.addAll(endpointGroup.endpoints());
            } else {
                groups.add(endpointGroup);
            }
        }

        if (groups.isEmpty() && staticEndpoints.isEmpty()) {
            return of();
        }

        if (groups.isEmpty()) {
            if (staticEndpoints.size() == 1) {
                // Only one static endpoint, can return it directly.
                return staticEndpoints.get(0);
            }
            // Only static endpoints, return an optimized endpoint group.
            return new StaticEndpointGroup(selectionStrategy, staticEndpoints);
        }

        if (!staticEndpoints.isEmpty()) {
            groups.add(new StaticEndpointGroup(selectionStrategy, staticEndpoints));
        }

        if (groups.size() == 1) {
            return groups.get(0);
        }

        return new CompositeEndpointGroup(selectionStrategy, groups);
    }

    /**
     * Returns the endpoints held by this {@link EndpointGroup}.
     */
    List<Endpoint> endpoints();

    /**
     * Returns the {@link EndpointSelectionStrategy} of this {@link EndpointGroup}.
     */
    EndpointSelectionStrategy selectionStrategy();

    /**
     * Selects an {@link Endpoint} from this {@link EndpointGroup}.
     *
     * @return the {@link Endpoint} selected by the {@link EndpointSelectionStrategy},
     *         which was specified when constructing this {@link EndpointGroup},
     *         or {@code null} if this {@link EndpointGroup} is empty.
     */
    @Nullable
    @Override
    Endpoint selectNow(ClientRequestContext ctx);

    /**
     * Returns the timeout to wait until a successful {@link Endpoint} selection.
     * If an {@link Endpoint} is not resolved by this {@link EndpointGroup} within the timeout, a null value
     * will be returned by {@link EndpointSelector#select(ClientRequestContext, ScheduledExecutorService)}.
     * The null {@link Endpoint} may cause a client request end with
     * an {@link EndpointSelectionTimeoutException} if no {@link RetryingClient} is configured.
     *
     * <p>{@code 0} means {@link #selectNow(ClientRequestContext)} should always return an {@link Endpoint}.
     */
    @UnstableApi
    long selectionTimeoutMillis();

    /**
     * Returns a {@link CompletableFuture} which is completed when the initial {@link Endpoint}s are ready.
     */
    CompletableFuture<List<Endpoint>> whenReady();

    @Override
    default void addListener(Consumer<? super List<Endpoint>> listener) {
        addListener(listener, false);
    }

    /**
     * Adds a {@link Consumer} that will be invoked when this {@link EndpointGroup} changes its value.
     * If {@code notifyLatestEndpoints} is set to true and the {@link #whenReady()} is completed already,
     * the {@link Consumer} will be invoked immediately with the current {@link #endpoints()}.
     */
    default void addListener(Consumer<? super List<Endpoint>> listener, boolean notifyLatestEndpoints) {}

    @Override
    default void removeListener(Consumer<?> listener) {}

    /**
     * Creates a new {@link EndpointGroup} that tries this {@link EndpointGroup} first and then the specified
     * {@link EndpointGroup} when this {@link EndpointGroup} does not have a requested resource.
     *
     * @param nextEndpointGroup the {@link EndpointGroup} to try secondly.
     */
    default EndpointGroup orElse(EndpointGroup nextEndpointGroup) {
        return new OrElseEndpointGroup(this, nextEndpointGroup);
    }

    /**
     * Returns {@code true} if the specified {@code endpointGroup} is an undefined {@link EndpointGroup},
     * which signifies that a request was created without a {@link URI} or {@link EndpointGroup}.
     * For example,
     * <pre>{@code
     * HttpPreprocessor preprocessor = (delegate, ctx, req) -> {
     *     if (EndpointGroup.isUndefined(ctx.endpointGroup())) {
     *         ctx.setEndpointGroup(Endpoint.of("fallback-endpoint"));
     *     }
     *     return delegate.execute(ctx, req);
     * };
     * WebClient client = WebClient.builder(preprocessor)
     *                             .build();
     * }</pre>
     */
    static boolean isUndefined(EndpointGroup endpointGroup) {
        return UndefinedEndpointGroup.of() == endpointGroup;
    }
}
