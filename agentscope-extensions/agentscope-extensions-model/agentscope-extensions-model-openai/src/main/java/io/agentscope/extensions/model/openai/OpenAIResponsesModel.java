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

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelContextWindows;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ModelUtils;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.model.transport.OkHttpTransport;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.extensions.model.openai.dto.ResponsesInputItem;
import io.agentscope.extensions.model.openai.dto.ResponsesRequest;
import io.agentscope.extensions.model.openai.dto.ResponsesResponse;
import io.agentscope.extensions.model.openai.formatter.OpenAIResponsesFormatter;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * OpenAI model using the Responses API ({@code /v1/responses}).
 *
 * <p>This implementation targets the newer Responses API which natively supports reasoning
 * models (e.g., gpt-5.6-luna) with function tools — a combination that is rejected by the
 * Chat Completions endpoint. It is structurally parallel to {@link OpenAIChatModel} but
 * uses Responses-specific DTOs, formatter, and client.
 *
 * <p>Features:
 * <ul>
 *   <li>Streaming and non-streaming modes</li>
 *   <li>Function tool calling (with streaming argument deltas)</li>
 *   <li>Reasoning effort configuration ({@code reasoning.effort})</li>
 *   <li>Stateless operation ({@code store=false}) — the agent manages conversation context</li>
 *   <li>Compatible with OpenAI official and OpenRouter (drop-in compatible)</li>
 * </ul>
 *
 * <p>System messages are extracted as the request's {@code instructions} field; all other
 * messages are converted to the {@code input} array.
 */
