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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.stream.StreamMessage;

import reactor.test.StepVerifier;

class ServerSentEventDecoderTest {

    @Test
    void singleEventInOneChunk() {
        final String sse = "id: 1\n" +
                           "event: update\n" +
                           "data: hello\n\n";

        final StreamMessage<HttpData> stream = StreamMessage.of(HttpData.ofUtf8(sse));
        final StreamMessage<ServerSentEvent> decoded = stream.decode(ServerSentEvent.decoder());

        StepVerifier.create(decoded)
                    .assertNext(event -> {
                        assertThat(event.id()).isEqualTo("1");
                        assertThat(event.event()).isEqualTo("update");
                        assertThat(event.data()).isEqualTo("hello");
                        assertThat(event.retry()).isNull();
                        assertThat(event.comment()).isNull();
                    })
                    .expectComplete()
                    .verify();
    }

    @Test
    void multipleEventsInOneChunk() {
        final String sse = "id: 1\n" +
                           "data: first\n\n" +
                           "id: 2\n" +
                           "data: second\n\n" +
                           "id: 3\n" +
                           "data: third\n\n";

        final StreamMessage<HttpData> stream = StreamMessage.of(HttpData.ofUtf8(sse));
        final StreamMessage<ServerSentEvent> decoded = stream.decode(ServerSentEvent.decoder());

        StepVerifier.create(decoded)
                    .assertNext(event -> {
                        assertThat(event.id()).isEqualTo("1");
                        assertThat(event.data()).isEqualTo("first");
                    })
                    .assertNext(event -> {
                        assertThat(event.id()).isEqualTo("2");
                        assertThat(event.data()).isEqualTo("second");
                    })
                    .assertNext(event -> {
                        assertThat(event.id()).isEqualTo("3");
                        assertThat(event.data()).isEqualTo("third");
                    })
                    .expectComplete()
                    .verify();
    }

    @Test
    void singleEventSplitAcrossMultipleChunks() {
        final String chunk1 = "id: 1\n";
        final String chunk2 = "event: update\n";
        final String chunk3 = "data: hello";
        final String chunk4 = "\n\n";

        final StreamMessage<HttpData> stream = StreamMessage.of(
                HttpData.ofUtf8(chunk1),
                HttpData.ofUtf8(chunk2),
                HttpData.ofUtf8(chunk3),
                HttpData.ofUtf8(chunk4)
        );
        final StreamMessage<ServerSentEvent> decoded = stream.decode(ServerSentEvent.decoder());

        StepVerifier.create(decoded)
                    .assertNext(event -> {
                        assertThat(event.id()).isEqualTo("1");
                        assertThat(event.event()).isEqualTo("update");
                        assertThat(event.data()).isEqualTo("hello");
                    })
                    .expectComplete()
                    .verify();
    }

    @Test
    void eventSplitInTheMiddleOfDelimiter() {
        // Split right between the two newlines of "\n\n"
        final String chunk1 = "id: 1\ndata: hello\n";
        final String chunk2 = "\nid: 2\ndata: world\n\n";

        final StreamMessage<HttpData> stream = StreamMessage.of(
                HttpData.ofUtf8(chunk1),
                HttpData.ofUtf8(chunk2)
        );
        final StreamMessage<ServerSentEvent> decoded = stream.decode(ServerSentEvent.decoder());

        StepVerifier.create(decoded)
                    .assertNext(event -> {
                        assertThat(event.id()).isEqualTo("1");
                        assertThat(event.data()).isEqualTo("hello");
                    })
                    .assertNext(event -> {
                        assertThat(event.id()).isEqualTo("2");
                        assertThat(event.data()).isEqualTo("world");
                    })
                    .expectComplete()
                    .verify();
    }

    @Test
    void allFields() {
        final String sse = "id: 123\n" +
                           "event: notification\n" +
                           "retry: 5000\n" +
                           "comment: test comment\n" +
                           "data: test data\n\n";

        final StreamMessage<HttpData> stream = StreamMessage.of(HttpData.ofUtf8(sse));
        final StreamMessage<ServerSentEvent> decoded = stream.decode(ServerSentEvent.decoder());

        StepVerifier.create(decoded)
                    .assertNext(event -> {
                        assertThat(event.id()).isEqualTo("123");
                        assertThat(event.event()).isEqualTo("notification");
                        assertThat(event.retry()).isEqualTo(Duration.ofMillis(5000));
                        assertThat(event.comment()).isEqualTo("test comment");
                        assertThat(event.data()).isEqualTo("test data");
                    })
                    .expectComplete()
                    .verify();
    }

