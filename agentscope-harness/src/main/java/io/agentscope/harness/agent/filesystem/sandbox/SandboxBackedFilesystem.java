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
package io.agentscope.harness.agent.filesystem.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxAware;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link BaseSandboxFilesystem} that delegates execution to a live {@link Sandbox}.
 *
 * <p>Stable proxy created at agent build time; a fresh {@link Sandbox} is injected on each call
 * via the volatile {@code sandbox} field by {@link
 * io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware}.
 *
 * <p><b>Lazy sandbox creation:</b> since v2.0.0 the sandbox is no longer created eagerly at the
 * start of every agent call. Instead the {@link
 * io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware} only binds the
 * sandbox-creation dependencies ({@link SandboxManager} + {@link SandboxContext}) via {@link
 * #bindLifecycle}; the actual {@code SandboxManager.acquire} + {@code Sandbox.start} is deferred
 * to the first filesystem operation that actually needs a sandbox (i.e. the first call to {@link
 * #requireSandbox}). Calls that never touch the sandbox filesystem — pure-text replies or tools
 * that do not read/write/exec — therefore pay zero sandbox creation cost. The lazily-created
 * {@link SandboxAcquireResult} is exposed to the middleware via {@link #consumeAcquireResult} so
 * the normal end-of-call release path still runs when (and only when) a sandbox was created.
 */
public class SandboxBackedFilesystem extends BaseSandboxFilesystem implements SandboxAware {

    private static final Logger log = LoggerFactory.getLogger(SandboxBackedFilesystem.class);

    private final String fsId;
    private volatile Sandbox sandbox;

    // 懒创建依赖：由 SandboxLifecycleMiddleware.acquireForCall 在每次调用开始时注入，
    // 供 requireSandbox 在首次需要沙箱时按需 acquire + start。
    private volatile SandboxManager sandboxManager;
    private volatile SandboxContext sandboxContext;
    // 本次调用懒创建产生的 AcquireResult；未创建沙箱时为 null。release 时消费。
    private volatile SandboxAcquireResult lazyAcquireResult;

    public SandboxBackedFilesystem() {
        this.fsId = "sandbox-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public void setSandbox(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public Sandbox getSandbox() {
        return sandbox;
    }

    /**
     * 绑定本次调用所需的懒创建依赖。由 {@link
     * io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware#acquireForCall} 在每次
     * agent 调用开始时调用。绑定后不会立即创建沙箱，沙箱将在首次文件系统操作时按需创建。
     *
     * @param sandboxManager 沙箱生命周期管理器（acquire/release）
     * @param sandboxContext 当前调用的沙箱配置（从 RuntimeContext 取出）
     */
    public void bindLifecycle(SandboxManager sandboxManager, SandboxContext sandboxContext) {
        this.sandboxManager = sandboxManager;
        this.sandboxContext = sandboxContext;
        this.lazyAcquireResult = null;
    }

    /**
     * 返回并清空本次调用懒创建产生的 {@link SandboxAcquireResult}。
     *
     * <p>供 {@link
     * io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware#releaseForCall} 在调用结束
     * 时消费：若本次调用实际创建过沙箱则返回非 null（中间件据此执行 persist + release）；若本次
     * 调用从未触发沙箱创建则返回 null（中间件跳过释放）。
     *
     * @return 本次调用懒创建的 AcquireResult，未创建则返回 null
     */
    public SandboxAcquireResult consumeAcquireResult() {
        SandboxAcquireResult r = lazyAcquireResult;
        lazyAcquireResult = null;
        return r;
    }

    @Override
    public String id() {
        return fsId;
    }

    @Override
    public ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        Sandbox active = requireSandbox(runtimeContext);
        // 诊断：execute 入口，记录命令与目标沙箱，便于追踪每次工具调用的落点
        log.info(
                "[sandbox-diag] execute ENTER: sandboxId={}, cmd={}",
                active.getState() != null ? active.getState().getSessionId() : "?",
                command != null && command.length() > 120
                        ? command.substring(0, 120) + "..."
                        : command);
        try {
            ExecResult result = active.exec(runtimeContext, command, timeoutSeconds);
            // 诊断：execute 成功，记录退出码
            log.info(
                    "[sandbox-diag] execute OK: exitCode={}, truncated={}",
                    result.exitCode(),
                    result.truncated());
            return new ExecuteResponse(
                    result.combinedOutput(), result.exitCode(), result.truncated());
        } catch (SandboxException.ExecTimeoutException e) {
            log.warn("[sandbox-diag] execute TIMEOUT: cmd={}", command);
            return new ExecuteResponse(e.getMessage(), 124, false);
        } catch (SandboxException.ExecException e) {
            log.warn(
                    "[sandbox-diag] execute EXEC ERROR: exitCode={}, msg={}",
                    e.getExitCode(),
                    e.getMessage());
            String combined =
                    (e.getStdout() != null ? e.getStdout() : "")
                            + (e.getStderr() != null && !e.getStderr().isBlank()
                                    ? "\n" + e.getStderr()
                                    : "");
            return new ExecuteResponse(combined, e.getExitCode(), false);
        } catch (Exception e) {
            // 诊断：execute 异常被吞成 ExecuteResponse（模型看到错误文本而非抛异常，
            // 这是模型反复重试 execute 的原因）
            log.warn(
                    "[sandbox-diag] execute SWALLOW ERROR: errorType={}, cmd={}, msg={}",
                    e.getClass().getSimpleName(),
                    command,
                    e.getMessage());
            log.error("[sandbox-fs] execute failed: {}", command, e);
            return new ExecuteResponse("Internal sandbox error: " + e.getMessage(), -1, false);
        }
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        Sandbox active = requireSandbox(runtimeContext);
        List<FileUploadResponse> results = new ArrayList<>(files.size());

        for (Map.Entry<String, byte[]> file : files) {
            String path = file.getKey();
            byte[] content = file.getValue();

            try {
                String base64Content = Base64.getEncoder().encodeToString(content);
                String escapedPath = shellSingleQuote(path);
                String cmd =
                        "mkdir -p $(dirname "
                                + escapedPath
                                + ") && "
                                + "printf '%s' '"
                                + base64Content
                                + "' | base64 -d > "
                                + escapedPath;

                ExecResult result = active.exec(runtimeContext, cmd, null);
                if (result.ok()) {
                    results.add(FileUploadResponse.success(path));
                } else {
                    results.add(FileUploadResponse.fail(path, result.combinedOutput()));
                }
            } catch (SandboxException.ExecException e) {
                String combined =
                        (e.getStdout() != null ? e.getStdout() : "")
                                + (e.getStderr() != null && !e.getStderr().isBlank()
                                        ? "\n" + e.getStderr()
                                        : "");
                results.add(FileUploadResponse.fail(path, combined));
            } catch (Exception e) {
                log.warn("[sandbox-fs] uploadFiles failed for path: {}", path, e);
                results.add(FileUploadResponse.fail(path, e.getMessage()));
            }
        }

        return results;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        Sandbox active = requireSandbox(runtimeContext);
        List<FileDownloadResponse> results = new ArrayList<>(paths.size());

        for (String path : paths) {
            try {
                String escapedPath = shellSingleQuote(path);
                String cmd = "base64 " + escapedPath;

                ExecResult result = active.exec(runtimeContext, cmd, null);
                if (result.ok()) {
                    // MIME decoder tolerates wrapped base64 output from GNU `base64`.
                    byte[] decoded =
                            Base64.getMimeDecoder()
                                    .decode(result.stdout() != null ? result.stdout() : "");
                    results.add(FileDownloadResponse.success(path, decoded));
                } else {
                    results.add(FileDownloadResponse.fail(path, result.combinedOutput()));
                }
            } catch (SandboxException.ExecException e) {
                String combined =
                        (e.getStdout() != null ? e.getStdout() : "")
                                + (e.getStderr() != null && !e.getStderr().isBlank()
                                        ? "\n" + e.getStderr()
                                        : "");
                results.add(FileDownloadResponse.fail(path, combined));
            } catch (Exception e) {
                log.warn("[sandbox-fs] downloadFiles failed for path: {}", path, e);
                results.add(FileDownloadResponse.fail(path, e.getMessage()));
            }
        }

        return results;
    }

    /**
     * 获取当前活跃沙箱；若尚未创建则按需懒创建。
     *
     * <p>懒创建语义：当 {@code sandbox} 为 null 且已通过 {@link #bindLifecycle} 绑定
     * {@link SandboxManager} + {@link SandboxContext} 时，从 {@code runtimeContext} 取出
     * {@link SandboxContext}，调用 {@link SandboxManager#acquire} 获取沙箱并 {@link Sandbox#start}
     * 启动，随后注入到 {@code sandbox} 字段供本次调用后续操作复用。产生的
     * {@link SandboxAcquireResult} 暂存到 {@link #lazyAcquireResult}，由
     * {@link io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware#releaseForCall}
     * 在调用结束时消费释放。
     *
     * <p>使用 synchronized + double-check 保证同一调用内多个工具并发触发时只创建一次。
     *
     * @param runtimeContext 当前调用的 RuntimeContext（携带 SandboxContext）
     * @return 当前活跃沙箱
     * @throws SandboxException.SandboxConfigurationException 既未注入沙箱也未绑定懒创建依赖
     */
    private Sandbox requireSandbox(RuntimeContext runtimeContext) {
        Sandbox s = sandbox;
        if (s != null) {
            // 诊断：复用当前调用已创建/注入的沙箱（同一次 agent 调用内多个工具共享）
            log.debug(
                    "[sandbox-diag] requireSandbox REUSE: sandboxId={}",
                    s.getState() != null ? s.getState().getSessionId() : "?");
            return s;
        }
        // 已绑定懒创建依赖：首次需要沙箱时按需创建
        SandboxManager manager = sandboxManager;
        if (manager == null) {
            throw new SandboxException.SandboxConfigurationException(
                    "No active sandbox — sandbox filesystem used outside of a call context");
        }
        SandboxContext ctx = sandboxContext;
        // 优先使用绑定时的 sandboxContext；若为 null 则尝试从 runtimeContext 取
        if (ctx == null && runtimeContext != null) {
            ctx = runtimeContext.get(SandboxContext.class);
        }
        if (ctx == null) {
            throw new SandboxException.SandboxConfigurationException(
                    "No active sandbox — sandbox context not bound for lazy creation");
        }
        // 诊断：懒创建入口，记录当前 sandbox 为 null，将触发 acquire（Priority 3 resume 或 4 create）
        log.info(
                "[sandbox-diag] requireSandbox LAZY CREATE: sandbox==null, manager={}, ctx={}",
                manager != null ? manager.getClass().getSimpleName() : "null",
                ctx.getExternalSandbox() != null
                        ? "externalSandbox"
                        : (ctx.getExternalSandboxState() != null
                                ? "externalSandboxState"
                                : "harness-managed"));
        synchronized (this) {
            s = sandbox;
            if (s != null) {
                return s;
            }
            try {
                SandboxAcquireResult result = manager.acquire(ctx, runtimeContext);
                Sandbox acquired = result.getSandbox();
                try {
                    acquired.start();
                } catch (Exception startErr) {
                    // 诊断：懒创建后 start 失败，记录 sandboxId 与异常，定位复用/重建失败
                    log.warn(
                            "[sandbox-diag] requireSandbox LAZY START FAILED: sandboxId={},"
                                    + " error={}",
                            acquired.getState() != null ? acquired.getState().getSessionId() : "?",
                            startErr.getMessage());
                    // start 失败需回滚 acquire，避免沙箱泄漏
                    try {
                        manager.release(result);
                    } catch (Exception releaseErr) {
                        log.warn(
                                "[sandbox-fs] Failed to release sandbox after lazy start failure:"
                                        + " {}",
                                releaseErr.getMessage(),
                                releaseErr);
                    }
                    result.getLease().close();
                    throw startErr;
                }
                this.sandbox = acquired;
                this.lazyAcquireResult = result;
                // 诊断：懒创建成功，记录最终 sandboxId
                log.info(
                        "[sandbox-diag] requireSandbox LAZY CREATE OK: sandboxId={}",
                        acquired.getState() != null ? acquired.getState().getSessionId() : "?");
                return acquired;
            } catch (SandboxException e) {
                throw e;
            } catch (Exception e) {
                throw new SandboxException.SandboxConfigurationException(
                        "Failed to lazily create sandbox: " + e.getMessage(), e);
            }
        }
    }

    private String shellSingleQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
