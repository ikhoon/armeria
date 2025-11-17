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

import static io.modelcontextprotocol.spec.McpSchema.deserializeJsonRpcMessage;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.InvalidResponseHeadersException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.jsonrpc.JsonRpcMessage;
import com.linecorp.armeria.common.sse.ServerSentEvent;
import com.linecorp.armeria.common.stream.ByteStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.util.Exceptions;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.ClosedMcpTransportSession;
import io.modelcontextprotocol.spec.DefaultMcpTransportSession;
import io.modelcontextprotocol.spec.DefaultMcpTransportStream;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSession;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import io.modelcontextprotocol.spec.McpTransportStream;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.util.Utils;
import io.netty.util.AsciiString;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

public final class ArmeriaStreamableClientTransport implements McpClientTransport {

    private static final String MISSING_SESSION_ID = "[missing_session_id]";

    private static final Logger logger = LoggerFactory.getLogger(ArmeriaStreamableClientTransport.class);

    private static final String MESSAGE_EVENT_TYPE = "message";
    private static final String MCP_PROTOCOL_VERSION = ProtocolVersions.MCP_2025_06_18;

    private static final AsciiString MCP_PROTOCOL_HEADER = AsciiString.of("MCP-Protocol-Version");

    private final AtomicReference<McpTransportSession<Disposable>> activeSession = new AtomicReference<>();
    private final AtomicReference<Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>>> handler =
            new AtomicReference<>();
    private final AtomicReference<Consumer<Throwable>> exceptionHandler = new AtomicReference<>();

    private final McpJsonMapper jsonMapper;
    private final WebClient webClient;
    private final HttpHeaders defaultHeaders;
    private final URI baseUri;
    private final String endpoint;
    private final boolean openConnectionOnStartup;
    private final boolean resumableStreams;

