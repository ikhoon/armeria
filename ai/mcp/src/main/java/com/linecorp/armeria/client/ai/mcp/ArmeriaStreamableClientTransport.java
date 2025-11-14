/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.client.ai.mcp;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SplitHttpResponse;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpTransportSession;
import io.modelcontextprotocol.spec.McpTransportStream;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.util.Utils;
import io.netty.util.AsciiString;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

public final class ArmeriaStreamableClientTransport implements McpClientTransport {

    private static final Logger logger = LoggerFactory.getLogger(ArmeriaStreamableClientTransport.class);

    private static final String MCP_PROTOCOL_VERSION = ProtocolVersions.MCP_2025_06_18;

    private static final AsciiString MCP_PROTOCOL_HEADER = AsciiString.of("MCP-Protocol-Version");

    private final AtomicReference<McpTransportSession<Disposable>> activeSession = new AtomicReference<>();
    private final AtomicReference<Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>>> handler =
            new AtomicReference<>();

    private final WebClient webClient;
    private final HttpHeaders defaultHeaders;
    private final URI baseUri;
    private final String endpoint;
    private final boolean openConnectionOnStartup;

    @Override
    public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
        return Mono.deferContextual(ctx -> {
            this.handler.set(handler);
            if (this.openConnectionOnStartup) {
                logger.debug("Eagerly opening connection on startup");
                return this.reconnect(null).onErrorComplete(t -> {
                    logger.warn("Eager connect failed ", t);
                    return true;
                }).then();
            }
            return Mono.empty();
        });
    }

    private Mono<Disposable> reconnect(McpTransportStream<Disposable> stream) {
        return Mono.deferContextual(ctx -> {

            if (stream != null) {
                logger.debug("Reconnecting stream {} with lastId {}", stream.streamId(), stream.lastId());
            } else {
                logger.debug("Reconnecting with no prior stream");
            }

            final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
            final McpTransportSession<Disposable> transportSession = this.activeSession.get();
            URI uri = Utils.resolveUri(this.baseUri, this.endpoint);

            // TODO(ikhoon): Set this to the default headers.
            final HttpHeadersBuilder headersBuilder =
                    HttpHeaders.builder()
                               .setObject(HttpHeaderNames.ACCEPT, MediaType.EVENT_STREAM)
                               .set(io.modelcontextprotocol.spec.HttpHeaders.PROTOCOL_VERSION,
                                    MCP_PROTOCOL_VERSION);

            transportSession.sessionId().ifPresent(id -> {
                headersBuilder.add(io.modelcontextprotocol.spec.HttpHeaders.MCP_SESSION_ID, id);
            });
            if (stream != null) {
                stream.lastId().ifPresent(id -> {
                    headersBuilder.add(io.modelcontextprotocol.spec.HttpHeaders.LAST_EVENT_ID, id);
                });
            }
            final SplitHttpResponse response = webClient.prepare()
                                                        .get(endpoint)
                                                        .header(HttpHeaderNames.ACCEPT, MediaType.EVENT_STREAM)
                                                        .header(MCP_PROTOCOL_HEADER, MCP_PROTOCOL_VERSION)
                                                        .execute()
                                                        .split();
            response.headers().thenApply(headers -> {

            })

        });
    }

    @Override
    public Mono<Void> closeGracefully() {
        return null;
    }

    @Override
    public Mono<Void> sendMessage(JSONRPCMessage message) {
        return null;
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return null;
    }
}
