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
 * Responses API 的 prompt caching 配置。
 *
 * <p>{@code gpt-5.6} 及之后模型支持。默认 OpenAI 自动选择一个隐式缓存断点（implicit 模式），
 * 也可通过 {@code prompt_cache_breakpoint} 在内容块中设置显式断点。缓存命中时输入 token
 * 按半价计费，TTL 默认 30 分钟。
 *
 * <p>Example JSON:
 * <pre>{@code
 * {
 *   "mode": "implicit",
 *   "ttl": "30m"
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponsesPromptCacheOptions {

    /**
     * 缓存断点模式：
     * "implicit"（默认，OpenAI 自动创建一个隐式断点）或
     * "explicit"（不创建隐式断点，仅使用请求中显式声明的断点）。
     */
    @JsonProperty("mode")
    private String mode;

    /** 缓存最小生存时间，当前仅支持 "30m"。 */
    @JsonProperty("ttl")
    private String ttl;

    public ResponsesPromptCacheOptions() {}

    public ResponsesPromptCacheOptions(String mode) {
        this.mode = mode;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getTtl() {
        return ttl;
    }

    public void setTtl(String ttl) {
        this.ttl = ttl;
    }
}
