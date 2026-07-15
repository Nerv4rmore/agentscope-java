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
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Response from the OpenAI Responses API ({@code /v1/responses}).
 *
 * <p>This represents the full non-streaming response, and is also embedded inside
 * streaming envelope events ({@code response.created}, {@code response.completed}, etc.)
 * via the {@link ResponsesStreamEvent#getResponse()} field.
 *
 * <p>The key difference from Chat Completions is the {@code output} array (instead of
 * {@code choices}): each element is a typed {@link ResponsesOutputItem} (message,
 * function_call, reasoning, etc.).
 *
 * <p>Example JSON:
 * <pre>{@code
 * {
 *   "id": "resp_abc",
 *   "object": "response",
 *   "status": "completed",
 *   "output": [
 *     {"type": "message", "role": "assistant", "content": [...]},
 *     {"type": "function_call", "call_id": "call_xyz", "name": "...", "arguments": "..."}
 *   ],
 *   "usage": {"input_tokens": 37, "output_tokens": 11, "total_tokens": 48}
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponsesResponse {

    /** Unique response ID (e.g., "resp_..."). */
    private String id;

    /** Object type, always "response". */
    private String object;

    /** Response status: "in_progress", "completed", "incomplete", or "failed". */
    private String status;

    /** The output items (messages, function calls, reasoning, etc.). */
    private List<ResponsesOutputItem> output;

    /** Token usage statistics (only present in the final response.completed event). */
    private ResponsesUsage usage;

    /** Error information (present when status is "failed"). */
    private OpenAIError error;

    public ResponsesResponse() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<ResponsesOutputItem> getOutput() {
        return output;
    }

    public void setOutput(List<ResponsesOutputItem> output) {
        this.output = output;
    }

    public ResponsesUsage getUsage() {
        return usage;
    }

    public void setUsage(ResponsesUsage usage) {
        this.usage = usage;
    }

    public OpenAIError getError() {
        return error;
    }

    public void setError(OpenAIError error) {
        this.error = error;
    }

    /**
     * Check whether this response represents an error.
     *
     * @return true if the response has an error or a failed status
     */
    @JsonIgnore
    public boolean isError() {
        return error != null || "failed".equalsIgnoreCase(status);
    }
}
