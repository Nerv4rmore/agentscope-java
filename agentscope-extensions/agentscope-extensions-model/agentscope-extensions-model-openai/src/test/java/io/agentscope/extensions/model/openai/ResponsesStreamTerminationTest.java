/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.openai;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.extensions.model.openai.dto.ResponsesRequest;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Tests that the Responses API streaming response is terminated deterministically by the SSE
 * {@code [DONE]} sentinel, independent of the underlying HTTP connection lifecycle.
 *
 * <p>Regression test mirroring {@link OpenAIStreamTerminationTest} for the fork-specific
 * {@link ResponsesClient}. Before the fix, {@code [DONE]} was merely filtered out via
 * {@code .filter(data -> !data.equals("[DONE]"))}, so stream completion was deferred until the
 * underlying connection closed. Against an OpenAI-compatible gateway that keeps the connection
 * open (HTTP keep-alive) after emitting {@code [DONE]}, the stream would only complete when the
 * socket was eventually closed by an idle timeout, stalling the ReAct turn for up to a minute —
 * observable in the chat layer as a迟迟不到达的 {@code run.end} 事件.
 */
@Tag("unit")
@DisplayName("Responses Stream Termination Tests")
class ResponsesStreamTerminationTest {

    // Responses API emits typed events; two minimal chunks exercising the parse path.
    private static final String CHUNK_DELTA =
            "{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}";
    private static final String CHUNK_COMPLETED =
            "{\"type\":\"response.completed\"}";

    /**
     * A transport that emits two content chunks and the {@code [DONE]} sentinel, then never
     * completes ({@link Flux#never()}) — simulating a gateway that holds the connection open
     * after {@code [DONE]}.
     */
    private static HttpTransport keepAliveTransportAfterDone() {
        return new HttpTransport() {
            @Override
            public HttpResponse execute(HttpRequest request) {
                throw new UnsupportedOperationException("not used in this test");
            }

            @Override
            public Flux<String> stream(HttpRequest request) {
                return Flux.just(CHUNK_DELTA, CHUNK_COMPLETED, "[DONE]")
                        .concatWith(Flux.never());
            }

            @Override
            public void close() {}
        };
    }

    private static ResponsesRequest sampleRequest() {
        return ResponsesRequest.builder().model("gpt-5.6-luna").build();
    }

    @Test
    @DisplayName("Stream should complete on [DONE] even if the connection stays open")
    void streamCompletesOnDoneSentinelWithoutWaitingForConnectionClose() {
        ResponsesClient client = new ResponsesClient(keepAliveTransportAfterDone());

        StepVerifier.create(
                        client.stream(
                                "test-key",
                                "http://localhost",
                                sampleRequest(),
                                GenerateOptions.builder().build()))
                .expectNextCount(2)
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }
}
