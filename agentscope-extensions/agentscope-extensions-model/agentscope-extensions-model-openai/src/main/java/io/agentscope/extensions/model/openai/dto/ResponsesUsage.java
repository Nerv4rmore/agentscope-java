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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token usage statistics for the OpenAI Responses API.
 *
 * <p>The Responses API uses {@code input_tokens}/{@code output_tokens} field names
 * (unlike Chat Completions' {@code prompt_tokens}/{@code completion_tokens}).
 *
 * <p>Example JSON:
 * <pre>{@code
 * {
 *   "input_tokens": 37,
 *   "output_tokens": 11,
 *   "total_tokens": 48,
 *   "output_tokens_details": {
 *     "reasoning_tokens": 0
 *   }
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponsesUsage {

    /** Number of input (prompt) tokens. */
    @JsonProperty("input_tokens")
    private Integer inputTokens;

    /** Number of output (completion) tokens. */
    @JsonProperty("output_tokens")
    private Integer outputTokens;

    /** Total tokens (input + output). */
    @JsonProperty("total_tokens")
    private Integer totalTokens;

    /** Detailed breakdown of output tokens (optional). */
    @JsonProperty("output_tokens_details")
    private OutputTokensDetails outputTokensDetails;

    public ResponsesUsage() {}

    public Integer getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Integer inputTokens) {
        this.inputTokens = inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Integer outputTokens) {
        this.outputTokens = outputTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public OutputTokensDetails getOutputTokensDetails() {
        return outputTokensDetails;
    }

    public void setOutputTokensDetails(OutputTokensDetails outputTokensDetails) {
        this.outputTokensDetails = outputTokensDetails;
    }

    /**
     * Detailed breakdown of output tokens.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OutputTokensDetails {

        /** Number of tokens spent on reasoning (for reasoning models). */
        @JsonProperty("reasoning_tokens")
        private Integer reasoningTokens;

        public Integer getReasoningTokens() {
            return reasoningTokens;
        }

        public void setReasoningTokens(Integer reasoningTokens) {
            this.reasoningTokens = reasoningTokens;
        }
    }
}
