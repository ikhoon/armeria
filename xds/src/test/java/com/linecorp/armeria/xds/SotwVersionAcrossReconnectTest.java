/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.armeria.xds;

import static com.linecorp.armeria.xds.XdsTestResources.BOOTSTRAP_CLUSTER_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.Message;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * Pins what the client puts in {@code version_info} on the FIRST request of a stream opened after the
 * previous stream broke. The xDS protocol says the version of a resource type is a property of the
 * resources rather than of the stream, so a reconnecting client is expected to report the last version
 * it saw on the previous stream.
 */
class SotwVersionAcrossReconnectTest {

    private static final String GROUP = "key";
    private static final String CLUSTER_TYPE_URL =
            "type.googleapis.com/envoy.config.cluster.v3.Cluster";
    private static final String ENDPOINT_TYPE_URL =
            "type.googleapis.com/envoy.config.endpoint.v3.ClusterLoadAssignment";

    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

    /** One entry per opened stream; each holds that stream's requests for the cluster type. */
    private static final List<List<DiscoveryRequest>> streams = new CopyOnWriteArrayList<>();
    private static final List<StreamObserver<DiscoveryResponse>> responseObservers =
            new CopyOnWriteArrayList<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new AggregatedDiscoveryServiceImplBase() {
                                      @Override
                                      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
                                              StreamObserver<DiscoveryResponse> responseObserver) {
                                          final List<DiscoveryRequest> requests =
                                                  new CopyOnWriteArrayList<>();
                                          streams.add(requests);
                                          responseObservers.add(responseObserver);
                                          return new StreamObserver<DiscoveryRequest>() {
                                              @Override
                                              public void onNext(DiscoveryRequest request) {
                                                  requests.add(request);
                                                  // Only answer a request that is not already an ACK of
                                                  // what we serve, so the client is not pushed in a loop.
                                                  if ("1".equals(request.getVersionInfo())) {
                                                      return;
                                                  }
                                                  final Snapshot snapshot = cache.getSnapshot(GROUP);
                                                  final Collection<? extends Message> resources;
                                                  if (CLUSTER_TYPE_URL.equals(request.getTypeUrl())) {
                                                      resources = snapshot.clusters().resources().values();
                                                  } else if (ENDPOINT_TYPE_URL.equals(request.getTypeUrl())) {
                                                      resources = snapshot.endpoints().resources().values();
                                                  } else {
                                                      return;
                                                  }
                                                  final DiscoveryResponse.Builder builder =
                                                          DiscoveryResponse.newBuilder()
                                                                           .setTypeUrl(request.getTypeUrl())
                                                                           .setVersionInfo("1")
                                                                           .setNonce("nonce-" +
                                                                                     streams.size());
                                                  resources.forEach(r -> builder.addResources(Any.pack(r)));
                                                  responseObserver.onNext(builder.build());
                                              }

                                              @Override
                                              public void onError(Throwable t) {}

                                              @Override
                                              public void onCompleted() {}
                                          };
                                      }
                                  })
                                  .build());
        }
    };

    @BeforeEach
    void beforeEach() {
        streams.clear();
        responseObservers.clear();
        cache.setSnapshot(
                GROUP,
                Snapshot.create(
                        ImmutableList.of(XdsTestResources.createCluster("cluster1", 1)),
                        ImmutableList.of(XdsTestResources.loadAssignment("cluster1", "127.0.0.1", 8080)),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        "1"));
    }

    @Test
    void versionInfoIsReportedOnTheFirstRequestAfterReconnect() {
        final Cluster bootstrapCluster = XdsTestResources.createStaticCluster(
                BOOTSTRAP_CLUSTER_NAME,
                XdsTestResources.loadAssignment(BOOTSTRAP_CLUSTER_NAME, server.httpUri()));
        final ApiConfigSource configSource =
                XdsTestResources.apiConfigSource(BOOTSTRAP_CLUSTER_NAME, ApiType.AGGREGATED_GRPC);
        final ConfigSource cdsSource =
                ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()).build();
        final Bootstrap bootstrap = XdsTestResources.bootstrap(configSource, cdsSource, bootstrapCluster);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ClusterRoot clusterRoot = xdsBootstrap.clusterRoot("cluster1");
            clusterRoot.addSnapshotWatcher(new TestResourceWatcher());

            // Wait until the client has ACKed version "1" for the cluster type on the first stream.
            await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(streams).isNotEmpty();
                assertThat(clusterRequests(streams.get(0)))
                        .anyMatch(r -> "1".equals(r.getVersionInfo()));
            });

            // Break the cluster stream from the server side, forcing the client to open a new one.
            // Each resource type gets its own stream here, so target the one carrying cluster requests.
            final int clusterStreamIdx = clusterStreamIndexes().get(0);
            responseObservers.get(clusterStreamIdx).onError(
                    Status.UNAVAILABLE.withDescription("forced disconnect").asRuntimeException());

            try {
                await().atMost(40, TimeUnit.SECONDS).untilAsserted(
                        () -> assertThat(clusterStreamIndexes()).hasSizeGreaterThan(1));
            } finally {
                dump();
            }

            final int reconnectedIdx = clusterStreamIndexes().get(1);
            final DiscoveryRequest firstOnNewStream = clusterRequests(streams.get(reconnectedIdx)).get(0);
            assertThat(firstOnNewStream.getVersionInfo()).isEqualTo("1");
            assertThat(firstOnNewStream.getResponseNonce()).isEmpty();
        }
    }

    /** Indexes of the streams that carried at least one cluster request, in the order they opened. */
    private static List<Integer> clusterStreamIndexes() {
        final ImmutableList.Builder<Integer> builder = ImmutableList.builder();
        for (int i = 0; i < streams.size(); i++) {
            if (!clusterRequests(streams.get(i)).isEmpty()) {
                builder.add(i);
            }
        }
        return builder.build();
    }

    private static void dump() {
        System.out.println("[reconnect] streams=" + streams.size());
        for (int i = 0; i < streams.size(); i++) {
            for (DiscoveryRequest r : streams.get(i)) {
                System.out.println("[reconnect] stream#" + i +
                                   " type=" + shortType(r.getTypeUrl()) +
                                   " version_info=\"" + r.getVersionInfo() + '"' +
                                   " nonce=\"" + r.getResponseNonce() + '"' +
                                   " names=" + r.getResourceNamesList());
            }
        }
    }

    private static String shortType(String typeUrl) {
        final int idx = typeUrl.lastIndexOf('.');
        return idx < 0 ? typeUrl : typeUrl.substring(idx + 1);
    }

    private static List<DiscoveryRequest> clusterRequests(List<DiscoveryRequest> requests) {
        return requests.stream().filter(r -> CLUSTER_TYPE_URL.equals(r.getTypeUrl()))
                       .collect(ImmutableList.toImmutableList());
    }
}
