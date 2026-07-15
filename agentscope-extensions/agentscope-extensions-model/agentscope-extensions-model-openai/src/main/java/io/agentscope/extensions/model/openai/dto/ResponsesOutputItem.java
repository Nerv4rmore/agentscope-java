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
import java.util.List;

/**
 * Responses API 响应 {@code output} 数组元素。
 *
 * <p>每个 item 有 {@code type} 判别字段。最小实现处理两种类型：
 * message（助手消息）和 function_call（工具调用请求）。
 *
 * <p>字段名使用 camelCase + {@code @JsonProperty} 指定 snake_case JSON 名，
 * 与 {@link OpenAIRequest} 保持一致的序列化模式。
 * {@code @JsonIgnoreProperties} 容忍未处理的其他 item 类型（reasoning、web_search_call 等）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponsesOutputItem {

    /** Item type: "message", "function_call", "reasoning", etc. */
    private String type;

    /** Unique item ID (e.g., "msg_...", "fc_..."). */
    private String id;

    /** Message role ("assistant") for type="message". */
    private String role;

    /** Message content parts for type="message" (list of content part objects). */
    private List<Object> content;

    /** The call ID for type="function_call" (used to correlate the tool result). */
    @JsonProperty("call_id")
    private String callId;

    /** The function name for type="function_call". */
    private String name;

    /** The JSON arguments string for type="function_call". */
    private String arguments;

    /** Item status: "in_progress", "completed", or "incomplete". */
    private String status;

    public ResponsesOutputItem() {}

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public List<Object> getContent() {
        return content;
    }

    public void setContent(List<Object> content) {
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
