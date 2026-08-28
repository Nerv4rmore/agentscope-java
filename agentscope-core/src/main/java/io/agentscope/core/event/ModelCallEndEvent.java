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
import io.agentscope.core.model.ChatUsage;

/**
 * Emitted when the agent finishes a model (LLM) call.
 */
public class ModelCallEndEvent extends AgentEvent {

    private final String replyId;
    private final String messageId;
    private final ChatUsage usage;

    public ModelCallEndEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("usage") ChatUsage usage) {
        this(id, createdAt, replyId, null, usage);
    }

    @JsonCreator
    public ModelCallEndEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("replyId") String replyId,
            @JsonProperty("messageId") String messageId,
            @JsonProperty("usage") ChatUsage usage) {
        super(id, createdAt);
        this.replyId = replyId;
        this.messageId = messageId;
        this.usage = usage;
    }

    public ModelCallEndEvent(String replyId, ChatUsage usage) {
        this(replyId, null, usage);
    }

    /**
     * Creates an event carrying the persisted message id.
     *
     * <p>{@code messageId} is the id assigned to the assistant {@code Msg} built from this model
     * call (the provider response id), i.e. the {@code id} field persisted in the agent state.
     */
    public ModelCallEndEvent(String replyId, String messageId, ChatUsage usage) {
        this(null, null, replyId, messageId, usage);
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.MODEL_CALL_END;
    }

    public String getReplyId() {
        return replyId;
    }

    /** Id of the assistant message persisted for this model call (may be null). */
    public String getMessageId() {
        return messageId;
    }

    public ChatUsage getUsage() {
        return usage;
    }
}
