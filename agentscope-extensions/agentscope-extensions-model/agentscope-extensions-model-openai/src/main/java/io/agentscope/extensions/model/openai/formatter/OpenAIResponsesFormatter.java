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
import io.agentscope.extensions.model.openai.dto.ResponsesPromptCacheOptions;
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

        // Reasoning effort → nested reasoning object。
        // Responses API 必须显式带 reasoning.summary，否则只会内部推理但不下发任何
        // reasoning_summary_text.delta 事件（前端拿不到思考内容）。这里默认请求 summary="auto"，
        // 让模型自行决定摘要粒度；stream 解析侧已就绪解析该事件为 ThinkingBlock。
        String reasoningEffort =
                getOptionOrDefault(options, defaultOptions, GenerateOptions::getReasoningEffort);
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            ResponsesReasoning reasoning = new ResponsesReasoning(reasoningEffort.trim());
            reasoning.setSummary("auto");
            // 只回传当前轮次的 reasoning，避免多轮对话历史 reasoning 累积消耗大量输入 token
            reasoning.setContext("current_turn");
            request.setReasoning(reasoning);
        } else {
            // 即使未配 reasoning_effort，只要是 reasoning 模型（如默认 effort）也需要带 summary
            // 才能回传思考内容。此处兗底设置 summary="auto"，effort 由 API 默认值决定。
            ResponsesReasoning reasoning = new ResponsesReasoning();
            reasoning.setSummary("auto");
            reasoning.setContext("current_turn");
            request.setReasoning(reasoning);
        }
        
        // 截断策略：上下文超窗口时自动从对话开头丢弃旧消息，避免 400 报错
        request.setTruncation("auto");
        
        // Prompt caching：gpt-5.6 及之后模型支持，implicit 模式让 OpenAI 自动创建缓存断点，
        // 缓存命中时输入 token 半价计费。cache_key 用模型名分桶，同模型同系统提示词的请求共享缓存。
        String modelName = getOptionOrDefault(options, defaultOptions, GenerateOptions::getModelName);
        if (modelName != null && !modelName.isBlank()) {
            request.setPromptCacheKey(modelName);
        }
        ResponsesPromptCacheOptions cacheOptions = new ResponsesPromptCacheOptions("implicit");
        request.setPromptCacheOptions(cacheOptions);
        
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
