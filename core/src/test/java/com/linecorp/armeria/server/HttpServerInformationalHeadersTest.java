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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpServerInformationalHeadersTest {

    private static final ResponseHeaders hints = ResponseHeaders.builder(103)
                                                                .add("link", "</style.css>; rel=preload")
                                                                .build();
    private static final ResponseHeaders processing = ResponseHeaders.of(HttpStatus.PROCESSING);
    private static final BlockingQueue<ServiceRequestContext> contexts = new LinkedBlockingQueue<>();
    private static final BlockingQueue<CompletableFuture<Void>> queuedWrites = new LinkedBlockingQueue<>();
    private static final CompletableFuture<HttpResponse> firstResponse = new CompletableFuture<>();
    private static final CompletableFuture<HttpResponse> queuedResponse = new CompletableFuture<>();
    private static final BlockingQueue<HttpResponseWriter> pendingFinals = new LinkedBlockingQueue<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.auto100Continue(false).addHeader("x-default", "default");
            sb.service("/hints", hintService(false, false));
            sb.service("/wrapped-hints", hintService(false, true));
            sb.service("/blocking-hints", hintService(true, false));
            sb.service("/stream-hints", (ctx, req) -> HttpResponse.of(
                    ctx.writeInformationalHeaders(hints).thenApply(unused ->
                            HttpResponse.of(processing, ResponseHeaders.of(HttpStatus.OK),
                                            HttpData.ofUtf8("ok")))));
            sb.service("/capture", (ctx, req) -> {
                contexts.add(ctx);
                return HttpResponse.of(HttpStatus.OK);
            });
            sb.service("/pending", (ctx, req) -> {
                contexts.add(ctx);
                return HttpResponse.streaming();
            });
            sb.service("/first", (ctx, req) -> HttpResponse.of(firstResponse));
            sb.service("/queued-hints", (ctx, req) -> {
                final CompletableFuture<Void> write = ctx.writeInformationalHeaders(hints);
                queuedWrites.add(write);
                return HttpResponse.of(queuedResponse);
            });
            sb.service("/independent", new HttpService() {
                @Override
                public ExchangeType exchangeType(RoutingContext routingContext) {
                    return ExchangeType.REQUEST_STREAMING;
                }

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                    final HttpResponseWriter response = HttpResponse.streaming();
                    pendingFinals.add(response);
                    ctx.writeInformationalHeaders(hints);
                    return response;
                }
            });
            sb.service("/final-header", (ctx, req) -> {
                final HttpResponseWriter response = HttpResponse.streaming();
                response.write(ResponseHeaders.builder(HttpStatus.OK).contentLength(6).build());
                pendingFinals.add(response);
                contexts.add(ctx);
                return response;
            });
            sb.service("/hints-continue", (ctx, req) -> HttpResponse.of(
                    ctx.writeInformationalHeaders(hints)
                       .thenCompose(unused -> ctx.writeInformationalHeaders(
                               ResponseHeaders.of(HttpStatus.CONTINUE)))
                       .thenCompose(unused -> req.aggregate())
                       .thenApply(aggregated -> HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                                                              aggregated.contentUtf8()))));
        }
    };

    private static HttpService hintService(boolean offEventLoop, boolean wrapped) {
        return new HttpService() {
            @Override
            public ExchangeType exchangeType(RoutingContext routingContext) {
                return ExchangeType.REQUEST_STREAMING;
            }

            @Override
            public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                final ServiceRequestContext writer =
                        wrapped ? new ServiceRequestContextWrapper(ctx) {} : ctx;
                ctx.addAdditionalResponseHeader("x-extra", "extra");
                final CompletableFuture<Void> write;
                if (offEventLoop) {
                    write = CompletableFuture.supplyAsync(() -> {
                        assertThat(ctx.eventLoop().inEventLoop()).isFalse();
                        return writer.writeInformationalHeaders(hints);
                    }, ctx.blockingTaskExecutor()).thenCompose(Function.identity());
                } else {
                    write = writer.writeInformationalHeaders(hints);
                }
                return HttpResponse.of(write.thenCompose(unused -> writer.writeInformationalHeaders(processing))
                                            .thenApply(unused -> HttpResponse.of(HttpStatus.OK)));
            }
        };
    }

    @ParameterizedTest
    @CsvSource({ "H1C,/hints", "H2C,/hints", "H1C,/wrapped-hints", "H2C,/wrapped-hints",
                 "H1C,/blocking-hints", "H2C,/blocking-hints", "H1C,/stream-hints", "H2C,/stream-hints" })
    void sendsInformationalsBeforeFinalResponse(SessionProtocol protocol, String path) {
        final AggregatedHttpResponse response = WebClient.of(server.uri(protocol)).get(path).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.informationals()).containsExactly(hints, processing);
        response.informationals().forEach(headers -> {
            assertThat(headers.names()).doesNotContain(HttpHeaderNames.of("x-default"),
                                                       HttpHeaderNames.of("x-extra"));
        });
        assertThat(response.headers().get("x-default")).isEqualTo("default");
        if (!"/stream-hints".equals(path)) {
            assertThat(response.headers().get("x-extra")).isEqualTo("extra");
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void ignoresOtherInformationalsWhileWaitingForContinue(SessionProtocol protocol) {
        final AggregatedHttpResponse response = WebClient.of(server.uri(protocol))
                .execute(RequestHeaders.of(HttpMethod.POST, "/hints-continue",
                                           HttpHeaderNames.EXPECT, "100-continue"), "upload")
                .aggregate().join();
        assertThat(response.informationals()).containsExactly(hints, ResponseHeaders.of(HttpStatus.CONTINUE));
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("upload");
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void rejectsInformationalsAfterFinalResponse(SessionProtocol protocol) throws Exception {
        final AggregatedHttpResponse response = WebClient.of(server.uri(protocol)).get("/capture")
                                                         .aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        final ServiceRequestContext ctx = contexts.poll(5, TimeUnit.SECONDS);
        assertThat(ctx).isNotNull();
        assertThatThrownBy(() -> ctx.writeInformationalHeaders(hints).get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void failsWhenRequestIsClosed(SessionProtocol protocol) throws Exception {
        final HttpRequest request = HttpRequest.streaming(RequestHeaders.of(HttpMethod.POST, "/pending",
                                                                           HttpHeaderNames.CONTENT_LENGTH, 6));
        final HttpResponse response = WebClient.of(server.uri(protocol)).execute(request);
        final ServiceRequestContext ctx = contexts.poll(5, TimeUnit.SECONDS);
        assertThat(ctx).isNotNull();
        response.abort();
        ctx.whenRequestCancelled().get(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> ctx.writeInformationalHeaders(hints).get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(protocol.isMultiplex() ? ClosedStreamException.class
                                                           : ClosedSessionException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = { 101, 200, 503 })
    void rejectsUnsupportedStatus(int status) {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertThatThrownBy(() -> ctx.writeInformationalHeaders(ResponseHeaders.of(status)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonCanonicalSwitchingProtocolsStatus() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertThatThrownBy(() -> ctx.writeInformationalHeaders(
                ResponseHeaders.of(new HttpStatus(101, "Custom Switching Protocols"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "content-length", "transfer-encoding" })
    void rejectsBodyFramingHeaders(String name) {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final String value = "content-length".equals(name) ? "0" : "chunked";
        assertThatThrownBy(() -> ctx.writeInformationalHeaders(ResponseHeaders.builder(103)
                                                                             .add(name, value).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void sendsInformationalWhileFinalResponseIsHeldOpen(SessionProtocol protocol) throws Exception {
        final HttpResponse response = WebClient.of(server.uri(protocol)).get("/independent");
        final CompletableFuture<ResponseHeaders> informational = new CompletableFuture<>();
        final CompletableFuture<Void> complete = new CompletableFuture<>();
        response.subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpObject object) {
                if (object instanceof ResponseHeaders &&
                    ((ResponseHeaders) object).status().isInformational()) {
                    informational.complete((ResponseHeaders) object);
                } else if (object instanceof HttpData) {
                    ((HttpData) object).close();
                }
            }

            @Override
            public void onError(Throwable cause) {
                informational.completeExceptionally(cause);
                complete.completeExceptionally(cause);
            }

            @Override
            public void onComplete() {
                complete.complete(null);
            }
        });
        final HttpResponseWriter finalResponse = pendingFinals.poll(5, TimeUnit.SECONDS);
        assertThat(finalResponse).isNotNull();
        try {
            assertThat(informational.get(5, TimeUnit.SECONDS)).isEqualTo(hints);
            assertThat(complete).isNotDone();
            finalResponse.write(ResponseHeaders.of(HttpStatus.OK));
            finalResponse.close();
            complete.get(5, TimeUnit.SECONDS);
        } finally {
            finalResponse.abort();
            response.abort();
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void rejectsInformationalAfterFinalHeadersWithBodyPending(SessionProtocol protocol) throws Exception {
        final SplitHttpResponse response = WebClient.of(server.uri(protocol)).get("/final-header").split();
        assertThat(response.headers().get(5, TimeUnit.SECONDS).status()).isEqualTo(HttpStatus.OK);
        final ServiceRequestContext ctx = contexts.poll(5, TimeUnit.SECONDS);
        final HttpResponseWriter finalResponse = pendingFinals.poll(5, TimeUnit.SECONDS);
        assertThat(ctx).isNotNull();
        assertThat(finalResponse).isNotNull();
        try {
            assertThatThrownBy(() -> ctx.writeInformationalHeaders(hints).get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
            finalResponse.write(HttpData.ofUtf8("upload"));
            finalResponse.close();
            assertThat(response.unsplit().aggregate().get(5, TimeUnit.SECONDS).contentUtf8())
                    .isEqualTo("upload");
        } finally {
            finalResponse.abort();
        }
    }

    @Test
    void rejectsEndOfStreamAndNullHeaders() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertThatThrownBy(() -> ctx.writeInformationalHeaders(ResponseHeaders.builder(103)
                                                                             .endOfStream(true).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ctx.writeInformationalHeaders(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void failsForContextWithoutLiveRequest() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertThatThrownBy(() -> ctx.writeInformationalHeaders(hints).join())
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void respectsHttp1PipeliningAndCompletesAfterFlush() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(server.httpSocketAddress());
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /first HTTP/1.1\r\nHost: localhost\r\n\r\n" +
                                            "GET /queued-hints HTTP/1.1\r\nHost: localhost\r\n" +
                                            "Connection: close\r\n\r\n")
                                           .getBytes(StandardCharsets.US_ASCII));
            final CompletableFuture<Void> queued = queuedWrites.poll(5, TimeUnit.SECONDS);
            assertThat(queued).isNotNull();
            assertThat(queued).isNotDone();
            firstResponse.complete(HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "first"));
            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            assertThat(reader.readLine()).isEqualTo("HTTP/1.1 200 OK");
            String line;
            while (!(line = reader.readLine()).isEmpty()) {
                assertThat(line).isNotNull();
            }
            final char[] body = new char[5];
            for (int i = 0; i < body.length; i++) {
                body[i] = (char) reader.read();
            }
            assertThat(new String(body)).isEqualTo("first");
            assertThat(reader.readLine()).startsWith("HTTP/1.1 103 ");
            while (!(line = reader.readLine()).isEmpty()) {
                assertThat(line).isNotNull();
            }
            queued.get(5, TimeUnit.SECONDS);
            assertThat(queuedResponse).isNotDone();
            queuedResponse.complete(HttpResponse.of(HttpStatus.OK));
            assertThat(reader.readLine()).isEqualTo("HTTP/1.1 200 OK");
        } finally {
            firstResponse.complete(HttpResponse.of(HttpStatus.OK));
            queuedResponse.complete(HttpResponse.of(HttpStatus.OK));
        }
    }
}
