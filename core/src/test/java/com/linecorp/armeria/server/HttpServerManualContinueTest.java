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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpServerManualContinueTest {

    private static final ResponseHeaders continueHeaders = ResponseHeaders.of(HttpStatus.CONTINUE);
    private static final String expectation = "100-continue";
    private static final long maxUploadLength = 1024 * 1024;
    private static final AtomicInteger storedUploads = new AtomicInteger();

    @TempDir
    static Path uploadDirectory;

    @RegisterExtension
    static final ServerExtension manualServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.auto100Continue(false).maxRequestLength(2 * maxUploadLength);
            sb.service("/upload", (ctx, req) -> {
                final long length = req.headers().contentLength();
                if (length < 0) {
                    return reject(ctx, HttpStatus.LENGTH_REQUIRED);
                }
                if (length > maxUploadLength) {
                    return reject(ctx, HttpStatus.REQUEST_ENTITY_TOO_LARGE);
                }
                return continueThenServe(HttpServerManualContinueTest::storeUpload, ctx, req);
            });

            final HttpService uploadService = new HttpService() {
                @Override
                public ExchangeType exchangeType(RoutingContext routingContext) {
                    return ExchangeType.REQUEST_STREAMING;
                }

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                    return storeUpload(ctx, req);
                }
            };
            sb.service("/decorated-upload", uploadService.decorate(uploadAdmissionDecorator()));
        }
    };

    @RegisterExtension
    static final ServerExtension automaticServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/echo", (ctx, req) -> echo(req));
        }
    };

    private static DecoratingHttpServiceFunction uploadAdmissionDecorator() {
        return (delegate, ctx, req) -> {
            final long length = req.headers().contentLength();
            if (length < 0) {
                return reject(ctx, HttpStatus.LENGTH_REQUIRED);
            }
            if (length > maxUploadLength) {
                return reject(ctx, HttpStatus.REQUEST_ENTITY_TOO_LARGE);
            }
            return continueThenServe(delegate, ctx, req);
        };
    }

    private static HttpResponse continueThenServe(HttpService service, ServiceRequestContext ctx,
                                                 HttpRequest req) throws Exception {
        if (!"100-continue".equalsIgnoreCase(req.headers().get(HttpHeaderNames.EXPECT))) {
            return service.serve(ctx, req);
        }

        final CompletableFuture<HttpResponse> response = ctx.writeInformationalHeaders(continueHeaders)
                .thenApplyAsync(unused -> {
                    try {
                        return service.serve(ctx, req);
                    } catch (Exception cause) {
                        return HttpResponse.ofFailure(cause);
                    }
                }, ctx.eventLoop());
        return HttpResponse.of(response);
    }

    private static HttpResponse storeUpload(ServiceRequestContext ctx, HttpRequest req) {
        return HttpResponse.of(req.aggregate().thenApplyAsync(aggregated -> {
            final String name = UUID.randomUUID() + ".bin";
            try {
                Files.write(uploadDirectory.resolve(name), aggregated.content().array());
            } catch (IOException cause) {
                return HttpResponse.ofFailure(cause);
            }
            storedUploads.incrementAndGet();
            return HttpResponse.of(ResponseHeaders.builder(HttpStatus.CREATED)
                                                   .add(HttpHeaderNames.LOCATION, name)
                                                   .build());
        }, ctx.blockingTaskExecutor()));
    }

    private static HttpResponse echo(HttpRequest req) {
        return HttpResponse.of(req.aggregate().thenApply(aggregated ->
                HttpResponse.of(ResponseHeaders.builder(HttpStatus.OK)
                                               .contentType(MediaType.PLAIN_TEXT_UTF_8)
                                               .add("x-seen-expect",
                                                    aggregated.headers().get(HttpHeaderNames.EXPECT, "none"))
                                               .build(),
                                HttpData.ofUtf8(aggregated.contentUtf8()))));
    }

    private static HttpResponse reject(ServiceRequestContext ctx, HttpStatus status) {
        if (ctx.sessionProtocol().isExplicitHttp1()) {
            ctx.initiateConnectionShutdown();
        }
        return HttpResponse.of(status);
    }

    @ParameterizedTest
    @CsvSource({ "H1C,/upload", "H2C,/upload", "H1C,/decorated-upload", "H2C,/decorated-upload" })
    void acceptsAfterSendingManualContinue(SessionProtocol protocol, String path) throws Exception {
        final WebClient client = WebClient.of(manualServer.uri(protocol));
        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.POST, path,
                                                 HttpHeaderNames.EXPECT, expectation), "upload")
                      .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.informationals()).containsExactly(continueHeaders);
        assertStoredUpload(response, "upload");
    }

    @ParameterizedTest
    @CsvSource({ "H1C,/upload", "H2C,/upload", "H1C,/decorated-upload", "H2C,/decorated-upload" })
    void acceptsUploadAtSizeLimit(SessionProtocol protocol, String path) throws Exception {
        final byte[] content = new byte[(int) maxUploadLength];
        Arrays.fill(content, (byte) 'a');
        final AggregatedHttpResponse response =
                WebClient.of(manualServer.uri(protocol))
                         .execute(RequestHeaders.of(HttpMethod.POST, path,
                                                    HttpHeaderNames.EXPECT, expectation), content)
                         .aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.informationals()).containsExactly(continueHeaders);
        final String location = response.headers().get(HttpHeaderNames.LOCATION);
        assertThat(location).isNotNull();
        assertThat(Files.readAllBytes(uploadDirectory.resolve(location))).isEqualTo(content);
    }

    @ParameterizedTest
    @CsvSource({ "H1C,/upload", "H2C,/upload", "H1C,/decorated-upload", "H2C,/decorated-upload" })
    void rejectsOversizedUploadWithoutContinueOrSendingBody(SessionProtocol protocol, String path)
            throws Exception {
        final WebClient client = WebClient.of(manualServer.uri(protocol));
        final AtomicInteger producedBodyChunks = new AtomicInteger();
        final int storedBefore = storedUploads.get();
        final HttpRequest request =
                HttpRequest.of(RequestHeaders.of(HttpMethod.POST, path,
                                                 HttpHeaderNames.EXPECT, "100-continue"),
                               HttpData.wrap(new byte[(int) maxUploadLength + 1]))
                           .mapData(data -> {
                               producedBodyChunks.incrementAndGet();
                               return data;
                           });
        final AggregatedHttpResponse response = client.execute(request).aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        assertThat(response.informationals()).isEmpty();
        assertThat(producedBodyChunks).hasValue(0);
        assertThat(storedUploads).hasValue(storedBefore);
        final AggregatedHttpResponse nextResponse = client.post(path, "next upload").aggregate().join();
        assertThat(nextResponse.status()).isEqualTo(HttpStatus.CREATED);
        assertStoredUpload(nextResponse, "next upload");
    }

    @ParameterizedTest
    @CsvSource({ "H1C,/upload", "H2C,/upload", "H1C,/decorated-upload", "H2C,/decorated-upload" })
    void doesNotSendContinueWithoutExpectation(SessionProtocol protocol, String path) throws Exception {
        final AggregatedHttpResponse response = WebClient.of(manualServer.uri(protocol))
                                                         .post(path, "upload").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.informationals()).isEmpty();
        assertStoredUpload(response, "upload");
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void preservesAutomaticHandlingByDefault(SessionProtocol protocol) {
        final AggregatedHttpResponse response =
                WebClient.of(automaticServer.uri(protocol))
                         .execute(RequestHeaders.of(HttpMethod.POST, "/echo",
                                                    HttpHeaderNames.EXPECT, "100-continue"), "upload")
                         .aggregate().join();
        assertThat(automaticServer.server().config().isAuto100ContinueEnabled()).isTrue();
        assertThat(manualServer.server().config().isAuto100ContinueEnabled()).isFalse();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.informationals()).containsExactly(continueHeaders);
        assertThat(response.headers().get("x-seen-expect")).isEqualTo("none");
        assertThat(response.contentUtf8()).isEqualTo("upload");
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void stillRejectsUnsupportedExpectation(SessionProtocol protocol) {
        final AggregatedHttpResponse response =
                WebClient.of(manualServer.uri(protocol))
                         .execute(RequestHeaders.of(HttpMethod.POST, "/upload",
                                                    HttpHeaderNames.EXPECT, "unsupported"), "upload")
                         .aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.EXPECTATION_FAILED);
        assertThat(response.informationals()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { "/upload", "/decorated-upload" })
    void http1SendsContinueBeforeClientSendsBody(String path) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(manualServer.httpSocketAddress());
            socket.setSoTimeout(5000);
            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            socket.getOutputStream().write(("POST " + path + " HTTP/1.1\r\n" +
                                            "Host: localhost\r\n" +
                                            "Expect: 100-CoNtInUe\r\n" +
                                            "Content-Length: 6\r\n" +
                                            "Connection: close\r\n\r\n")
                                           .getBytes(StandardCharsets.US_ASCII));
            assertThat(reader.readLine()).isEqualTo("HTTP/1.1 100 Continue");
            assertThat(reader.readLine()).isEmpty();

            socket.getOutputStream().write("upload".getBytes(StandardCharsets.US_ASCII));
            final String response = readToEnd(reader);
            assertThat(response).startsWith("HTTP/1.1 201 Created\n");
            final String location = Arrays.stream(response.split("\n"))
                                            .filter(line -> line.startsWith("location: "))
                                            .map(line -> line.substring("location: ".length()))
                                            .findFirst().orElseThrow(AssertionError::new);
            final byte[] stored = Files.readAllBytes(uploadDirectory.resolve(location));
            assertThat(new String(stored, StandardCharsets.UTF_8)).isEqualTo("upload");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "/upload", "/decorated-upload" })
    void http1RejectsWhenOnlyHeadersHaveBeenSent(String path) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(manualServer.httpSocketAddress());
            socket.setSoTimeout(5000);
            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            socket.getOutputStream().write(("POST " + path + " HTTP/1.1\r\n" +
                                            "Host: localhost\r\n" +
                                            "Expect: 100-continue\r\n" +
                                            "Content-Length: " + (maxUploadLength + 1) + "\r\n\r\n")
                                           .getBytes(StandardCharsets.US_ASCII));
            final String response = readToEnd(reader);
            assertThat(response).startsWith("HTTP/1.1 413 Request Entity Too Large\n");
            assertThat(response).doesNotContain("100 Continue");
            assertThat(response).contains("connection: close\n");
        }
    }

    @ParameterizedTest
    @CsvSource({ "H1C,/upload", "H2C,/upload", "H1C,/decorated-upload", "H2C,/decorated-upload" })
    void rejectsUploadWithoutContentLengthBeforeSendingContinue(SessionProtocol protocol, String path) {
        final HttpRequest request = HttpRequest.streaming(
                RequestHeaders.of(HttpMethod.POST, path, HttpHeaderNames.EXPECT, expectation));
        final AggregatedHttpResponse response = WebClient.of(manualServer.uri(protocol))
                                                         .execute(request).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.LENGTH_REQUIRED);
        assertThat(response.informationals()).isEmpty();
    }

    private static void assertStoredUpload(AggregatedHttpResponse response, String content) throws IOException {
        final String location = response.headers().get(HttpHeaderNames.LOCATION);
        assertThat(location).isNotNull();
        final byte[] stored = Files.readAllBytes(uploadDirectory.resolve(location));
        assertThat(new String(stored, StandardCharsets.UTF_8)).isEqualTo(content);
    }

    private static String readToEnd(BufferedReader reader) throws Exception {
        final StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line).append('\n');
        }
        return response.toString();
    }
}
