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
 * See the License for the specific language and permissions
 * limitations under the License.
 */
package io.agentscope.core.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Snapshot of a tool invocation, passed to {@link ToolExecutionListener} after the tool finishes.
 *
 * <p>Captures the tool name, call id, merged input (preset parameters + LLM-supplied input), the
 * original {@link ToolUseBlock}, and the {@link RuntimeContext} carrying {@code userId} /
 * {@code sessionId}. Business layers (e.g. billing) use this to compute charges based on the
 * actual arguments of the call.
 *
 * <p>The input map is defensively copied and exposed as unmodifiable.
 */
public final class ToolCallContext {

    private final String toolName;
    private final String toolCallId;
    private final Map<String, Object> input;
    private final ToolUseBlock toolUseBlock;
    private final RuntimeContext runtimeContext;

    /**
     * Creates a tool call context snapshot.
     *
     * @param toolName       the name of the invoked tool
     * @param toolCallId     the unique id of this tool call
     * @param input          the merged input parameters (preset + LLM input); defensively copied
     * @param toolUseBlock   the original tool use block
     * @param runtimeContext the runtime context (may carry userId / sessionId); may be {@code null}
     */
    public ToolCallContext(
            String toolName,
            String toolCallId,
            Map<String, Object> input,
            ToolUseBlock toolUseBlock,
            RuntimeContext runtimeContext) {
        this.toolName = Objects.requireNonNull(toolName, "toolName must not be null");
        this.toolCallId = toolCallId;
        this.input =
                input != null
                        ? Collections.unmodifiableMap(new HashMap<>(input))
                        : Collections.emptyMap();
        this.toolUseBlock = toolUseBlock;
        this.runtimeContext = runtimeContext;
    }

    /** @return the name of the invoked tool */
    public String getToolName() {
        return toolName;
    }

    /** @return the unique id of this tool call, or {@code null} if not set */
    public String getToolCallId() {
        return toolCallId;
    }

    /**
     * @return unmodifiable view of the merged input parameters (preset + LLM input); never
     *     {@code null}, empty if no input
     */
    public Map<String, Object> getInput() {
        return input;
    }

    /** @return the original tool use block, or {@code null} if not provided */
    public ToolUseBlock getToolUseBlock() {
        return toolUseBlock;
    }

    /** @return the runtime context carrying userId / sessionId; may be {@code null} */
    public RuntimeContext getRuntimeContext() {
        return runtimeContext;
    }

    /**
     * Convenience accessor for the session id from the runtime context.
     *
     * @return the session id, or {@code null} if the runtime context is absent
     */
    public String getSessionId() {
        return runtimeContext != null ? runtimeContext.getSessionId() : null;
    }

    /**
     * Convenience accessor for the user id from the runtime context.
     *
     * @return the user id, or {@code null} if the runtime context is absent
     */
    public String getUserId() {
        return runtimeContext != null ? runtimeContext.getUserId() : null;
    }

    @Override
    public String toString() {
        return "ToolCallContext{toolName='"
                + toolName
                + "', toolCallId='"
                + toolCallId
                + "', inputKeys="
                + input.keySet()
                + '}';
    }
}
