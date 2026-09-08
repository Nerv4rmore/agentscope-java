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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Observability middleware that logs the reasoning and execution trace of an agent.
 *
 * <p>Normal-path trace summaries (PRE_CALL / PRE_REASONING / PRE_MODEL_CALL / PRE_ACTING /
 * successful POST_ACTING) are logged at DEBUG to avoid flooding the main log on every tool
 * invocation. Only abnormal outcomes (errors, empty completions, tool-call-terminated loops,
 * failed tool executions) surface at INFO/WARN/ERROR so trouble-shooting stays focused.
 * At DEBUG level, additionally logs tool call arguments, tool result
 * content, reasoning text, and input message details.
 *
 * <p>The effective model is logged from {@link #onModelCall} via {@code
 * ModelCallInput#model()} — the model AFTER outer middlewares (e.g. per-request model
 * switching / fallback middlewares) have replaced it. Logging {@code agent.getModel()}
 * instead would always show the construction-time default model and mislead readers
 * about which model actually served the request.
 */
public class AgentTraceMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceMiddleware.class);

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        // 正常路径摘要降为 debug，避免每次调用刷 INFO；仅 ERROR 分支保留 info。
        boolean debug = log.isDebugEnabled();
        String name = agent.getName();
        if (debug) {
            List<Msg> msgs = input.msgs();
            log.debug("[{}] PRE_CALL  | {} input message(s)", name, msgs != null ? msgs.size() : 0);
            if (msgs != null) {
                for (Msg msg : msgs) {
                    log.debug(
                            "[{}] PRE_CALL  |   [{}] {}",
                            name,
                            msg.getRole(),
                            truncate(msg.getTextContent(), 200));
                }
            }
        }
        return next.apply(input)
                .doOnComplete(() -> logPostCall(agent, ctx))
                .doOnError(
                        e ->
                                log.warn(
                                        "[{}] ERROR | {}: {}",
                                        name,
                                        e.getClass().getSimpleName(),
                                        e.getMessage()));
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        // 正常路径摘要降为 debug；仅异常分支（空完成）保留 info。
        String name = agent.getName();
        if (log.isDebugEnabled()) {
            int msgCount = input.messages() != null ? input.messages().size() : 0;
            log.debug("[{}] PRE_REASONING  | messages={}", name, msgCount);
            if (input.messages() != null) {
                for (Msg msg : input.messages()) {
                    log.debug(
                            "[{}] PRE_REASONING  |   [{}] len={}",
                            name,
                            msg.getRole(),
                            msg.getTextContent() != null ? msg.getTextContent().length() : 0);
                }
            }
        }
        StringBuilder textBuf = new StringBuilder();
        List<ToolCallStartEvent> toolCalls = new ArrayList<>();
        return next.apply(input)
                .doOnNext(
                        ev -> {
                            if (ev instanceof TextBlockDeltaEvent tbd) {
                                if (tbd.getDelta() != null) {
                                    textBuf.append(tbd.getDelta());
                                }
                            } else if (ev instanceof ToolCallStartEvent tcs) {
                                toolCalls.add(tcs);
                            }
                        })
                .doOnComplete(
                        () -> {
                            String text = textBuf.toString();
                            boolean hasText = !text.isBlank();
                            // Always surface the model's text, even when it accompanies tool calls
                            // (a tool-call turn often carries a "thinking out loud" preamble that
                            // would otherwise be silently dropped).
                            if (toolCalls.isEmpty()) {
                                // No tool call ends the ReAct loop. If there was also no text, the
                                // model returned an empty completion — make that explicit instead
                                // of logging a misleading "<empty>" that looks like normal output.
                                if (!hasText) {
                                    log.info(
                                            "[{}] POST_REASONING | empty completion (no text, no"
                                                    + " tool call) — ReAct loop will terminate",
                                            name);
                                }
                            } else {
                                for (ToolCallStartEvent tc : toolCalls) {
                                    // 正常工具调用摘要降为 debug，失败结果在 onActing 里以 warn 输出。
                                    log.debug(
                                            "[{}] POST_REASONING | tool_call: id={}, name={}",
                                            name,
                                            tc.getToolCallId(),
                                            tc.getToolCallName());
                                }
                            }
                        });
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        // 正常路径摘要降为 debug，每次模型调用不再刷 INFO。
        if (log.isDebugEnabled()) {
            log.debug(
                    "[{}] PRE_MODEL_CALL | model={}, messages={}",
                    agent.getName(),
                    input.model() != null ? input.model().getModelName() : "<unknown>",
                    input.messages() != null ? input.messages().size() : 0);
        }
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        // 正常路径摘要降为 debug；仅失败的工具结果保留 warn 输出。
        String name = agent.getName();
        if (log.isDebugEnabled() && input.toolCalls() != null) {
            for (ToolUseBlock tu : input.toolCalls()) {
                log.debug("[{}] PRE_ACTING  | id={}, name={}", name, tu.getId(), tu.getName());
                log.debug(
                        "[{}] PRE_ACTING  |   args={}",
                        name,
                        truncate(mapToJson(tu.getInput()), 500));
            }
        }
        // Derive POST_ACTING from the tool-result events flowing through the middleware stream
        // (ToolResultStart/TextDelta/End), rather than scanning the context tail on completion.
        // The context append happens after this stream completes, so the previous approach missed
        // successfully-executed tools; observing the events captures every tool deterministically
        // and keeps us off the deprecated hook path.
        Map<String, String> toolNames = new ConcurrentHashMap<>();
        Map<String, StringBuilder> toolText = new ConcurrentHashMap<>();
        return next.apply(input)
                .doOnNext(
                        ev -> {
                            if (ev instanceof ToolResultStartEvent start) {
                                // 工具调用 id 在异常场景下可能为 null（如模型返回 name=null 的工具调用），
                                // ConcurrentHashMap 不允许 null key，这里提前跳过，避免抛 NPE 打断流。
                                String id = start.getToolCallId();
                                if (id == null) {
                                    return;
                                }
                                toolNames.put(id, start.getToolCallName());
                                toolText.computeIfAbsent(id, k -> new StringBuilder());
                            } else if (ev instanceof ToolResultTextDeltaEvent delta) {
                                String id = delta.getToolCallId();
                                if (id == null || delta.getDelta() == null) {
                                    return;
                                }
                                toolText.computeIfAbsent(id, k -> new StringBuilder())
                                        .append(delta.getDelta());
                            } else if (ev instanceof ToolResultEndEvent end) {
                                String id = end.getToolCallId();
                                if (id == null) {
                                    return;
                                }
                                String toolName = toolNames.getOrDefault(id, "<unknown>");
                                String text =
                                        toolText.getOrDefault(id, new StringBuilder()).toString();
                                // 失败/非正常结束的工具执行提升为 warn（排障最关心）；成功结果降为 debug。
                                boolean failed =
                                        end.getState() != null
                                                && end.getState() != ToolResultState.SUCCESS;
                                if (failed) {
                                    log.warn(
                                            "[{}] POST_ACTING | id={}, name={}, result_len={},"
                                                    + " state={}, result={}",
                                            name,
                                            id,
                                            toolName,
                                            text.length(),
                                            end.getState(),
                                            truncate(text, 500));
                                } else if (log.isDebugEnabled()) {
                                    log.debug(
                                            "[{}] POST_ACTING | id={}, name={}, result_len={},"
                                                    + " state={}",
                                            name,
                                            id,
                                            toolName,
                                            text.length(),
                                            end.getState());
                                    log.debug(
                                            "[{}] POST_ACTING |   result={}",
                                            name,
                                            truncate(text, 500));
                                }
                            }
                        });
    }

    private void logPostCall(Agent agent, RuntimeContext rc) {
        String name = agent.getName();
        AgentState state = RuntimeContext.resolveAgentState(rc, agent);
        if (state == null) {
            log.debug("[{}] POST_CALL | response: <n/a>", name);
            return;
        }
        Msg lastAssistant = null;
        List<Msg> ctx = state.getContext();
        for (int i = ctx.size() - 1; i >= 0; i--) {
            if (ctx.get(i).getRole() == MsgRole.ASSISTANT) {
                lastAssistant = ctx.get(i);
                break;
            }
        }
        if (lastAssistant == null) {
            log.debug("[{}] POST_CALL | response: <n/a>", name);
            return;
        }
        String text = lastAssistant.getTextContent();
        // A turn carrying tool calls is never a clean final reply: if it is the last
        // assistant message, the loop ended on a tool-call turn (e.g., the model returned an
        // empty completion right after). Surfacing its preamble as the "response" is misleading,
        // so we flag the situation explicitly and show the preamble only as context.
        boolean endedOnToolCall = !lastAssistant.getContentBlocks(ToolUseBlock.class).isEmpty();
        if (endedOnToolCall) {
            log.info(
                    "[{}] POST_CALL | ended on a tool-call turn with no final text reply; last"
                            + " preamble: {}",
                    name,
                    truncate(text, 120));
        }
    }

    private static String truncate(String s, int max) {
        if (s == null || s.isEmpty()) {
            return "<empty>";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...[truncated, limit=" + max + " chars]";
    }

    private static String mapToJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            return JsonUtils.getJsonCodec().toJson(map);
        } catch (Exception e) {
            return map.toString();
        }
    }
}
