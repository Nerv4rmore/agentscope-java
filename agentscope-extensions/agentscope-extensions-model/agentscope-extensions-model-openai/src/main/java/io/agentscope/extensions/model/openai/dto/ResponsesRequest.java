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
package io.agentscope.extensions.model.openai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Responses API 请求体。
 *
 * <p>与 Chat Completions 的根本差异：对话通过 {@code input}（String 或
 * {@link ResponsesInputItem} 数组）传入，系统提示词放入 {@code instructions}。
 *
 * <p>字段名使用 camelCase + {@code @JsonProperty} 指定 snake_case JSON 名，
 * 与 {@link OpenAIRequest} 保持一致的序列化模式。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponsesRequest {

    /** The model to use (e.g., "gpt-5.6-luna"). */
    private String model;

    /** System/developer instructions inserted into the model's context. */
    private String instructions;

    /** Text, image, or structured input items (String or List&lt;ResponsesInputItem&gt;). */
    private Object input;

    /** Available function tools. */
    private List<ResponsesFunctionTool> tools;

    /** Tool choice: "auto", "none", "required", or a specific tool object. */
    @JsonProperty("tool_choice")
    private Object toolChoice;

    /** Reasoning configuration (effort/summary). */
    private ResponsesReasoning reasoning;

    /** Sampling temperature (0.0-2.0). */
    private Double temperature;

    /** Nucleus sampling parameter (0.0-1.0). */
    @JsonProperty("top_p")
    private Double topP;

    /** Maximum output tokens to generate. */
    @JsonProperty("max_output_tokens")
    private Integer maxOutputTokens;

    /** Whether to stream the response. */
    private Boolean stream;

    /** Whether to store the response server-side (set to false for stateless usage). */
    private Boolean store;

    /** Whether to allow parallel tool calls. */
    @JsonProperty("parallel_tool_calls")
    private Boolean parallelToolCalls;

    /**
     * 截断策略："auto" 时上下文超窗口会自动从对话开头丢弃旧消息；"disabled"（默认）时超窗口返回 400。
     */
    private String truncation;

    /**
     * Prompt 缓存键：用于帮助 OpenAI 对相似请求进行缓存匹配，提高缓存命中率。
     * 通常传入 userId 或用户标识。
     */
    @JsonProperty("prompt_cache_key")
    private String promptCacheKey;

    /**
     * Prompt 缓存选项（mode / ttl）。gpt-5.6 及之后模型支持。
     * 设 mode="implicit" 让 OpenAI 自动创建隐式缓存断点，缓存命中时输入 token 半价。
     */
    @JsonProperty("prompt_cache_options")
    private ResponsesPromptCacheOptions promptCacheOptions;

    public ResponsesRequest() {}

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public Object getInput() {
        return input;
    }

    public void setInput(Object input) {
        this.input = input;
    }

    public List<ResponsesFunctionTool> getTools() {
        return tools;
    }

    public void setTools(List<ResponsesFunctionTool> tools) {
        this.tools = tools;
    }

    public Object getToolChoice() {
        return toolChoice;
    }

    public void setToolChoice(Object toolChoice) {
        this.toolChoice = toolChoice;
    }

    public ResponsesReasoning getReasoning() {
        return reasoning;
    }

    public void setReasoning(ResponsesReasoning reasoning) {
        this.reasoning = reasoning;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(Integer maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    public Boolean getStore() {
        return store;
    }

    public void setStore(Boolean store) {
        this.store = store;
    }

    public Boolean getParallelToolCalls() {
        return parallelToolCalls;
    }

    public void setParallelToolCalls(Boolean parallelToolCalls) {
        this.parallelToolCalls = parallelToolCalls;
    }

    public String getTruncation() {
        return truncation;
    }

    public void setTruncation(String truncation) {
        this.truncation = truncation;
    }

    public String getPromptCacheKey() {
        return promptCacheKey;
    }

    public void setPromptCacheKey(String promptCacheKey) {
        this.promptCacheKey = promptCacheKey;
    }

    public ResponsesPromptCacheOptions getPromptCacheOptions() {
        return promptCacheOptions;
    }

    public void setPromptCacheOptions(ResponsesPromptCacheOptions promptCacheOptions) {
        this.promptCacheOptions = promptCacheOptions;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link ResponsesRequest}. */
    public static class Builder {
        private final ResponsesRequest request = new ResponsesRequest();

        public Builder model(String model) {
            request.setModel(model);
            return this;
        }

        public Builder instructions(String instructions) {
            request.setInstructions(instructions);
            return this;
        }

        public Builder input(Object input) {
            request.setInput(input);
            return this;
        }

        public Builder tools(List<ResponsesFunctionTool> tools) {
            request.setTools(tools);
            return this;
        }

        public Builder toolChoice(Object toolChoice) {
            request.setToolChoice(toolChoice);
            return this;
        }

        public Builder reasoning(ResponsesReasoning reasoning) {
            request.setReasoning(reasoning);
            return this;
        }

        public Builder temperature(Double temperature) {
            request.setTemperature(temperature);
            return this;
        }

        public Builder topP(Double topP) {
            request.setTopP(topP);
            return this;
        }

        public Builder maxOutputTokens(Integer maxOutputTokens) {
            request.setMaxOutputTokens(maxOutputTokens);
            return this;
        }

        public Builder stream(Boolean stream) {
            request.setStream(stream);
            return this;
        }

        public Builder store(Boolean store) {
            request.setStore(store);
            return this;
        }

        public Builder parallelToolCalls(Boolean parallelToolCalls) {
            request.setParallelToolCalls(parallelToolCalls);
            return this;
        }

        public Builder truncation(String truncation) {
            request.setTruncation(truncation);
            return this;
        }

        public Builder promptCacheKey(String promptCacheKey) {
            request.setPromptCacheKey(promptCacheKey);
            return this;
        }

        public Builder promptCacheOptions(ResponsesPromptCacheOptions promptCacheOptions) {
            request.setPromptCacheOptions(promptCacheOptions);
            return this;
        }

        public ResponsesRequest build() {
            return request;
        }
    }
}
