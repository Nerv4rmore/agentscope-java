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

import java.util.Optional;
import reactor.util.context.ContextView;

/**
 * 工具执行期间的「延迟事件发射器」：工具调用 {@link #emit(AgentEvent)} 的事件不会立即进入事件流，
 * 而是被暂存到 per-toolCallId 的缓冲队列，由框架在该工具的 {@code ToolResultEndEvent}（tool.end）
 * <b>之后</b>统一 flush 到 streamEvents() 的 Flux 中。
 *
 * <p>与 {@link AgentEventEmitter} 的区别：
 * <ul>
 *   <li>{@link AgentEventEmitter} —— 立即发射，事件出现在 tool.end <b>之前</b>（工具执行过程中）。</li>
 *   <li>{@code DeferredEventEmitter} —— 延迟发射，事件出现在 tool.end <b>之后</b>，时序更符合
 *       「工具执行完毕后以卡片/通知形式展示」的语义。</li>
 * </ul>
 *
 * <p>工具方法需返回 {@code Mono<String>}（或 {@code Mono<ToolResultBlock>}），通过
 * {@code Mono.deferContextual(ctx -> DeferredEventEmitter.fromContext(ctx).ifPresent(e -> e.emit(...)))}
 * 拿到实例后调用 {@link #emit}。返回 {@code String} 的同步工具无法访问 Reactor Context，故拿不到本接口。
 *
 * <p>当父 agent 通过非流式 {@code call()} 调用时，Context 中不存在本接口实例，
 * {@link #fromContext(ContextView)} 返回 {@link Optional#empty()}，调用方可优雅降级。
 */
@FunctionalInterface
public interface DeferredEventEmitter {

    /**
     * Reactor Context key，框架在 runToolBatch 中把当前 toolCallId 对应的 emitter 实例存入此 key。
     * 使用 {@link #fromContext(ContextView)} 获取。
     */
    String CONTEXT_KEY = "agentscope.agent.event.deferred.emitter";

    /**
     * 暂存一个事件，使其在该工具的 {@code ToolResultEndEvent} 之后才进入事件流。
     *
     * <p>本方法线程安全；底层缓冲队列使用并发安全结构。
     *
     * @param event 待延迟发射的事件（通常为 {@link CustomEvent}）
     */
    void emit(AgentEvent event);

    /**
     * 从 Reactor Context 获取延迟事件发射器，若不存在则返回 {@link Optional#empty()}。
     *
     * <p>不存在的情况：非流式调用、或工具未返回 {@code Mono}。
     *
     * @param ctx 当前 Reactor 订阅者上下文
     * @return 包含发射器的 Optional，或空
     */
    static Optional<DeferredEventEmitter> fromContext(ContextView ctx) {
        if (ctx == null || !ctx.hasKey(CONTEXT_KEY)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ctx.getOrDefault(CONTEXT_KEY, null));
    }
}
