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

package com.linecorp.armeria.common.sse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.HttpDecoder;
import com.linecorp.armeria.common.stream.StreamDecoderInput;
import com.linecorp.armeria.common.stream.StreamDecoderOutput;

import io.netty.buffer.ByteBuf;

final class ServerSentEventDecoder implements HttpDecoder<ServerSentEvent> {

    @Nullable
    private String buffer;

    @Override
    public void process(StreamDecoderInput in, StreamDecoderOutput<ServerSentEvent> out) throws Exception {
        final int readableBytes = in.readableBytes();
        final ByteBuf byteBuf = in.readBytes(readableBytes);
        final String data;
        if (buffer != null) {
            data = buffer + byteBuf.toString(StandardCharsets.UTF_8);
            buffer = null;
        } else {
            data = byteBuf.toString(StandardCharsets.UTF_8);
        }

        int begin = 0;
        for (;;) {
            final int delim = data.indexOf("\n\n", begin);
            if (delim < 0) {
                // not enough data
                buffer = data.substring(begin);
                return;
            } else {
                out.add(parse(data.substring(begin, delim)));
                begin = delim + 2;
            }
        }
    }

    private static ServerSentEvent parse(String rawEvent) {
        final String[] lines = rawEvent.split("\n");
        final ServerSentEventBuilder sseBuilder = ServerSentEvent.builder();
        for (String line : lines) {
            final int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            final String field = line.substring(0, separator).trim();
            final String value = line.substring(separator + 1).trim();
            switch (field) {
                case "id":
                    sseBuilder.id(value);
                    break;
                case "event":
                    sseBuilder.event(value);
                    break;
                case "retry":
                    try {
                        sseBuilder.retry(Duration.ofMillis(Long.parseLong(value)));
                    } catch (NumberFormatException e) {
                        // Ignore invalid retry value.
                    }
                    break;
                case "comment":
                    sseBuilder.comment(value);
                    break;
                case "data":
                    sseBuilder.data(value);
                    break;
                default:
                    // Ignore unknown field.
                    break;
            }
        }
        return sseBuilder.build();
    }
}