    @Override
    public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
        return Mono.deferContextual(ctx -> {
            this.handler.set(handler);
            if (openConnectionOnStartup) {
                logger.debug("Eagerly opening connection on startup");
                return reconnect(null).onErrorComplete(t -> {
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
            final McpTransportSession<Disposable> transportSession = activeSession.get();
            final URI uri = Utils.resolveUri(baseUri, endpoint);

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
                                                        .headers(headersBuilder.build())
                                                        .execute()
                                                        .split();
            final CompletableFuture<Flux<JSONRPCMessage>> future = response.headers().thenApply(headers -> {
                // TODO(ikhoon): Use StreamMessage instead of Flux.
                if (isEventStream(headers)) {
                    logger.debug("Established SSE stream via GET");
                    return eventStream(stream, response.body());
                } else if (isNotAllowed(headers)) {
                    logger.debug(
                            "The server does not support SSE streams, using request-response mode.");
                    return Flux.empty();
                } else if (isNotFound(headers)) {
                    if (transportSession.sessionId().isPresent()) {
                        final String sessionIdRepresentation = sessionIdOrPlaceholder(transportSession);
                        return mcpSessionNotFoundError(sessionIdRepresentation);
                    } else {
                        return extractError(headers, response.body(), MISSING_SESSION_ID);
                    }
                } else {
                    final InvalidResponseHeadersException exception =
                            new InvalidResponseHeadersException(headers);
                    logger.info("Opening an SSE stream failed. This can be safely ignored.", exception);
                    return Flux.error(exception);
                }
            });
            final Disposable connection =
                    Mono.fromFuture(future)
                        .flux()
                        .flatMap(Function.identity())
                        .flatMap(jsonrpcMessage -> {
                            return handler.get().apply(Mono.just(jsonrpcMessage));
                        })
                        .onErrorComplete(t -> {
                            handleException(t);
                            return true;
                        })
                        .doFinally(s -> {
                            final Disposable ref = disposableRef.getAndSet(null);
                            if (ref != null) {
                                transportSession.removeConnection(ref);
                            }
                        })
                        .contextWrite(ctx)
                        .subscribe();

            disposableRef.set(connection);
            transportSession.addConnection(connection);
            return Mono.just(connection);
        });
    }

    @Override
    public Mono<Void> sendMessage(JSONRPCMessage message) {
        return Mono.create(sink -> {
            logger.debug("Sending message {}", message);
            // Here we attempt to initialize the client.
            // In case the server supports SSE, we will establish a long-running session
            // here and
            // listen for messages.
            // If it doesn't, nothing actually happens here, that's just the way it is...
            final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
            final McpTransportSession<Disposable> transportSession = activeSession.get();

            final SplitHttpResponse response =
                    webClient.prepare()
                             .post(endpoint)
                             .header(HttpHeaderNames.ACCEPT, MediaType.JSON)
                             .header(HttpHeaderNames.ACCEPT, MediaType.EVENT_STREAM)
                             .header(io.modelcontextprotocol.spec.HttpHeaders.PROTOCOL_VERSION,
                                     MCP_PROTOCOL_VERSION)
                             .headers(httpHeaders -> {
                                 transportSession.sessionId().ifPresent(
                                         id -> httpHeaders.add(
                                                 io.modelcontextprotocol.spec.HttpHeaders.MCP_SESSION_ID,
                                                 id));
                             })
                             .contentJson(message)
                             .execute().split();
            final CompletableFuture<Flux<JSONRPCResponse>> future = response.headers().thenApply(
                    headers -> {
                        if (transportSession
                                .markInitialized(
                                        headers.get(io.modelcontextprotocol.spec.HttpHeaders.MCP_SESSION_ID))) {
                            // Once we have a session, we try to open an async stream for
                            // the server to send notifications and requests out-of-band.
                            reconnect(null).contextWrite(sink.contextView()).subscribe();
                        }
                        final String sessionRepresentation = sessionIdOrPlaceholder(transportSession);

                        // The spec mentions only ACCEPTED, but the existing SDKs can return
                        // 200 OK for notifications
                        if (headers.status().isSuccess()) {
                            final MediaType contentType = headers.contentType();
                            final long contentLength = headers.contentLength();
                            // Existing SDKs consume notifications with no response body nor
                            // content type
                            if (contentType == null || contentLength == 0) {
                                logger.trace("Message was successfully sent via POST for session {}",
                                             sessionRepresentation);
                                // signal the caller that the message was successfully
                                // delivered
                                sink.success();
                                // communicate to downstream there is no streamed data coming
                                return Flux.<JSONRPCResponse>empty();
                            } else {
                                if (contentType.is(MediaType.EVENT_STREAM)) {
                                    logger.debug("Established SSE stream via POST");
                                    // communicate to caller that the message was delivered
                                    sink.success();
                                    // starting a stream
                                    return newEventStream(response.body(), sessionRepresentation);
                                } else if (contentType.isJson()) {
                                    logger.trace("Received response to POST for session {}",
                                                 sessionRepresentation);
                                    // communicate to caller the message was delivered
                                    sink.success();
                                    return directResponseFlux(message, response.body());
                                } else {
                                    logger.warn("Unknown media type {} returned for POST in session {}",
                                                contentType,
                                                sessionRepresentation);
                                    return Flux.error(new RuntimeException(
                                            "Unknown media type returned: " + contentType));
                                }
                            }
                        } else {
                            if (isNotFound(headers) && !sessionRepresentation.equals(MISSING_SESSION_ID)) {
                                return mcpSessionNotFoundError(sessionRepresentation);
                            }
                            return extractError(headers, response.body(), sessionRepresentation);
                        }
                    });
            final Disposable connection =
                    Mono.fromFuture(future).flux()
                        .flatMap(Function.identity())
                        .flatMap(jsonRpcMessage -> {
                            return handler.get().apply(Mono.just(
                                    jsonRpcMessage));
                        })
                        .onErrorComplete(t -> {
                            // handle the error first
                            handleException(t);
                            // inform the caller of sendMessage
                            sink.error(t);
                            return true;
                        })
                        .doFinally(s -> {
                            final Disposable ref = disposableRef.getAndSet(null);
                            if (ref != null) {
                                transportSession.removeConnection(ref);
                            }
                        })
                        .contextWrite(sink.contextView())
                        .subscribe();

            disposableRef.set(connection);
            transportSession.addConnection(connection);
        });
    }

    private void handleException(Throwable t) {
        t = Exceptions.peel(t);
        logger.debug("Handling exception for session {}", sessionIdOrPlaceholder(activeSession.get()), t);
        if (t instanceof McpTransportSessionNotFoundException) {
            final McpTransportSession<?> invalidSession = activeSession.getAndSet(createTransportSession());
            logger.warn("Server does not recognize session {}. Invalidating.", invalidSession.sessionId());
            invalidSession.close();
        }
        final Consumer<Throwable> handler = exceptionHandler.get();
        if (handler != null) {
            handler.accept(t);
        }
    }

    private McpTransportSession<Disposable> createTransportSession() {
        final Function<String, Publisher<Void>> onClose = sessionId -> {
            if (sessionId == null) {
                return StreamMessage.of();
            }
            return webClient.prepare()
                            .delete(endpoint)
                            .header(io.modelcontextprotocol.spec.HttpHeaders.MCP_SESSION_ID,
                                    sessionId)
                            .header(io.modelcontextprotocol.spec.HttpHeaders.PROTOCOL_VERSION,
                                    MCP_PROTOCOL_VERSION)
                            .execute()
                            .<Void>map(ignored -> null)
                            .recoverAndResume(ex -> {
                                logger.warn("Got error when closing transport", ex);
                                return StreamMessage.of();
                            });
        };
        return new DefaultMcpTransportSession(onClose);
    }

    private static McpTransportSession<Disposable> createClosedSession(
            McpTransportSession<Disposable> existingSession) {
        final String existingSessionId = Optional.ofNullable(existingSession)
                                                 .filter(session -> !(session instanceof ClosedMcpTransportSession<Disposable>))
                                                 .flatMap(McpTransportSession::sessionId)
                                                 .orElse(null);
        return new ClosedMcpTransportSession<>(existingSessionId);
    }

    private Flux<JSONRPCMessage> eventStream(@Nullable McpTransportStream<Disposable> stream,
                                             ByteStreamMessage body) {
        final McpTransportStream<Disposable> sessionStream;
        if (stream != null) {
            sessionStream = stream;
        } else {
            sessionStream = new DefaultMcpTransportStream<>(resumableStreams, this::reconnect);
        }
        logger.debug("Connected stream {}", sessionStream.streamId());
        final StreamMessage<Tuple2<Optional<String>, Iterable<JSONRPCMessage>>> messages =
                body.decode(ServerSentEvent.decoder()).map(this::parse);
        return Flux.from(sessionStream.consumeSseStream(messages));
    }

    private Flux<McpSchema.JSONRPCMessage> newEventStream(ByteStreamMessage body,
                                                          String sessionRepresentation) {
        final McpTransportStream<Disposable> sessionStream = new DefaultMcpTransportStream<>(resumableStreams,
                                                                                             this::reconnect);
        logger.trace("Sent POST and opened a stream ({}) for session {}", sessionStream.streamId(),
                     sessionRepresentation);
        return eventStream(sessionStream, body);
    }

    private Tuple2<Optional<String>, Iterable<JSONRPCMessage>> parse(ServerSentEvent event) {
        if (MESSAGE_EVENT_TYPE.equals(event.event())) {
            try {
                final JSONRPCMessage message = deserializeJsonRpcMessage(jsonMapper, event.data());
                return Tuples.of(Optional.ofNullable(event.id()), List.of(message));
            } catch (IOException ioException) {
                throw new McpTransportException("Error parsing JSON-RPC message: " + event.data(), ioException);
            }
        } else {
            logger.debug("Received SSE event with type: {}", event);
            return Tuples.of(Optional.empty(), List.of());
        }
    }

    private static boolean isEventStream(ResponseHeaders headers) {
        final MediaType contentType = headers.contentType();
        return headers.status().isSuccess() && contentType != null && contentType.is(MediaType.EVENT_STREAM);
    }

    private static boolean isNotAllowed(ResponseHeaders headers) {
        return headers.status() == HttpStatus.METHOD_NOT_ALLOWED;
    }

    private static boolean isNotFound(ResponseHeaders headers) {
        return headers.status() == HttpStatus.NOT_FOUND;
    }

    private static String sessionIdOrPlaceholder(McpTransportSession<?> transportSession) {
        return transportSession.sessionId().orElse(MISSING_SESSION_ID);
    }

    private Flux<McpSchema.JSONRPCMessage> directResponseFlux(McpSchema.JSONRPCMessage sentMessage,
                                                              ByteStreamMessage body) {
        final CompletableFuture<Flux<JSONRPCMessage>> future = body.collectBytes().thenApply(bytes -> {
            final String responseMessage = new String(bytes, StandardCharsets.UTF_8);
            try {
                if (sentMessage instanceof JSONRPCNotification) {
                    logger.warn("Notification: {} received non-compliant response: {}", sentMessage,
                                Utils.hasText(responseMessage) ? responseMessage : "[empty]");
                    return Flux.empty();
                } else {
                    final JSONRPCMessage jsonRpcResponse = deserializeJsonRpcMessage(jsonMapper,
                                                                                     responseMessage);
                    return Flux.fromIterable(ImmutableList.of(jsonRpcResponse));
                }
            } catch (IOException e) {
                return Flux.error(new McpTransportException(e));
            }
        });
        return Mono.fromFuture(future).flux().flatMap(Function.identity());
    }

    private static Flux<JSONRPCMessage> mcpSessionNotFoundError(String sessionRepresentation) {
        logger.warn("Session {} was not found on the MCP server", sessionRepresentation);
        // inform the stream/connection subscriber
        return Flux.error(new McpTransportSessionNotFoundException(sessionRepresentation));
    }

    private Flux<JSONRPCMessage> extractError(ResponseHeaders headers, ByteStreamMessage body,
                                              String sessionRepresentation) {
        final CompletableFuture<JSONRPCMessage> future = body.collectBytes().handle((bytes, cause) -> {
            if (cause != null) {
                cause = Exceptions.peel(cause);
                throw new McpTransportException("Sending request failed. " + cause.getMessage(), cause);
            }

            Exception toPropagate;
            try {
                final JSONRPCResponse rpcResponse = jsonMapper.readValue(bytes, JSONRPCResponse.class);
                final JSONRPCError jsonRpcError = rpcResponse.error();
                if (jsonRpcError != null) {
                    toPropagate = new McpError(jsonRpcError);
                } else {
                    toPropagate = new McpTransportException("Can't parse the jsonResponse " + rpcResponse);
                }
            } catch (IOException ex) {
                toPropagate = new McpTransportException("Can't parse the jsonResponse ", ex);
                logger.debug("Received content together with {} HTTP code response: {}",
                             headers.status().code(), body);
            }

            // Some implementations can return 400 when presented with a
            // session id that it doesn't know about, so we will
            // invalidate the session
            // https://github.com/modelcontextprotocol/typescript-sdk/issues/389
            if (headers.status() == HttpStatus.BAD_REQUEST) {
                if (!sessionRepresentation.equals(MISSING_SESSION_ID)) {
                    throw new McpTransportSessionNotFoundException(sessionRepresentation, toPropagate);
                }
                throw new McpTransportException(
                        "Received 400 BAD REQUEST for session " + sessionRepresentation + ". " +
                        toPropagate.getMessage(), toPropagate);
            }
            return Exceptions.throwUnsafely(toPropagate);
        });
        return Mono.fromFuture(future).flux();
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> handler) {
        logger.debug("Exception handler registered");
        exceptionHandler.set(handler);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.defer(() -> {
            logger.debug("Graceful close triggered");
            final McpTransportSession<Disposable> currentSession = activeSession.getAndUpdate(
                    ArmeriaStreamableClientTransport::createClosedSession);
            if (currentSession != null) {
                return Mono.from(currentSession.closeGracefully());
            }
            return Mono.empty();
        });
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return null;
    }
}