    @Test
    void dataOnly() {
        final String sse = "data: simple message\n\n";

        final StreamMessage<HttpData> stream = StreamMessage.of(HttpData.ofUtf8(sse));
        final StreamMessage<ServerSentEvent> decoded = stream.decode(ServerSentEvent.decoder());

        StepVerifier.create(decoded)
                    .assertNext(event -> {
                        assertThat(event.id()).isNull();
                        assertThat(event.event()).isNull();
                        assertThat(event.retry()).isNull();
                        assertThat(event.comment()).isNull();
                        assertThat(event.data()).isEqualTo("simple message");
                    })
                    .expectComplete()
                    .verify();
    }

    @Test
    void emptyEvent() {
        final String sse = "\n\n";

        final StreamMessage<HttpData> stream = StreamMessage.of(HttpData.ofUtf8(sse));
        final StreamMessage<ServerSentEvent> decoded = stream.decode(ServerSentEvent.decoder());

        StepVerifier.create(decoded)
                    .assertNext(event -> {
                        assertThat(event.id()).isNull();
                        assertThat(event.event()).isNull();
                        assertThat(event.retry()).isNull();
                        assertThat(event.comment()).isNull();
                        assertThat(event.data()).isNull();
                    })
                    .expectComplete()
                    .verify();
    }

    @Test
    void multilineDataInSingleChunk() {
        final String sse = "data: line 1\n" +
                           "data: line 2\n" +
                           "data: line 3\n\n";

        final StreamMessage<HttpData> stream = StreamMessage.of(HttpData.ofUtf8(sse));
        final StreamMessage<ServerSentEvent> decoded = stream.decode(ServerSentEvent.decoder());

        StepVerifier.create(decoded)
                    .assertNext(event -> {
                        // Note: The decoder takes the last data line
                        assertThat(event.data()).isEqualTo("line 3");
                    })
                    .expectComplete()
                    .verify();
    }

    @Test
    void incompleteEventBuffered() {
        // This tests that incomplete events are buffered properly
        final String chunk1 = "id: 1\ndata: incomplete";
        // No delimiter at the end, so should be buffered

        final StreamMessage<HttpData> stream = StreamMessage.of(HttpData.ofUtf8(chunk1));
        final StreamMessage<ServerSentEvent> decoded = stream.decode(ServerSentEvent.decoder());

        StepVerifier.create(decoded)
                    .expectComplete()
                    .verify();
    }

    @Test
    void invalidRetryValueIgnored() {
        final String sse = "id: 1\n" +
                           "retry: not-a-number\n" +
                           "data: test\n\n";

        final StreamMessage<HttpData> stream = StreamMessage.of(HttpData.ofUtf8(sse));
        final StreamMessage<ServerSentEvent> decoded = stream.decode(ServerSentEvent.decoder());

        StepVerifier.create(decoded)
                    .assertNext(event -> {
                        assertThat(event.id()).isEqualTo("1");
                        assertThat(event.retry()).isNull(); // Invalid retry should be ignored
                        assertThat(event.data()).isEqualTo("test");
                    })
                    .expectComplete()
                    .verify();
    }

    @Test
    void unknownFieldsIgnored() {
        final String sse = "id: 1\n" +
                           "unknown: field\n" +
                           "custom: value\n" +
                           "data: test\n\n";

        final StreamMessage<HttpData> stream = StreamMessage.of(HttpData.ofUtf8(sse));
        final StreamMessage<ServerSentEvent> decoded = stream.decode(ServerSentEvent.decoder());

        StepVerifier.create(decoded)
                    .assertNext(event -> {
                        assertThat(event.id()).isEqualTo("1");
                        assertThat(event.data()).isEqualTo("test");
                    })
                    .expectComplete()
                    .verify();
    }

    @Test
    void fieldWithoutColonIgnored() {
        final String sse = "nocolon\n" +
                           "id: 1\n" +
                           "data: test\n\n";

        final StreamMessage<HttpData> stream = StreamMessage.of(HttpData.ofUtf8(sse));
        final StreamMessage<ServerSentEvent> decoded = stream.decode(ServerSentEvent.decoder());

        StepVerifier.create(decoded)
                    .assertNext(event -> {
                        assertThat(event.id()).isEqualTo("1");
                        assertThat(event.data()).isEqualTo("test");
                    })
                    .expectComplete()
                    .verify();
    }

