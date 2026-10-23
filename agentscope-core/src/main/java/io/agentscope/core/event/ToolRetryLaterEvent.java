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
package io.agentscope.core.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Emitted during the acting phase when a tool throws
 * {@link io.agentscope.core.tool.ToolRetryLaterException}.
 *
 * <p>The offending tool call is left pending and the agent stops with
 * {@link io.agentscope.core.message.GenerateReason#TOOL_RETRY_PENDING}. Callers observe this event
 * to surface a prompt (e.g. "out of credits — recharge to continue"); once the precondition is met
 * they resume with an empty {@code agent.call(...)}, which re-executes the pending tool.
 *
 * @see io.agentscope.core.tool.ToolRetryLaterException
 */
public class ToolRetryLaterEvent extends AgentEvent {

    private final String toolCallId;
    private final String toolName;
    private final String reason;
    private final Map<String, Object> retryMetadata;

    @JsonCreator
    public ToolRetryLaterEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("toolCallId") String toolCallId,
            @JsonProperty("toolName") String toolName,
            @JsonProperty("reason") String reason,
            @JsonProperty("retryMetadata") Map<String, Object> retryMetadata) {
        super(id, createdAt);
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.reason = reason;
        this.retryMetadata = retryMetadata != null ? Map.copyOf(retryMetadata) : Map.of();
    }

    public ToolRetryLaterEvent(
            String toolCallId, String toolName, String reason, Map<String, Object> retryMetadata) {
        super();
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.reason = reason;
        this.retryMetadata = retryMetadata != null ? Map.copyOf(retryMetadata) : Map.of();
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.TOOL_RETRY_LATER;
    }

    /** The id of the tool call that must be retried. */
    public String getToolCallId() {
        return toolCallId;
    }

    /** The name of the tool that requested the retry. */
    public String getToolName() {
        return toolName;
    }

    /** Machine-readable reason supplied by the tool (e.g. {@code "credits.insufficient"}). */
    public String getReason() {
        return reason;
    }

    /** Arbitrary payload supplied by the tool for the caller's UI; never null. */
    public Map<String, Object> getRetryMetadata() {
        return retryMetadata;
    }
}
