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

import io.agentscope.core.message.ToolResultBlock;

/**
 * Listener for tool execution completion, enabling cross-cutting concerns such as billing,
 * auditing, and monitoring to react to every tool call with full access to its inputs and result.
 *
 * <p>The framework invokes {@link #onToolExecuted(ToolCallContext, ToolResultBlock)} from
 * {@code ToolExecutor.executeCore} via a {@code doOnNext} hook once the tool's
 * {@code callAsync} Mono emits a result — regardless of whether the tool succeeded, errored,
 * or was suspended. The listener receives the merged input map (preset + LLM input) and the
 * full {@link ToolResultBlock} (including {@code output} content blocks and {@code state}).
 *
 * <p><b>Contract for implementations:</b>
 * <ul>
 *   <li>Invoked on the tool execution thread. Any blocking or long-running work should be
 *       dispatched asynchronously so it doesn't stall the agent reply.</li>
 *   <li>Exceptions thrown by a listener are caught and logged by the framework; they never
 *       propagate to disrupt the tool/agent pipeline.</li>
 *   <li>External (suspended) tools that short-circuit before real execution are still notified
 *       with the suspended result block, so implementations should handle
 *       {@link ToolResultBlock#METADATA_SUSPENDED} if they need to skip such calls.</li>
 * </ul>
 *
 * <p>Register listeners via {@link Toolkit#addExecutionListener(ToolExecutionListener)}.
 */
public interface ToolExecutionListener {

    /**
     * Called after a tool finishes executing.
     *
     * @param context snapshot of the tool call (tool name, merged input, runtime context with
     *                userId / sessionId)
     * @param result  the execution result (output content blocks + state: SUCCESS / ERROR / DENIED)
     */
    void onToolExecuted(ToolCallContext context, ToolResultBlock result);
}
