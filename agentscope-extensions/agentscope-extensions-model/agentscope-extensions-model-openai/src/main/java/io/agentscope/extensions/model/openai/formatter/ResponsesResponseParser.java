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
import io.agentscope.core.message.ThinkingBlock;
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
 *   <li>Non-streaming: reads from {@link ResponsesResponse#getOutput()} array, including
 *       {@code message} (text), {@code function_call} (tool use), and {@code reasoning}
 *       (reasoning summary → {@link ThinkingBlock}) items</li>
 *   <li>Streaming: dispatches on {@link ResponsesStreamEvent#getType()} to handle
 *       text deltas, reasoning summary deltas, function call argument deltas, completion
 *       usage, and errors</li>
 * </ul>
 *
 * <p><b>Streaming function call fragments</b>: incremental argument chunks use the
 * {@code __fragment__} placeholder name (same convention as {@link OpenAIResponseParser}),
 * so the agent layer's accumulation logic works unchanged.
 *
 * <p><b>Reasoning summary streaming</b>: {@code response.reasoning_summary_text.delta}
 * events build {@link ThinkingBlock}s carrying the delta text, mirroring how
 * {@link OpenAIResponseParser} handles {@code reasoning_content}. The
 * {@code reasoning_summary_part.added/done} and {@code reasoning_summary_text.done}
 * boundary events are skipped — {@code ReActAgent}'s block-type-transition logic infers
 * {@code thinking.start/delta/end} boundaries automatically.
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
                    case "reasoning" -> {
                        // 提取 reasoning item 的 summary 摘要文本，构建 ThinkingBlock。
                        // summary 是一个 part 数组，每个 part 形如
                        // {"type":"summary_text","text":"..."}，拼接所有 text 字段。
                        String summaryText = extractReasoningSummaryText(item);
                        if (summaryText != null && !summaryText.isEmpty()) {
                            blocks.add(ThinkingBlock.builder().thinking(summaryText).build());
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
            case "response.reasoning_summary_text.delta" -> {
                // 推理摘要增量文本：构建 ThinkingBlock，与 Chat Completions 的
                // reasoning_content 流式处理方式一致。ReActAgent 的 ModelCallBlockLifecycle
                // 通过 thinkingStarted 标志 + block 类型转换自动推断 thinking.start/delta/end
                // 边界，无需在此显式发射 start/end 事件。
                return parseReasoningSummaryDelta(event);
            }
            case "response.reasoning_summary_part.added",
                    "response.reasoning_summary_part.done",
                    "response.reasoning_summary_text.done" -> {
                // 边界/终结事件由 ReActAgent 的 block 类型转换逻辑自动处理，无需单独发射。
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
                // output_item.added for non-function items, output_item.done, etc.)
                return null;
            }
        }
    }

    private ChatResponse parseTextDelta(ResponsesStreamEvent event) {
        String delta = event.getDelta();
        if (delta == null || delta.isEmpty()) {
            return null;
        }
        log.debug("Output text delta: len={}", delta.length());
        TextBlock textBlock = TextBlock.builder().text(delta).build();
        return ChatResponse.builder().content(Collections.singletonList(textBlock)).build();
    }

    /**
     * 解析 reasoning summary 增量文本事件，构建 ThinkingBlock。
     *
     * <p>与 {@link OpenAIResponseParser} 处理 {@code reasoning_content} 的方式一致：
     * 每个增量 chunk 构建一个带 delta 文本的 ThinkingBlock，由 ReActAgent 的
     * ThinkingAccumulator 累加拼接。
     */
    private ChatResponse parseReasoningSummaryDelta(ResponsesStreamEvent event) {
        String delta = event.getDelta();
        if (delta == null || delta.isEmpty()) {
            return null;
        }
        ThinkingBlock thinkingBlock = ThinkingBlock.builder().thinking(delta).build();
        return ChatResponse.builder().content(Collections.singletonList(thinkingBlock)).build();
    }

    private ChatResponse parseOutputItemAdded(ResponsesStreamEvent event) {
        ResponsesOutputItem item = event.getItem();
        if (item == null) {
            return null;
        }
        if (!"function_call".equals(item.getType())) {
            // 非 function_call 的 output_item.added（如 reasoning、message）不产生工具调用，
            // 记录实际 type 便于排查 OpenRouter 等转发层是否返回了非标准类型。
            log.debug("Skipping output_item.added with non-function type: {}", item.getType());
            return null;
        }
        // Emit the tool-call start: id and name are known, content is empty.
        String toolCallId = item.getCallId() != null ? item.getCallId() : item.getId();
        log.debug(
                "Function call started: id={}, name={}, callId={}",
                item.getId(),
                item.getName(),
                item.getCallId());
        ToolUseBlock toolUse =
                new ToolUseBlock(toolCallId, item.getName(), Collections.emptyMap(), "", null);
        return ChatResponse.builder().content(Collections.singletonList(toolUse)).build();
    }

    private ChatResponse parseFunctionCallArgumentDelta(ResponsesStreamEvent event) {
        String delta = event.getDelta();
        if (delta == null || delta.isEmpty()) {
            return null;
        }
        log.debug(
                "Function call argument delta: itemId={}, deltaLen={}",
                event.getItemId(),
                delta.length());
        // Emit a fragment ToolUseBlock. The agent layer accumulates these by
        // name=FRAGMENT_PLACEHOLDER.
        ToolUseBlock fragment =
                new ToolUseBlock("", FRAGMENT_PLACEHOLDER, Collections.emptyMap(), delta, null);
        return ChatResponse.builder().content(Collections.singletonList(fragment)).build();
    }

    private ChatResponse parseCompleted(ResponsesStreamEvent event, Instant startTime) {
        ResponsesResponse response = event.getResponse();
        if (response == null) {
            log.warn("response.completed event has null response body");
            return null;
        }
        log.debug(
                "Response completed: id={}, status={}, outputCount={}",
                response.getId(),
                response.getStatus(),
                response.getOutput() != null ? response.getOutput().size() : 0);
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

    /**
     * 从 reasoning output item 的 {@code summary} 数组中提取拼接的摘要文本。
     *
     * <p>每个 summary part 形如 {@code {"type":"summary_text","text":"..."}}，
     * 拼接所有 part 的 {@code text} 字段。
     */
    @SuppressWarnings("unchecked")
    private String extractReasoningSummaryText(ResponsesOutputItem item) {
        List<Object> summary = item.getSummary();
        if (summary == null || summary.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Object part : summary) {
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
