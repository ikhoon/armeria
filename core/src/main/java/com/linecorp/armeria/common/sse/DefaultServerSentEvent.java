/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.common.sse;

import java.time.Duration;
import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A default implementation of the {@link ServerSentEvent} interface.
 */
final class DefaultServerSentEvent implements ServerSentEvent {

    /**
     * A line feed character which marks the end of a field in Server-Sent Events.
     */
    private static final char LINE_FEED = '\n';

    /**
     * An empty {@link ServerSentEvent} instance.
     */
    static final ServerSentEvent EMPTY = new DefaultServerSentEvent(null, null, null, null, null);

    @Nullable
    private final String id;
    @Nullable
    private final String event;
    @Nullable
    private final Duration retry;
    @Nullable
    private final String comment;
    @Nullable
    private final String data;

    DefaultServerSentEvent(@Nullable String id,
                           @Nullable String event,
                           @Nullable Duration retry,
                           @Nullable String comment,
                           @Nullable String data) {
        this.id = id;
        this.event = event;
        this.retry = retry;
        this.comment = comment;
        this.data = data;
    }

    @Nullable
    @Override
    public String id() {
        return id;
    }

    @Nullable
    @Override
    public String event() {
        return event;
    }

    @Nullable
    @Override
    public Duration retry() {
        return retry;
    }

    @Nullable
    @Override
    public String comment() {
        return comment;
    }

    @Nullable
    @Override
    public String data() {
        return data;
    }


    static String toEventStreamString(ServerSentEvent sse) {
        final StringBuilder sb = new StringBuilder();

        // Write a comment first because a user might want to explain his or her event at first line.
        final String comment = sse.comment();
        if (comment != null) {
            appendField(sb, "", comment, false);
        }

        final String id = sse.id();
        if (id != null) {
            appendField(sb, "id", id, true);
        }

        final String event = sse.event();
        if (event != null) {
            appendField(sb, "event", event, true);
        }

        final String data = sse.data();
        if (data != null) {
            appendField(sb, "data", data, true);
        }

        final Duration retry = sse.retry();
        if (retry != null) {
            // Reconnection time, in milliseconds.
            sb.append("retry:").append(retry.toMillis()).append(LINE_FEED);
        }

        return sb.length() == 0 ? "" : sb.append(LINE_FEED).toString();
    }

    private static void appendField(StringBuilder sb, String name, String value,
                                    boolean emitFieldForEmptyValue) {
        if (value.isEmpty()) {
            if (emitFieldForEmptyValue) {
                // Emit name only if the value is an empty string.
                sb.append(name).append(LINE_FEED);
            }
        } else {
            sb.append(name).append(':');

            final String[] values = value.split("\n");
            assert values.length > 0;
            if (values.length == 1) {
                sb.append(value);
            } else {
                final int len = values.length - 1;
                for (int i = 0; i < len; i++) {
                    sb.append(values[i]).append(LINE_FEED).append(name).append(':');
                }
                sb.append(values[len]);
            }
            sb.append(LINE_FEED);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, event, retry, comment, data);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof ServerSentEvent)) {
            return false;
        }

        final ServerSentEvent that = (ServerSentEvent) obj;
        return Objects.equals(id, that.id()) &&
               Objects.equals(event, that.event()) &&
               Objects.equals(retry, that.retry()) &&
               Objects.equals(comment, that.comment()) &&
               Objects.equals(data, that.data());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("id", id)
                          .add("event", event)
                          .add("retry", retry)
                          .add("comment", comment)
                          .add("data", data)
                          .toString();
    }
}