public class OpenAIResponsesModel extends ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(OpenAIResponsesModel.class);

    private final ResponsesClient client;
    private final OpenAIResponsesFormatter formatter;
    private final GenerateOptions configuredOptions;

    private OpenAIResponsesModel(
            ResponsesClient client,
            OpenAIResponsesFormatter formatter,
            GenerateOptions configuredOptions) {
        this.client = client != null ? client : new ResponsesClient();
        this.formatter = formatter != null ? formatter : new OpenAIResponsesFormatter();
        this.configuredOptions = configuredOptions;
    }

    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return ModelUtils.applyTimeoutAndRetry(
                doStream0(messages, tools, options),
                options,
                configuredOptions,
                configuredOptions.getModelName(),
                "openai-responses");
    }

    protected Flux<ChatResponse> doStream0(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {

        GenerateOptions effectiveOptions = GenerateOptions.mergeOptions(options, configuredOptions);

        if (effectiveOptions == null || effectiveOptions.getModelName() == null) {
            throw new IllegalArgumentException(
                    "modelName must be specified in GenerateOptions or configured in builder");
        }

        String modelName = effectiveOptions.getModelName();
        log.debug("OpenAI Responses API call: model={}", modelName);

        boolean stream =
                effectiveOptions.getStream() != null ? effectiveOptions.getStream() : false;

        String apiKey = effectiveOptions.getApiKey();
        String baseUrl = effectiveOptions.getBaseUrl();

        Instant start = Instant.now();

        // Format messages: extract instructions (first system message), convert rest to input items
        OpenAIResponsesFormatter.FormattedInput formatted =
                formatter.formatWithInstructions(messages);
        String instructions = formatted.instructions();
        List<ResponsesInputItem> inputItems = formatted.inputItems();

        // Build request
        ResponsesRequest.Builder requestBuilder =
                ResponsesRequest.builder().model(modelName).stream(stream);

        if (instructions != null && !instructions.isBlank()) {
            requestBuilder.instructions(instructions);
        }
        requestBuilder.input(inputItems.isEmpty() ? "" : inputItems);

        ResponsesRequest request = requestBuilder.build();

        // Apply tools
        if (tools != null && !tools.isEmpty()) {
            formatter.applyTools(request, tools);
        }

        // Apply generation options (temperature, reasoning, etc.)
        formatter.applyOptions(request, effectiveOptions, null);

        // Apply tool choice if specified
        if (effectiveOptions.getToolChoice() != null) {
            formatter.applyToolChoice(request, effectiveOptions.getToolChoice());
        }

        // Make the API call
        if (stream) {
            // 使用 handle 而非 map+filter：Responses API 流式事件中大量结构性事件
            // (response.created/in_progress/output_item.done 等) 在 parser 中返回 null，
            // 而 Reactor 的 map 操作符不允许返回 null，handle 可安全跳过这些事件。
            return client.stream(apiKey, baseUrl, request, effectiveOptions)
                    .handle(
                            (event, sink) -> {
                                ChatResponse chatResponse = formatter.parseResponse(event, start);
                                if (chatResponse != null) {
                                    sink.next(chatResponse);
                                }
                            });
        } else {
            return Flux.defer(
                            () -> {
                                try {
                                    ResponsesResponse response =
                                            client.call(apiKey, baseUrl, request, effectiveOptions);
                                    ChatResponse chatResponse =
                                            formatter.parseCompletion(response, start);
                                    return Flux.just(chatResponse);
                                } catch (Exception e) {
                                    return Flux.error(
                                            new ModelException(
                                                    "Failed to call Responses API: "
                                                            + e.getMessage(),
                                                    e,
                                                    modelName,
                                                    "openai-responses"));
                                }
                            })
                    .subscribeOn(Schedulers.boundedElastic());
        }
    }

    @Override
    public String getModelName() {
        return configuredOptions != null ? configuredOptions.getModelName() : null;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link OpenAIResponsesModel}. */
    public static class Builder {
        private String apiKey;
        private String modelName;
        private boolean stream = true;
        private GenerateOptions defaultOptions;
        private String baseUrl;
        private String endpointPath;
        private HttpTransport httpTransport;
        private ProxyConfig proxyConfig;
        private int contextWindowSize = -1;

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder generateOptions(GenerateOptions options) {
            this.defaultOptions = options;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder endpointPath(String endpointPath) {
            this.endpointPath = endpointPath;
            return this;
        }

        public Builder httpTransport(HttpTransport httpTransport) {
            this.httpTransport = httpTransport;
            return this;
        }

        public Builder proxy(ProxyConfig proxyConfig) {
            this.proxyConfig = proxyConfig;
            return this;
        }

        public Builder contextWindowSize(int contextWindowSize) {
            this.contextWindowSize = contextWindowSize;
            return this;
        }

        public OpenAIResponsesModel build() {
            Objects.requireNonNull(modelName, "modelName must be set");

            GenerateOptions.Builder optionsBuilder =
                    GenerateOptions.builder()
                            .apiKey(apiKey)
                            .baseUrl(baseUrl)
                            .modelName(modelName)
                            .stream(stream);

            if (endpointPath != null) {
                optionsBuilder.endpointPath(endpointPath);
            } else {
                // Default to the Responses endpoint
                optionsBuilder.endpointPath(ResponsesClient.RESPONSES_ENDPOINT);
            }

            GenerateOptions builderOptions = optionsBuilder.build();
            GenerateOptions mergedOptions =
                    GenerateOptions.mergeOptions(builderOptions, defaultOptions);
            GenerateOptions effectiveOptions =
                    ModelUtils.ensureDefaultExecutionConfig(mergedOptions);

            HttpTransport transport = resolveTransport();
            ResponsesClient client = new ResponsesClient(transport);
            OpenAIResponsesFormatter fmt = new OpenAIResponsesFormatter();

            OpenAIResponsesModel model = new OpenAIResponsesModel(client, fmt, effectiveOptions);
            model.setContextWindowSize(
                    contextWindowSize >= 0
                            ? contextWindowSize
                            : ModelContextWindows.lookup(modelName, ModelContextWindows.OPENAI));
            return model;
        }

        private HttpTransport resolveTransport() {
            if (httpTransport != null) {
                if (proxyConfig != null) {
                    log.warn(
                            "OpenAIResponsesModel: both proxy() and httpTransport() are set. "
                                    + "httpTransport() takes precedence, proxy() is ignored.");
                }
                return httpTransport;
            }
            if (proxyConfig != null) {
                return OkHttpTransport.builder()
                        .config(HttpTransportConfig.builder().proxy(proxyConfig).build())
                        .build();
            }
            return HttpTransportFactory.getDefault();
        }
    }
}
