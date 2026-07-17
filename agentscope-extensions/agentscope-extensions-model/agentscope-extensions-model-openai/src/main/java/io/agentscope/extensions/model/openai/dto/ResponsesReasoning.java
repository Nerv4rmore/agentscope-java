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

/**
 * Reasoning configuration for the OpenAI Responses API.
 *
 * <p>The Responses API uses a nested {@code reasoning} object (unlike Chat Completions'
 * top-level {@code reasoning_effort} string). The {@code effort} field controls how much
 * effort reasoning models spend on internal reasoning.
 *
 * <p>Example JSON:
 * <pre>{@code
 * {
 *   "effort": "medium",
 *   "summary": null
 * }
 * }</pre>
 *
 * @see ResponsesRequest#getReasoning()
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponsesReasoning {

    /** Reasoning effort level: "none", "minimal", "low", "medium", "high", "xhigh", or "max". */
    @JsonProperty("effort")
    private String effort;

    /** Reasoning summary mode: "auto", "concise", or "detailed". */
    @JsonProperty("summary")
    private String summary;

    /**
     * 控制哪些历史 reasoning 项回传给模型：
     * "auto"（模型自决）、"current_turn"（仅当前轮次）、"all_turns"（全部历史）。
     * 设为 "current_turn" 可显著减少多轮对话的输入 token。
     */
    @JsonProperty("context")
    private String context;

    public ResponsesReasoning() {}

    /**
     * Creates a reasoning configuration with the given effort level.
     *
     * @param effort the reasoning effort level
     */
    public ResponsesReasoning(String effort) {
        this.effort = effort;
    }

    public String getEffort() {
        return effort;
    }

    public void setEffort(String effort) {
        this.effort = effort;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }
}
