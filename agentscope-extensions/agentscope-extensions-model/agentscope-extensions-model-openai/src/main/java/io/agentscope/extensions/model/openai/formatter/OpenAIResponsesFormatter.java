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
package io.agentscope.extensions.model.openai.formatter;

import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.dto.ResponsesFunctionTool;
import io.agentscope.extensions.model.openai.dto.ResponsesInputItem;
import io.agentscope.extensions.model.openai.dto.ResponsesReasoning;
import io.agentscope.extensions.model.openai.dto.ResponsesRequest;
import io.agentscope.extensions.model.openai.dto.ResponsesResponse;
import io.agentscope.extensions.model.openai.dto.ResponsesStreamEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Formatter for the OpenAI Responses API ({@code /v1/responses}).
 *
 * <p>Extends {@link AbstractBaseFormatter} with Responses-specific DTO type parameters.
 * Delegates message conversion to {@link ResponsesMessageConverter} and response parsing
 * to {@link ResponsesResponseParser}.
 *
 * <p>Key difference from {@link OpenAIChatFormatter}: the {@code reasoning_effort} string
 * from {@link GenerateOptions} is mapped to the nested {@code reasoning: {effort: ...}}
 * object required by the Responses API.
 */
public class OpenAIResponsesFormatter
        extends AbstractBaseFormatter<ResponsesInputItem, ResponsesStreamEvent, ResponsesRequest> {

    private final ResponsesMessageConverter messageConverter;
    private final ResponsesResponseParser responseParser;

    public OpenAIResponsesFormatter() {
        this.messageConverter =
                new ResponsesMessageConverter(
                        this::extractTextContent, this::convertToolResultToString);
        this.responseParser = new ResponsesResponseParser();
    }

    @Override
    protected List<ResponsesInputItem> doFormat(List<Msg> msgs) {
        List<ResponsesInputItem> items = new ArrayList<>();
        for (Msg msg : msgs) {
            items.addAll(messageConverter.convertToItems(msg));
        }
        return items;
    }

    /**
     * Format messages into a structured input: extract the first system message as
     * {@code instructions}, and convert all messages to input items.
     *
     * <p>The Responses API convention is to pass the system prompt via {@code instructions}
     * rather than as a message in the {@code input} array. If there is no system message,
     * instructions will be null.
     *
     * @param msgs the conversation messages
     * @return a result containing the instructions string and the input items list
     */
    public FormattedInput formatWithInstructions(List<Msg> msgs) {
        String instructions = null;
        List<ResponsesInputItem> inputItems = new ArrayList<>();
        for (Msg msg : msgs) {
            if (msg.getRole() == MsgRole.SYSTEM && instructions == null) {
                instructions = extractTextContent(msg);
            }
            inputItems.addAll(messageConverter.convertToItems(msg));
        }
        return new FormattedInput(instructions, inputItems);
    }

    @Override
    public ChatResponse parseResponse(ResponsesStreamEvent event, Instant startTime) {
        return responseParser.parseStreamEvent(event, startTime);
    }

    /**
     * Parse a non-streaming (complete) response.
     *
     * <p>This is a separate method from {@link #parseResponse} because the Formatter interface
     * is typed to the streaming event DTO. The model calls this for non-streaming mode.
     *
     * @param response  the full Responses API response
     * @param startTime the request start time
     * @return a ChatResponse
     */
    public ChatResponse parseCompletion(ResponsesResponse response, Instant startTime) {
        return responseParser.parseCompletion(response, startTime);
    }

    @Override
    public void applyOptions(
            ResponsesRequest request, GenerateOptions options, GenerateOptions defaultOptions) {

        // Temperature
        Double temperature =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getTemperature);
        if (temperature != null) {
            request.setTemperature(temperature);
        }

        // Top P
        Double topP = getOptionOrDefault(options, defaultOptions, GenerateOptions::getTopP);
        if (topP != null) {
            request.setTopP(topP);
        }

        // Max output tokens (Responses API uses max_output_tokens for both
        // maxTokens/maxCompletionTokens)
        Integer maxTokens =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getMaxTokens);
        Integer maxCompletionTokens =
                getOptionOrDefault(
                        options, defaultOptions, GenerateOptions::getMaxCompletionTokens);
        Integer effectiveMax = maxTokens != null ? maxTokens : maxCompletionTokens;
        if (effectiveMax != null) {
            request.setMaxOutputTokens(effectiveMax);
        }

        // Parallel tool calls
        Boolean parallelToolCalls =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getParallelToolCalls);
        if (parallelToolCalls != null) {
            request.setParallelToolCalls(parallelToolCalls);
        }

        // Reasoning effort → nested reasoning object
        String reasoningEffort =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getReasoningEffort);
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            request.setReasoning(new ResponsesReasoning(reasoningEffort.trim()));
        }

        // Stateless: always set store=false (OpenRouter doesn't support server-side state,
        // and the agent manages conversation context itself)
        request.setStore(false);
    }

    @Override
    public void applyTools(ResponsesRequest request, List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        List<ResponsesFunctionTool> functionTools = new ArrayList<>();
        for (ToolSchema toolSchema : tools) {
            ResponsesFunctionTool tool =
                    ResponsesFunctionTool.function(
                            toolSchema.getName(),
                            toolSchema.getDescription(),
                            toolSchema.getParameters());
            if (toolSchema.getStrict() != null) {
                tool.setStrict(toolSchema.getStrict());
            }
            functionTools.add(tool);
        }
        if (!functionTools.isEmpty()) {
            request.setTools(functionTools);
        }
    }

    @Override
    public void applyToolChoice(ResponsesRequest request, ToolChoice toolChoice) {
        if (request.getTools() == null || request.getTools().isEmpty()) {
            return;
        }
        if (toolChoice == null || toolChoice instanceof ToolChoice.Auto) {
            request.setToolChoice("auto");
        } else if (toolChoice instanceof ToolChoice.None) {
            request.setToolChoice("none");
        } else if (toolChoice instanceof ToolChoice.Required) {
            request.setToolChoice("required");
        } else if (toolChoice instanceof ToolChoice.Specific specific) {
            Map<String, Object> namedToolChoice = new HashMap<>();
            namedToolChoice.put("type", "function");
            namedToolChoice.put("name", specific.toolName());
            request.setToolChoice(namedToolChoice);
        } else {
            request.setToolChoice("auto");
        }
    }

    /** Result of {@link #formatWithInstructions(List)}: instructions + input items. */
    public record FormattedInput(String instructions, List<ResponsesInputItem> inputItems) {}
}
