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
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
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

    @RegisterExtension
    static final ServerExtension manualServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.auto100Continue(false);
            sb.service("/service", (ctx, req) -> {
                if (req.headers().contains("x-reject")) {
                    return reject(ctx, req);
                }
                final HttpResponse response = echo(req);
                if (expectsContinue(req)) {
                    return HttpResponse.of(continueHeaders, response);
                }
                return response;
            });

            final HttpService service = (ctx, req) -> echo(req);
            sb.service("/decorator", service.decorate((delegate, ctx, req) -> {
                if (req.headers().contains("x-reject")) {
                    return reject(ctx, req);
                }
                final HttpResponse response = delegate.serve(ctx, req);
                if (expectsContinue(req)) {
                    return HttpResponse.of(continueHeaders, response);
                }
                return response;
            }));
        }
    };

    @RegisterExtension
    static final ServerExtension automaticServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/echo", (ctx, req) -> echo(req));
        }
    };

    private static boolean expectsContinue(HttpRequest req) {
        return "100-continue".equalsIgnoreCase(req.headers().get(HttpHeaderNames.EXPECT));
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

    private static HttpResponse reject(ServiceRequestContext ctx, HttpRequest req) {
        if (ctx.sessionProtocol().isExplicitHttp1()) {
            ctx.initiateConnectionShutdown();
        }
        return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE, MediaType.PLAIN_TEXT_UTF_8,
                               req.headers().get(HttpHeaderNames.EXPECT, "none"));
    }

    @ParameterizedTest
    @CsvSource({ "H1C,/service", "H2C,/service", "H1C,/decorator", "H2C,/decorator" })
    void acceptsAfterSendingManualContinue(SessionProtocol protocol, String path) {
        final WebClient client = WebClient.of(manualServer.uri(protocol));
        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.POST, path,
                                                 HttpHeaderNames.EXPECT, expectation), "upload")
                      .aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.informationals()).containsExactly(continueHeaders);
        assertThat(response.headers().get("x-seen-expect")).isEqualTo(expectation);
        assertThat(response.contentUtf8()).isEqualTo("upload");
    }

    @ParameterizedTest
    @CsvSource({ "H1C,/service", "H2C,/service", "H1C,/decorator", "H2C,/decorator" })
    void rejectsWithoutContinueOrSendingBody(SessionProtocol protocol, String path) {
        final WebClient client = WebClient.of(manualServer.uri(protocol));
        final AtomicInteger producedBodyChunks = new AtomicInteger();
        final HttpRequest request =
                HttpRequest.of(RequestHeaders.of(HttpMethod.POST, path,
                                                 HttpHeaderNames.EXPECT, "100-continue",
                                                 "x-reject", "true"), HttpData.ofUtf8("upload"))
                           .mapData(data -> {
                               producedBodyChunks.incrementAndGet();
                               return data;
                           });
        final AggregatedHttpResponse response = client.execute(request).aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.informationals()).isEmpty();
        assertThat(response.contentUtf8()).isEqualTo("100-continue");
        assertThat(producedBodyChunks).hasValue(0);
        final AggregatedHttpResponse nextResponse = client.post(path, "next upload").aggregate().join();
        assertThat(nextResponse.status()).isEqualTo(HttpStatus.OK);
        assertThat(nextResponse.contentUtf8()).isEqualTo("next upload");
    }

    @ParameterizedTest
    @CsvSource({ "H1C,/service", "H2C,/service", "H1C,/decorator", "H2C,/decorator" })
    void doesNotSendContinueWithoutExpectation(SessionProtocol protocol, String path) {
        final AggregatedHttpResponse response = WebClient.of(manualServer.uri(protocol))
                                                         .post(path, "upload").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.informationals()).isEmpty();
        assertThat(response.contentUtf8()).isEqualTo("upload");
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
                         .execute(RequestHeaders.of(HttpMethod.POST, "/service",
                                                    HttpHeaderNames.EXPECT, "unsupported"), "upload")
                         .aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.EXPECTATION_FAILED);
        assertThat(response.informationals()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { "/service", "/decorator" })
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
            assertThat(response).startsWith("HTTP/1.1 200 OK\n");
            assertThat(response).contains("x-seen-expect: 100-CoNtInUe\n");
            assertThat(response).endsWith("\nupload\n");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "/service", "/decorator" })
    void http1RejectsWhenOnlyHeadersHaveBeenSent(String path) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(manualServer.httpSocketAddress());
            socket.setSoTimeout(5000);
            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            socket.getOutputStream().write(("POST " + path + " HTTP/1.1\r\n" +
                                            "Host: localhost\r\n" +
                                            "Expect: 100-continue\r\n" +
                                            "Content-Length: 6\r\n" +
                                            "x-reject: true\r\n\r\n")
                                           .getBytes(StandardCharsets.US_ASCII));
            final String response = readToEnd(reader);
            assertThat(response).startsWith("HTTP/1.1 503 Service Unavailable\n");
            assertThat(response).doesNotContain("100 Continue");
            assertThat(response).contains("connection: close\n");
            assertThat(response).endsWith("\n100-continue\n");
        }
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
