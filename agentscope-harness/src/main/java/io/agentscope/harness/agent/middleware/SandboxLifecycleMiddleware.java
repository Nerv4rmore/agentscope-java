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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Middleware that manages the sandbox session lifecycle around each agent call.
 *
 * <p><b>Lazy creation (since v2.0.0):</b> the sandbox is no longer created eagerly at the start
 * of every call. {@link #acquireForCall} only binds the sandbox-creation dependencies
 * ({@link SandboxManager} + {@link SandboxContext}) into the {@link SandboxBackedFilesystem}
 * proxy; the actual {@code acquire} + {@code start} is deferred to the first filesystem
 * operation that needs a sandbox (see {@link SandboxBackedFilesystem#requireSandbox}). Calls
 * that never touch the sandbox filesystem therefore pay zero sandbox creation cost.
 *
 * <h2>Pre-{@code next.apply}</h2>
 * <ol>
 *   <li>Read {@link SandboxContext} from the current {@link RuntimeContext}</li>
 *   <li>Bind {@link SandboxManager} + {@link SandboxContext} into the filesystem proxy for lazy
 *       creation (no sandbox is created yet); external sandboxes are acquired eagerly and also
 *       bound per-call on the {@link RuntimeContext} (issue #2490)</li>
 * </ol>
 *
 * <h2>doFinally</h2>
 * <ol>
 *   <li>Consume the lazily-created {@link SandboxAcquireResult} from the filesystem proxy
 *       (null when no sandbox was created this call)</li>
 *   <li>Only if non-null: persist sandbox session state via {@link SandboxManager} and
 *       {@link io.agentscope.harness.agent.sandbox.SessionSandboxStateStore}</li>
 *   <li>Only if non-null: release the session via {@link SandboxManager} (stop + optional
 *       shutdown)</li>
 *   <li>Clear this call's per-call binding from the {@link RuntimeContext} and compare-and-clear
 *       the filesystem proxy's fallback field
 *       ({@link SandboxBackedFilesystem#clearSandboxIfCurrent}, issue #2490)</li>
 * </ol>
 *
 * <p>Post-call failures (persist, release) are logged but do not propagate — this ensures
 * the agent call result is always returned to the caller even if sandbox cleanup fails.
 *
 * <p>The sandbox is bound <em>per call</em> on the invocation's {@link RuntimeContext} rather than
 * on a shared agent-level slot: distinct {@code (userId, sessionId)} sessions run in parallel on
 * the same agent bean, so a shared slot would let concurrent calls corrupt each other's binding
 * (issue #2490).
 */
public class SandboxLifecycleMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SandboxLifecycleMiddleware.class);

    private final SandboxManager sandboxManager;
    private final SandboxBackedFilesystem filesystemProxy;
    private volatile Consumer<RuntimeContext> beforeStartCallback;

    public SandboxLifecycleMiddleware(
            SandboxManager sandboxManager, SandboxBackedFilesystem filesystemProxy) {
        this.sandboxManager = sandboxManager;
        this.filesystemProxy = filesystemProxy;
    }

    /**
     * Registers a callback that runs after the sandbox session is acquired but before
     * {@link io.agentscope.harness.agent.sandbox.Sandbox#start()} applies workspace projection.
     * This allows callers to materialise resources on the host workspace (e.g.
     * {@code .skills-cache/}) so that projection picks them up in the same call.
     *
     * @param callback receives the per-call {@link RuntimeContext}; may be {@code null} to clear
     */
    public void setBeforeStartCallback(Consumer<RuntimeContext> callback) {
        this.beforeStartCallback = callback;
    }

    /**
     * 绑定本次调用所需的懒创建依赖，不再立即创建沙箱。
     *
     * <p>从 {@code ctx} 取出 {@link SandboxContext}，连同 {@link SandboxManager} 一起注入到
     * {@link SandboxBackedFilesystem} 代理。沙箱将在首次文件系统操作（execute/ls/read/write
     * 等）真正需要时由 {@link SandboxBackedFilesystem#requireSandbox} 按需创建；若本次调用
     * 从未触及沙箱文件系统，则不会创建任何沙箱。
     *
     * <p>同时保留 {@link #currentAcquireResult} 机制以兼容外部沙箱场景：当 {@link SandboxContext}
     * 携带外部沙箱（externalSandbox/externalSandboxState）时，仍按原有方式立即 acquire 并注入，
     * 确保外部沙箱在调用开始即就绪（外部沙箱由调用方管理，不涉及懒创建收益）。
     *
     * @param ctx the per-call RuntimeContext (must not be null)
     */
    public void acquireForCall(RuntimeContext ctx) {
        if (ctx == null) {
            return;
        }
        SandboxContext sandboxContext = ctx.get(SandboxContext.class);
        if (sandboxContext == null) {
            return;
        }
        // 诊断：每次 agent 调用开始的 acquire 边界，记录 sessionId/userId 与是否外部沙箱，
        // 用于核对同一 SSE 流内 acquire/release 的次数
        log.info(
                "[sandbox-diag] acquireForCall: sessionId={}, userId={}, externalSandbox={},"
                        + " externalState={}",
                ctx.getSessionId(),
                ctx.getUserId(),
                sandboxContext.getExternalSandbox() != null,
                sandboxContext.getExternalSandboxState() != null);
        // 外部沙箱（Priority 1/2）由调用方管理，保持原有"立即 acquire + 注入"语义，
        // 不走懒创建路径。
        if (sandboxContext.getExternalSandbox() != null
                || sandboxContext.getExternalSandboxState() != null) {
            try {
                // pre-stage hook: runs after acquire, before sandbox.start() workspace projection
                Consumer<RuntimeContext> cb = beforeStartCallback;
                if (cb != null) {
                    try {
                        cb.accept(ctx);
                    } catch (Exception e) {
                        log.warn(
                                "[sandbox-mw] beforeStartCallback failed; proceeding with sandbox"
                                        + " start: {}",
                                e.getMessage(),
                                e);
                    }
                }
                SandboxAcquireResult result = sandboxManager.acquire(sandboxContext, ctx);
                Sandbox sandbox = result.getSandbox();
                try {
                    sandbox.start();
                    // 上游 #2490：除共享字段外，再把本次调用的沙箱按调用绑定到 RuntimeContext；
                    // 同一 agent bean 上并发的不同 (userId, sessionId) 会话靠按调用绑定隔离，
                    // filesystem proxy 优先从 ctx 解析，共享字段仅作无 ctx 调用方的兜底。
                    ctx.put(SandboxAcquireResult.class, result);
                    filesystemProxy.setSandbox(sandbox);
                    currentAcquireResult.set(result);
                    log.debug(
                            "[sandbox-mw] Acquired external sandbox {}",
                            sandbox.getState() != null ? sandbox.getState().getSessionId() : "?");
                } catch (Exception e) {
                    ctx.put(SandboxAcquireResult.class, null);
                    filesystemProxy.clearSandboxIfCurrent(sandbox);
                    try {
                        sandboxManager.release(result);
                    } catch (Exception releaseErr) {
                        log.warn(
                                "[sandbox-mw] Failed to release session after pre-call failure: {}",
                                releaseErr.getMessage(),
                                releaseErr);
                    }
                    result.getLease().close();
                    throw e;
                }
            } catch (Exception e) {
                log.error("[sandbox-mw] Failed to acquire/start external sandbox", e);
                throw new RuntimeException(e);
            }
            return;
        }
        // 常规路径：仅绑定懒创建依赖，沙箱将在首次文件系统操作时按需创建。
        // 同时快照本次调用的 RuntimeContext：子 Agent 构建期操作（如读 tools.json）可能
        // 用空 ctx 触发懒创建，requireSandbox 需要该快照兜底解析隔离键与会话工作区根。
        filesystemProxy.bindLifecycle(sandboxManager, sandboxContext, ctx);
        log.debug("[sandbox-mw] Bound lazy sandbox creation dependencies (no sandbox created yet)");
    }

    /**
     * 释放本次调用懒创建的沙箱（若已创建）。在 agent 调用结束时调用。
     *
     * <p>从 {@link SandboxBackedFilesystem#consumeAcquireResult} 取出本次调用懒创建产生的
     * {@link SandboxAcquireResult}：
     * <ul>
     *   <li>非 null（本次调用实际创建过沙箱）：执行 persistState + release + lease.close，
     *       保持"流结束即释放"语义。</li>
     *   <li>null（本次调用从未创建沙箱）：跳过释放，无操作。</li>
     * </ul>
     *
     * <p>同时兼容外部沙箱场景：若 {@link #currentAcquireResult} 中存有外部沙箱的结果，
     * 优先按原有方式释放。
     *
     * @param ctx the per-call RuntimeContext (captured at acquire time)
     */
    public void releaseForCall(RuntimeContext ctx) {
        filesystemProxy.requestRelease(() -> releaseNow(ctx));
    }

    private void releaseNow(RuntimeContext ctx) {
        // 诊断：每次 agent 调用结束的 release 边界，记录 sessionId/userId，
        // 用于核对同一 SSE 流内 acquire/release 的次数与是否中途 release 清空 sandbox
        log.info(
                "[sandbox-diag] releaseForCall: sessionId={}, userId={}",
                ctx != null ? ctx.getSessionId() : "null",
                ctx != null ? ctx.getUserId() : "null");
        // 上游 #2490：优先回读本调用在 acquireForCall 中建立的按调用绑定，
        // 确保释放时只拆除本次调用自己获取的沙箱，不误伤并发会话。
        SandboxAcquireResult result = ctx != null ? ctx.get(SandboxAcquireResult.class) : null;
        if (result == null) {
            // 外部沙箱路径：消费 currentAcquireResult
            result = currentAcquireResult.getAndSet(null);
        }
        if (result == null) {
            // 常规路径：消费懒创建产生的 AcquireResult（未创建沙箱时为 null）
            result = filesystemProxy.consumeAcquireResult();
            log.info(
                    "[sandbox-diag] releaseForCall: lazyAcquireResult={}",
                    result != null ? "non-null(release)" : "null(skip)");
        }
        if (result == null) {
            // 本次调用从未创建沙箱，无需释放；不再无条件清空共享字段，
            // 避免误清并发兄弟调用的兜底绑定（issue #2490）
            log.info(
                    "[sandbox-diag] releaseForCall: no sandbox created this call (skip release)");
            return;
        }
        if (ctx != null) {
            ctx.put(SandboxAcquireResult.class, null);
        }
        // Compare-and-clear the fallback field so a releasing call never nulls a concurrent
        // sibling's binding (issue #2490); it only clears the field when it still points here.
        filesystemProxy.clearSandboxIfCurrent(result.getSandbox());
        SandboxContext sandboxContext = ctx != null ? ctx.get(SandboxContext.class) : null;
        try {
            sandboxManager.persistState(result, sandboxContext, ctx);
        } catch (Exception e) {
            log.warn("[sandbox-mw] Failed to persist sandbox state: {}", e.getMessage(), e);
        }
        try {
            sandboxManager.release(result);
        } catch (Exception e) {
            log.warn("[sandbox-mw] Failed to release sandbox session: {}", e.getMessage(), e);
        }
        result.getLease().close();
        // 兜底字段的清理已由上方 clearSandboxIfCurrent 完成（仅当仍指向本调用的沙箱时），
        // 此处不再无条件 setSandbox(null)，避免清掉并发兄弟调用的绑定（issue #2490）
        log.info("[sandbox-diag] releaseForCall DONE: sandbox session released");
    }
}
