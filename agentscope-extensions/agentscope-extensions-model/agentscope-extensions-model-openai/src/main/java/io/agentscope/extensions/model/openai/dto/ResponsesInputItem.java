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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Responses API 请求 {@code input} 数组元素。
 *
 * <p>支持三种 item 类型：message（消息）、function_call（历史工具调用）、
 * function_call_output（工具结果回传）。
 *
 * <p>字段名使用 camelCase + {@code @JsonProperty} 指定 snake_case JSON 名，
 * 与 {@link OpenAIRequest} 保持一致的序列化模式。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponsesInputItem {

    /** Item type: "message", "function_call", or "function_call_output". */
    private String type;

    /** Message role: "user", "assistant", "developer", or "system" (for type="message"). */
    private String role;

    /** Message content (String or structured content-part list) for type="message". */
    private Object content;

    /** The call ID for function_call and function_call_output items. */
    @JsonProperty("call_id")
    private String callId;

    /** The function name for function_call items. */
    private String name;

    /** The JSON arguments string for function_call items. */
    private String arguments;

    /** The output string for function_call_output items. */
    private String output;

    public ResponsesInputItem() {}

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Object getContent() {
        return content;
    }

    public void setContent(Object content) {
        this.content = content;
    }

    public String getCallId() {
        return callId;
    }

    public void setCallId(String callId) {
        this.callId = callId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    /**
     * Get content as a plain string if it is one.
     *
     * @return the content string, or null if content is not a string
     */
    @JsonIgnore
    public String getContentAsString() {
        return content instanceof String ? (String) content : null;
    }
}
