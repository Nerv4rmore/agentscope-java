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

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.dto.ResponsesOutputItem;
import io.agentscope.extensions.model.openai.dto.ResponsesResponse;
import io.agentscope.extensions.model.openai.dto.ResponsesStreamEvent;
import io.agentscope.extensions.model.openai.dto.ResponsesUsage;
import io.agentscope.extensions.model.openai.exception.OpenAIException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses OpenAI Responses API responses and streaming events into AgentScope
 * {@link ChatResponse} objects.
 *
 * <p>Mirrors {@link OpenAIResponseParser} but handles the Responses API schema:
 * <ul>
 *   <li>Non-streaming: reads from {@link ResponsesResponse#getOutput()} array</li>
 *   <li>Streaming: dispatches on {@link ResponsesStreamEvent#getType()} to handle
 *       text deltas, function call argument deltas, completion usage, and errors</li>
 * </ul>
 *
 * <p><b>Streaming function call fragments</b>: incremental argument chunks use the
 * {@code __fragment__} placeholder name (same convention as {@link OpenAIResponseParser}),
 * so the agent layer's accumulation logic works unchanged.
 */
public class ResponsesResponseParser {

    private static final Logger log = LoggerFactory.getLogger(ResponsesResponseParser.class);

    /** Placeholder name for streaming tool-call argument fragments. */
    protected static final String FRAGMENT_PLACEHOLDER = "__fragment__";

    /**
     * Parse a non-streaming (complete) response.
     *
     * @param response  the full Responses API response
     * @param startTime the request start time (for elapsed duration)
     * @return a ChatResponse with text and/or tool-use content blocks
     */
    public ChatResponse parseCompletion(ResponsesResponse response, Instant startTime) {
        if (response == null) {
            return null;
        }
        if (response.isError()) {
            throw buildErrorException(response);
        }

        List<ContentBlock> blocks = new ArrayList<>();
        String finishReason = null;

        List<ResponsesOutputItem> outputItems = response.getOutput();
        if (outputItems != null) {
            for (ResponsesOutputItem item : outputItems) {
                if (item == null || item.getType() == null) {
                    continue;
                }
                switch (item.getType()) {
                    case "message" -> {
                        String text = extractMessageText(item);
                        if (text != null && !text.isEmpty()) {
                            blocks.add(TextBlock.builder().text(text).build());
                        }
                    }
                    case "function_call" -> {
                        ToolUseBlock toolUse = parseFunctionCallItem(item);
                        if (toolUse != null) {
                            blocks.add(toolUse);
                        }
                    }
                    default ->
                            log.debug("Skipping unsupported output item type: {}", item.getType());
                }
            }
        }

        ChatUsage usage = buildUsage(response.getUsage(), startTime);

        if (!blocks.isEmpty() && blocks.get(blocks.size() - 1) instanceof ToolUseBlock) {
            finishReason = "tool_calls";
        } else {
            finishReason =
                    "completed".equalsIgnoreCase(response.getStatus())
                            ? "stop"
                            : response.getStatus();
        }

        return ChatResponse.builder()
                .id(response.getId())
                .content(blocks)
                .usage(usage)
                .finishReason(finishReason)
                .build();
    }

    /**
     * Parse a single streaming event into a ChatResponse, or null if the event should be skipped.
     *
     * @param event     the streaming event
     * @param startTime the request start time (for elapsed duration)
     * @return a ChatResponse, or null to skip this event
     * @throws OpenAIException if the event indicates an error
     */
    public ChatResponse parseStreamEvent(ResponsesStreamEvent event, Instant startTime) {
        if (event == null || event.getType() == null) {
            return null;
        }
        String type = event.getType();
        log.debug("Parsing Responses stream event: {}", type);

        switch (type) {
            case "response.output_text.delta" -> {
                return parseTextDelta(event);
            }
            case "response.output_item.added" -> {
                return parseOutputItemAdded(event);
            }
            case "response.function_call_arguments.delta" -> {
                return parseFunctionCallArgumentDelta(event);
            }
            case "response.function_call_arguments.done" -> {
                // Arguments are accumulated from deltas; the done event is not needed separately.
                return null;
            }
            case "response.completed" -> {
                return parseCompleted(event, startTime);
            }
            case "response.failed", "error" -> {
                throw buildStreamErrorException(event);
            }
            default -> {
                // Skip lifecycle/structural events (created, in_progress, content_part,
                // output_item.done, etc.)
                return null;
            }
        }
    }

    private ChatResponse parseTextDelta(ResponsesStreamEvent event) {
        String delta = event.getDelta();
        if (delta == null || delta.isEmpty()) {
            return null;
        }
        TextBlock textBlock = TextBlock.builder().text(delta).build();
        return ChatResponse.builder().content(Collections.singletonList(textBlock)).build();
    }

    private ChatResponse parseOutputItemAdded(ResponsesStreamEvent event) {
        ResponsesOutputItem item = event.getItem();
        if (item == null || !"function_call".equals(item.getType())) {
            return null;
        }
        // Emit the tool-call start: id and name are known, content is empty.
        ToolUseBlock toolUse =
                new ToolUseBlock(
                        item.getCallId() != null ? item.getCallId() : item.getId(),
                        item.getName(),
                        Collections.emptyMap(),
                        "",
                        null);
        return ChatResponse.builder().content(Collections.singletonList(toolUse)).build();
    }

    private ChatResponse parseFunctionCallArgumentDelta(ResponsesStreamEvent event) {
        String delta = event.getDelta();
        if (delta == null || delta.isEmpty()) {
            return null;
        }
        // Emit a fragment ToolUseBlock. The agent layer accumulates these by
        // name=FRAGMENT_PLACEHOLDER.
        ToolUseBlock fragment =
                new ToolUseBlock("", FRAGMENT_PLACEHOLDER, Collections.emptyMap(), delta, null);
        return ChatResponse.builder().content(Collections.singletonList(fragment)).build();
    }

    private ChatResponse parseCompleted(ResponsesStreamEvent event, Instant startTime) {
        ResponsesResponse response = event.getResponse();
        if (response == null) {
            return null;
        }
        ChatUsage usage = buildUsage(response.getUsage(), startTime);
        // content 必须设为非 null 的空列表，否则 ReasoningContext 等下游消费者
        // 迭代 getContent() 时会抛 NPE（与 OpenAIResponseParser 保持一致：始终设 content）。
        return ChatResponse.builder()
                .id(response.getId())
                .content(Collections.emptyList())
                .usage(usage)
                .finishReason("stop")
                .build();
    }

    /**
     * Extract concatenated text from a message output item's content array.
     * Each content part has a {@code type} (e.g. "output_text") and a {@code text} field.
     */
    @SuppressWarnings("unchecked")
    private String extractMessageText(ResponsesOutputItem item) {
        List<Object> content = item.getContent();
        if (content == null || content.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Object part : content) {
            if (part instanceof Map) {
                Map<String, Object> partMap = (Map<String, Object>) part;
                Object text = partMap.get("text");
                if (text != null) {
                    sb.append(text);
                }
            }
        }
        return sb.toString();
    }

    private ToolUseBlock parseFunctionCallItem(ResponsesOutputItem item) {
        String callId = item.getCallId() != null ? item.getCallId() : item.getId();
        String name = item.getName();
        if (name == null) {
            log.warn("Function call item missing name, skipping");
            return null;
        }
        Map<String, Object> input = parseArgumentsToMap(item.getArguments());
        return new ToolUseBlock(callId, name, input);
    }

    /**
     * Parse a JSON arguments string into a Map. Returns an empty map on failure.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgumentsToMap(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return JsonUtils.getJsonCodec().fromJson(arguments, Map.class);
        } catch (JsonException e) {
            log.warn("Failed to parse function call arguments as JSON: {}", arguments, e);
            return Collections.emptyMap();
        }
    }

    private ChatUsage buildUsage(ResponsesUsage usage, Instant startTime) {
        if (usage == null) {
            return null;
        }
        double elapsed =
                startTime != null
                        ? Duration.between(startTime, Instant.now()).toMillis() / 1000.0
                        : 0.0;
        int inputTokens = usage.getInputTokens() != null ? usage.getInputTokens() : 0;
        int outputTokens = usage.getOutputTokens() != null ? usage.getOutputTokens() : 0;
        return ChatUsage.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .time(elapsed)
                .build();
    }

    private OpenAIException buildErrorException(ResponsesResponse response) {
        if (response.getError() != null) {
            return OpenAIException.create(
                    400,
                    "Responses API error: " + response.getError().getMessage(),
                    response.getError().getCode(),
                    null);
        }
        return OpenAIException.create(
                400, "Responses API returned failed status: " + response.getStatus(), null, null);
    }

    private OpenAIException buildStreamErrorException(ResponsesStreamEvent event) {
        // For response.failed, the error is nested inside response.error
        if (event.getResponse() != null && event.getResponse().getError() != null) {
            return OpenAIException.create(
                    400,
                    "Responses API stream error: " + event.getResponse().getError().getMessage(),
                    event.getResponse().getError().getCode(),
                    null);
        }
        // For bare "error" events, code/message are on the event itself
        String message = event.getMessage() != null ? event.getMessage() : "Unknown stream error";
        return OpenAIException.create(
                400, "Responses API stream error: " + message, event.getCode(), null);
    }
}
