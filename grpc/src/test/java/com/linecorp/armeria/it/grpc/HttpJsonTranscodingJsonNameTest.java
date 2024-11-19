/*
 * Copyright 2024 LINE Corporation
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
 *
 */

package com.linecorp.armeria.it.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;
import testing.grpc.TestJsonNameServiceGrpc.TestJsonNameServiceImplBase;
import testing.grpc.TranscodingJsonName.GetJsonNameRequest;
import testing.grpc.TranscodingJsonName.JsonNameResponse;

class HttpJsonTranscodingJsonNameTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final GrpcService grpcService = GrpcService.builder()
                                                       .addService(new TestJsonNameService())
                                                       .enableUnframedRequests(true)
                                                       .enableHttpJsonTranscoding(true)
                                                       .build();
            sb.service(grpcService);
        }
    };

    @Test
    void supportJsonName() {
        final QueryParams query =
                QueryParams.builder()
                           .add("query_parameter", "query")
                           .add("second_query", "query2")
                           .add("parent.child_field", "childField")
                           .add("parent.second_field", "childField2")
                           .build();

        final JsonNode response =
                server.blockingWebClient()
                      .prepare()
                      .get("/json_name/messages/1")
                      .queryParams(query)
                      .asJson(JsonNode.class)
                      .execute()
                      .content();
        assertThat(response.get("text").asText()).isEqualTo("1:query:query2:childField:childField2");
    }

    private static class TestJsonNameService extends TestJsonNameServiceImplBase {

        @Override
        public void getMessage(GetJsonNameRequest request, StreamObserver<JsonNameResponse> responseObserver) {
            final String text = request.getMessageId() + ':' +
                                request.getQueryParameter() + ':' +
                                request.getQueryField1() + ':' +
                                request.getParentField().getChildField() + ':' +
                                request.getParentField().getChildField2();
            responseObserver.onNext(JsonNameResponse.newBuilder().setText(text).build());
            responseObserver.onCompleted();
        }
    }

}
