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
 * Responses API 流式 SSE 事件统一容器。
 *
 * <p>Responses API 有 53 种事件类型，每种由 JSON 中的 {@code type} 字段标识。
 * 此容器捕获所有事件类型的字段并集，解析器按 {@link #getType()} 分发。
 *
 * <p>传输层会剥离 SSE 的 {@code event:} 行，只交付 {@code data:} JSON 载荷，
 * 但事件类型在 JSON 的 {@code type} 字段中冗余出现，不会丢失信息。
 *
 * <p>字段名使用 camelCase + {@code @JsonProperty} 指定 snake_case JSON 名，
 * 与 {@link OpenAIRequest} 保持一致的序列化模式。
 * {@code @JsonIgnoreProperties} 至关重要：每种事件类型字段不同，只建模最小子集。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponsesStreamEvent {

    /** The event type, e.g. "response.output_text.delta". */
    private String type;

    /** Text delta (for output_text.delta) or argument delta (for function_call_arguments.delta). */
    private String delta;

    /** The output item added/done (for output_item.added and output_item.done events). */
    private ResponsesOutputItem item;

    /** Complete function arguments (for function_call_arguments.done events). */
    private String arguments;

    /** Function name (for function_call_arguments.done events). */
    private String name;

    /** The full response object (for response.created/in_progress/completed/failed events). */
    private ResponsesResponse response;

    /** Output item index in the response.output array. */
    @JsonProperty("output_index")
    private Integer outputIndex;

    /** The item ID this event pertains to (for content/function events). */
    @JsonProperty("item_id")
    private String itemId;

    /** Error code (for bare "error" events). */
    private String code;

    /** Error message (for bare "error" events). */
    private String message;

    public ResponsesStreamEvent() {}

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDelta() {
        return delta;
    }

    public void setDelta(String delta) {
        this.delta = delta;
    }

    public ResponsesOutputItem getItem() {
        return item;
    }

    public void setItem(ResponsesOutputItem item) {
        this.item = item;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ResponsesResponse getResponse() {
        return response;
    }

    public void setResponse(ResponsesResponse response) {
        this.response = response;
    }

    public Integer getOutputIndex() {
        return outputIndex;
    }

    public void setOutputIndex(Integer outputIndex) {
        this.outputIndex = outputIndex;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