    @Test
    void whitespaceHandling() {
        final String sse = "id:  spaced-id  \n" +
                           "event:  spaced-event  \n" +
                           "data:  spaced-data  \n\n";

        final StreamMessage<HttpData> stream = StreamMessage.of(HttpData.ofUtf8(sse));
        final StreamMessage<ServerSentEvent> decoded = stream.decode(ServerSentEvent.decoder());

        StepVerifier.create(decoded)
                    .assertNext(event -> {
                        // Values are trimmed
                        assertThat(event.id()).isEqualTo("spaced-id");
                        assertThat(event.event()).isEqualTo("spaced-event");
                        assertThat(event.data()).isEqualTo("spaced-data");
                    })
                    .expectComplete()
                    .verify();
    }

    @Test
    void complexChunkingScenario() {
        // Test various chunking patterns
        final String chunk1 = "id: 1\ndata: fi";
        final String chunk2 = "rst\n\nid: 2";
        final String chunk3 = "\ndata: second\n";
        final String chunk4 = "\nid: 3\ndata: third\n\n";

        final StreamMessage<HttpData> stream = StreamMessage.of(
                HttpData.ofUtf8(chunk1),
                HttpData.ofUtf8(chunk2),
                HttpData.ofUtf8(chunk3),
                HttpData.ofUtf8(chunk4)
        );
        final StreamMessage<ServerSentEvent> decoded = stream.decode(ServerSentEvent.decoder());

        StepVerifier.create(decoded)
                    .assertNext(event -> {
                        assertThat(event.id()).isEqualTo("1");
                        assertThat(event.data()).isEqualTo("first");
                    })
                    .assertNext(event -> {
                        assertThat(event.id()).isEqualTo("2");
                        assertThat(event.data()).isEqualTo("second");
                    })
                    .assertNext(event -> {
                        assertThat(event.id()).isEqualTo("3");
                        assertThat(event.data()).isEqualTo("third");
                    })
                    .expectComplete()
                    .verify();
    }

    @Test
    void byteByByteChunking() {
        final String sse = "id: 1\ndata: test\n\n";

        // Split into individual bytes
        final HttpData[] chunks = new HttpData[sse.length()];
        for (int i = 0; i < sse.length(); i++) {
            chunks[i] = HttpData.ofUtf8(String.valueOf(sse.charAt(i)));
        }

        final StreamMessage<HttpData> stream = StreamMessage.of(chunks);
        final StreamMessage<ServerSentEvent> decoded = stream.decode(ServerSentEvent.decoder());

        StepVerifier.create(decoded)
                    .assertNext(event -> {
                        assertThat(event.id()).isEqualTo("1");
                        assertThat(event.data()).isEqualTo("test");
                    })
                    .expectComplete()
                    .verify();
    }

    @Test
    void multipleEventsWithVariousChunking() {
        final String chunk1 = "id: 1\ndata: first\n\nid: 2\ndata: ";
        final String chunk2 = "second\n\nid: 3";
        final String chunk3 = "\ndata: third\n\n";

        final StreamMessage<HttpData> stream = StreamMessage.of(
                HttpData.ofUtf8(chunk1),
                HttpData.ofUtf8(chunk2),
                HttpData.ofUtf8(chunk3)
        );
        final StreamMessage<ServerSentEvent> decoded = stream.decode(ServerSentEvent.decoder());

        StepVerifier.create(decoded)
                    .assertNext(event -> {
                        assertThat(event.id()).isEqualTo("1");
                        assertThat(event.data()).isEqualTo("first");
                    })
                    .assertNext(event -> {
                        assertThat(event.id()).isEqualTo("2");
                        assertThat(event.data()).isEqualTo("second");
                    })
                    .assertNext(event -> {
                        assertThat(event.id()).isEqualTo("3");
                        assertThat(event.data()).isEqualTo("third");
                    })
                    .expectComplete()
                    .verify();
    }

    @Test
    void emptyFieldValue() {
        final String sse = "id: \n" +
                           "event: \n" +
                           "data: \n\n";

        final StreamMessage<HttpData> stream = StreamMessage.of(HttpData.ofUtf8(sse));
        final StreamMessage<ServerSentEvent> decoded = stream.decode(ServerSentEvent.decoder());

        StepVerifier.create(decoded)
                    .assertNext(event -> {
                        assertThat(event.id()).isEmpty();
                        assertThat(event.event()).isEmpty();
                        assertThat(event.data()).isEmpty();
                    })
                    .expectComplete()
                    .verify();
    }
}
