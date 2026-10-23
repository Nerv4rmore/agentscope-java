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
package io.agentscope.core.tool;

import java.util.Map;

/**
 * Signals that a tool cannot complete right now and should be <b>re-executed</b> when the agent is
 * resumed, rather than resolved with an externally supplied result.
 *
 * <p>Throw this from a tool when an external precondition is temporarily unmet — out of credits,
 * rate limited, quota exhausted, a dependency still provisioning. The framework will:
 * <ol>
 *   <li>Leave the offending {@code ToolUseBlock} <b>pending</b> (no {@code ToolResultBlock} is
 *       committed to the agent state), so it is still awaiting execution;</li>
 *   <li>Commit the results of any sibling tool calls in the same batch that already succeeded, so
 *       they are <b>not</b> re-run on resume;</li>
 *   <li>Emit a {@link io.agentscope.core.event.ToolRetryLaterEvent} carrying {@link #getReason()}
 *       and {@link #getMetadata()} so the caller can surface a UI prompt;</li>
 *   <li>Stop the agent cleanly with
 *       {@link io.agentscope.core.message.GenerateReason#TOOL_RETRY_PENDING}.</li>
 * </ol>
 *
 * <p>The caller resumes by issuing a second {@code agent.call(...)} with no input: the framework
 * detects the pending tool call and re-executes it from scratch. This is the key difference from
 * {@link ToolSuspendException}, whose tool call is resolved by feeding back an externally produced
 * result instead of being retried.
 *
 * <p>Because the tool is re-executed in full, it must throw <b>before</b> performing any
 * non-idempotent side effect (e.g. before charging the user or calling the vendor API).
 *
 * <p>Example:
 * <pre>{@code
 * long cost = pricing.quote(args);
 * if (billing.getBalance(userId) < cost) {
 *     throw new ToolRetryLaterException(
 *             "credits.insufficient", Map.of("balance", balance, "required", cost));
 * }
 * billing.charge(userId, cost); // side effect happens only when affordable
 * }</pre>
 *
 * @see ToolSuspendException
 */
public class ToolRetryLaterException extends RuntimeException {

    private final String reason;
    private final Map<String, Object> metadata;

    /** Creates a retry request with the default reason and no metadata. */
    public ToolRetryLaterException() {
        this(null, null);
    }

    /**
     * Creates a retry request with a reason.
     *
     * @param reason machine-readable reason (e.g. {@code "credits.insufficient"}); may be null
     */
    public ToolRetryLaterException(String reason) {
        this(reason, null);
    }

    /**
     * Creates a retry request with a reason and a payload for the caller's UI.
     *
     * @param reason   machine-readable reason (e.g. {@code "credits.insufficient"}); may be null
     * @param metadata arbitrary payload surfaced on the emitted event (e.g. balance/required); may
     *                 be null
     */
    public ToolRetryLaterException(String reason, Map<String, Object> metadata) {
        super(reason != null ? reason : "Tool execution deferred for retry");
        this.reason = reason;
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /** The machine-readable reason for the retry, or null if unspecified. */
    public String getReason() {
        return reason;
    }

    /** Immutable payload to surface alongside the retry event; never null. */
    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
