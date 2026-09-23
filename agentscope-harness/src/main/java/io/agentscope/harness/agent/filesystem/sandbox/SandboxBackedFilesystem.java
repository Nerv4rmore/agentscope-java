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
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxAware;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxFileTransfer;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 沙箱按调用懒加载；上下文副本及共享子 Agent 通过同一状态对象复用实例。 */
public class SandboxBackedFilesystem extends BaseSandboxFilesystem implements SandboxAware {

    private static final Logger log = LoggerFactory.getLogger(SandboxBackedFilesystem.class);

    private final String fsId;
    // 仅显式固定实例使用此字段，调用生命周期不得写入共享代理。
    private volatile Sandbox sandbox;

    private static final class CallState {
        private final SandboxBackedFilesystem filesystem;
        private final SandboxManager manager;
        private final SandboxContext sandboxContext;
        private final RuntimeContext runtimeContext;
        private final AtomicInteger unhealthyExecs = new AtomicInteger();
        private SandboxAcquireResult acquireResult;
        private int retainedAsyncCalls;
        private boolean releaseRequested;
        private boolean releaseCompleted;
        private Runnable pendingRelease;

        private CallState(
                SandboxBackedFilesystem filesystem,
                SandboxManager manager,
                SandboxContext sandboxContext,
                RuntimeContext runtimeContext) {
            this.filesystem = filesystem;
            this.manager = manager;
            this.sandboxContext = sandboxContext;
            this.runtimeContext = runtimeContext;
        }

        private Runnable takePendingReleaseIfReady() {
            if (!releaseRequested || retainedAsyncCalls != 0 || releaseCompleted) {
                return null;
            }
            releaseCompleted = true;
            Runnable action = pendingRelease;
            pendingRelease = null;
            return action;
        }
    }

    public static final class SharedLease implements AutoCloseable {
        private final CallState call;
        private boolean closed;

        private SharedLease(CallState call) {
            this.call = call;
        }

        @Override
        public void close() {
            Runnable releaseAction;
            synchronized (call) {
                if (closed) {
                    return;
                }
                closed = true;
                call.retainedAsyncCalls--;
                releaseAction = call.takePendingReleaseIfReady();
            }
            runReleaseAction(releaseAction);
        }
    }

    public SharedLease retainForAsync(RuntimeContext runtimeContext) {
        CallState call = callState(runtimeContext);
        if (call == null) {
            throw new IllegalStateException("Sandbox filesystem is not bound to this call");
        }
        synchronized (call) {
            if (call.releaseCompleted) {
                throw new IllegalStateException(
                        "Sandbox filesystem call has already been released");
            }
            call.retainedAsyncCalls++;
            log.debug("[sandbox-diag] shared retain: sessionId={}, refs={}",
                    call.runtimeContext.getSessionId(), call.retainedAsyncCalls);
            return new SharedLease(call);
        }
    }

    public void requestRelease(RuntimeContext runtimeContext, Runnable releaseAction) {
        CallState call = callState(runtimeContext);
        if (call == null) {
            runReleaseAction(releaseAction);
            return;
        }
        Runnable ready;
        synchronized (call) {
            if (call.releaseRequested) {
                return;
            }
            call.releaseRequested = true;
            call.pendingRelease = releaseAction;
            ready = call.takePendingReleaseIfReady();
            log.info("[sandbox-diag] shared release requested: sessionId={}, refs={}",
                    call.runtimeContext.getSessionId(), call.retainedAsyncCalls);
        }
        runReleaseAction(ready);
    }

    private static void runReleaseAction(Runnable action) {
        if (action != null) {
            action.run();
        }
    }

    // 连续“不健康” execute 失败计数（exitCode=-1 且无任何输出）。达到阈值后熔断：
    // 不再透传裸退出码，而是返回明确的止损文本，避免模型把沙箱已死误判为
    // “命令写错”而无限重试探测命令（sess-dec89eb5 事故：echo/ls/pwd 重试 11 轮）。
    private final AtomicInteger consecutiveUnhealthyExecs = new AtomicInteger();

    /** 触发熔断的连续不健康失败次数阈值。 */
    private static final int UNHEALTHY_EXEC_CIRCUIT_BREAK_THRESHOLD = 3;

    public SandboxBackedFilesystem() {
        this.fsId = "sandbox-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public synchronized void setSandbox(Sandbox sandbox) {
        this.sandbox = sandbox;
        if (sandbox != null) {
            consecutiveUnhealthyExecs.set(0);
        }
    }

    @Override
    public Sandbox getSandbox() {
        return sandbox;
    }

    public void bindLifecycle(
            SandboxManager sandboxManager,
            SandboxContext sandboxContext,
            RuntimeContext callContext) {
        CallState call = new CallState(
                this, sandboxManager, sandboxContext, RuntimeContext.builder(callContext).build());
        // 先绑定可变状态，再复制上下文；后续懒创建对所有副本及共享子 Agent 可见。
        callContext.put(CallState.class, call);
    }

    public SandboxAcquireResult consumeAcquireResult(RuntimeContext runtimeContext) {
        CallState call = callState(runtimeContext);
        if (call == null) {
            return null;
        }
        synchronized (call) {
            SandboxAcquireResult result = call.acquireResult;
            call.acquireResult = null;
            return result;
        }
    }

    public static Sandbox currentSandbox(RuntimeContext runtimeContext) {
        CallState call = runtimeContext != null ? runtimeContext.get(CallState.class) : null;
        if (call == null) {
            return null;
        }
        synchronized (call) {
            return !call.releaseCompleted && call.acquireResult != null
                    ? call.acquireResult.getSandbox() : null;
        }
    }

    public Sandbox getSandbox(RuntimeContext runtimeContext) {
        Sandbox fixed = sandbox;
        if (fixed != null) {
            return fixed;
        }
        return callState(runtimeContext) != null ? currentSandbox(runtimeContext) : null;
    }

    private CallState callState(RuntimeContext runtimeContext) {
        CallState call = runtimeContext != null ? runtimeContext.get(CallState.class) : null;
        return call != null && call.filesystem == this ? call : null;
    }

    /**
     * Clears the fallback {@code sandbox} field only if it still points at {@code expected}. Used by
     * {@link io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware} on release so a
     * finishing call never nulls a concurrent sibling call's fallback binding (issue #2490).
     *
     * @param expected the sandbox this call bound at acquire time
     */
    public synchronized void clearSandboxIfCurrent(Sandbox expected) {
        if (this.sandbox == expected) {
            this.sandbox = null;
        }
    }

    @Override
    public String id() {
        return fsId;
    }

    @Override
    public ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        Sandbox active = requireSandbox(runtimeContext);
        CallState call = callState(runtimeContext);
        AtomicInteger consecutiveUnhealthyExecs =
                call != null ? call.unhealthyExecs : this.consecutiveUnhealthyExecs;
        // 诊断：execute 入口，记录命令与目标沙箱，便于追踪每次工具调用的落点；
        // 正常路径降为 debug，避免每次工具调用刷两条 INFO 淹没主日志，异常路径保留 warn/error。
        log.debug(
                "[sandbox-diag] execute ENTER: sandboxId={}, cmd={}",
                active.getState() != null ? active.getState().getSessionId() : "?",
                command != null && command.length() > 120
                        ? command.substring(0, 120) + "..."
                        : command);
        try {
            ExecResult result = active.exec(runtimeContext, command, timeoutSeconds);
            // 命令通道正常应答（无论业务成败），清零不健康计数
            consecutiveUnhealthyExecs.set(0);
            // 诊断：execute 成功，记录退出码；成功属正常路径，降为 debug。
            log.debug(
                    "[sandbox-diag] execute OK: exitCode={}, truncated={}",
                    result.exitCode(),
                    result.truncated());
            return new ExecuteResponse(
                    result.combinedOutput(), result.exitCode(), result.truncated());
        } catch (SandboxException.ExecTimeoutException e) {
            // 超时说明命令通道至少接受了命令，不计为不健康
            consecutiveUnhealthyExecs.set(0);
            log.warn("[sandbox-diag] execute TIMEOUT: cmd={}", command);
            return new ExecuteResponse(e.getMessage(), 124, false);
        } catch (SandboxException.ExecException e) {
            String combined =
                    (e.getStdout() != null ? e.getStdout() : "")
                            + (e.getStderr() != null && !e.getStderr().isBlank()
                                    ? "\n" + e.getStderr()
                                    : "");
            // 熔断判定：exitCode=-1 且无输出是“沙箱不健康”信号而非普通命令失败。
            // 连续达到阈值后返回明确的英文止损文本，让模型停止重试而不是烧迭代。
            boolean unhealthy = e.getExitCode() == -1 && combined.isBlank();
            if (unhealthy) {
                int count = consecutiveUnhealthyExecs.incrementAndGet();
                if (count >= UNHEALTHY_EXEC_CIRCUIT_BREAK_THRESHOLD) {
                    log.error(
                            "[sandbox-diag] execute CIRCUIT BREAK: {} consecutive unhealthy"
                                    + " execs (exitCode=-1, empty output); refusing further"
                                    + " retries. cmd={}",
                            count,
                            command);
                    return new ExecuteResponse(
                            "Sandbox is unavailable (instance unhealthy: every command returns"
                                    + " exit code -1 with no output, and automatic recreation"
                                    + " did not recover it). Do NOT retry execute — the shell"
                                    + " cannot recover in this call. Stop using shell commands,"
                                    + " finish with whatever files you already have, and tell"
                                    + " the user the sandbox environment is currently"
                                    + " unavailable.",
                            e.getExitCode(),
                            false);
                }
            } else {
                // 普通命令失败（带退出码或输出）说明通道存活，重置计数
                consecutiveUnhealthyExecs.set(0);
            }
            log.warn(
                    "[sandbox-diag] execute EXEC ERROR: exitCode={}, msg={}",
                    e.getExitCode(),
                    e.getMessage());
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
            // 超时是基建故障，不是脚本错误。不说清楚的话模型会当成"脚本写错了"而重写重跑，
            // 关键信息：命令可能已在沙箱里跑完了——响应丢了，不等于工作没做。
            if (isTimeout(e)) {
                return new ExecuteResponse(
                        "Sandbox timeout: the command did not return in time."
                                + " This is an infrastructure failure, NOT an error in your"
                                + " command — do NOT rewrite it and do NOT re-run it as-is."
                                + " The command may have already completed inside the sandbox"
                                + " (the response was lost, not necessarily the work): list the"
                                + " files it should have produced before deciding anything."
                                + " If you must run it again, first make it finish faster —"
                                + " the gateway abandons any command that exceeds ~30s, so cap"
                                + " every network or long-running operation well under that.",
                        -1,
                        false);
            }
            return new ExecuteResponse("Internal sandbox error: " + e.getMessage(), -1, false);
        }
    }

    /**
     * True when a failure is a transport timeout rather than a command error.
     *
     * <p>Walks the cause chain because the transport exception is usually wrapped. Matches {@link
     * InterruptedIOException}, which covers both flavours OkHttp raises: a read-timeout expiry
     * ({@link java.net.SocketTimeoutException}) and a call-timeout expiry (a plain {@code
     * InterruptedIOException}). The message is also checked, since some layers rewrap the cause as a
     * generic exception and only preserve the text.
     */
    private static boolean isTimeout(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof InterruptedIOException || cur instanceof TimeoutException) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("timeout") || lower.contains("timed out")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        Sandbox active = requireSandbox(runtimeContext);
        List<FileUploadResponse> results = new ArrayList<>(files.size());

        for (Map.Entry<String, byte[]> file : files) {
            String path = file.getKey();
            byte[] content = file.getValue();
            if (content == null) {
                results.add(FileUploadResponse.fail(path, "File content must not be null"));
                continue;
            }

            if (active instanceof SandboxFileTransfer transfer) {
                String transferPath = null;
                try {
                    transferPath = resolveTransferPath(active, path);
                } catch (IllegalArgumentException e) {
                    // Same contract as the archive fallback: an invalid path fails this file.
                    log.warn("[sandbox-fs] uploadFiles failed for path: {}", path, e);
                    results.add(FileUploadResponse.fail(path, e.getMessage()));
                    continue;
                } catch (IOException e) {
                    log.debug(
                            "[sandbox-fs] Workspace root unavailable, keeping archive fallback: {}",
                            path);
                }
                if (transferPath != null && transfer.supportsFileTransfer(transferPath)) {
                    try {
                        transfer.uploadFile(transferPath, content);
                        results.add(FileUploadResponse.success(path));
                    } catch (Exception e) {
                        log.warn("[sandbox-fs] native upload failed for path: {}", path, e);
                        results.add(FileUploadResponse.fail(path, e.getMessage()));
                    }
                    continue;
                }
            }

            try {
                byte[] archive = buildSingleFileArchive(active, path, content);
                try (InputStream archiveStream = new ByteArrayInputStream(archive)) {
                    active.hydrateWorkspace(archiveStream);
                }
                results.add(FileUploadResponse.success(path));
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
            // Reads keep the raw-path probe, unlike uploads: no shared temp state to race on,
            // and the exec fallback below stays a single round trip.
            if (active instanceof SandboxFileTransfer transfer
                    && transfer.supportsFileTransfer(path)) {
                try {
                    results.add(FileDownloadResponse.success(path, transfer.downloadFile(path)));
                } catch (Exception e) {
                    log.warn("[sandbox-fs] native download failed for path: {}", path, e);
                    results.add(FileDownloadResponse.fail(path, e.getMessage()));
                }
                continue;
            }

            try {
                // Polymorphic dispatch: backends with a dedicated download endpoint (AgentRun's
                // data-plane GET /filesystem/download) override Sandbox#downloadFile and bypass the
                // shell base64 round trip entirely. The default implementation in that interface
                // carries the sandbox-truncation guard (upstream #2923).
                byte[] bytes = active.downloadFile(path);
                results.add(FileDownloadResponse.success(path, bytes));
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

    public Sandbox requireSandbox(RuntimeContext runtimeContext) {
        Sandbox fixed = sandbox;
        if (fixed != null) {
            return fixed;
        }
        CallState call = callState(runtimeContext);
        if (call == null) {
            throw new SandboxException.SandboxConfigurationException(
                    "No active sandbox — sandbox filesystem used outside of a call context");
        }
        synchronized (call) {
            if (call.releaseCompleted) {
                throw new SandboxException.SandboxConfigurationException(
                        "Sandbox filesystem call has already been released");
            }
            if (call.acquireResult != null) {
                return call.acquireResult.getSandbox();
            }
            log.info("[sandbox-diag] requireSandbox LAZY CREATE: userId={}, sessionId={}, root={}",
                    call.runtimeContext.getUserId(), call.runtimeContext.getSessionId(),
                    call.runtimeContext.get(SandboxManager.CALL_WORKSPACE_ROOT_KEY, String.class));
            try {
                SandboxAcquireResult result =
                        call.manager.acquire(call.sandboxContext, call.runtimeContext);
                Sandbox acquired = result.getSandbox();
                try {
                    acquired.start();
                } catch (Exception startErr) {
                    try {
                        call.manager.release(result);
                    } catch (Exception releaseErr) {
                        log.warn("[sandbox-fs] Failed to release sandbox after start failure",
                                releaseErr);
                    } finally {
                        result.getLease().close();
                    }
                    throw startErr;
                }
                call.acquireResult = result;
                log.info("[sandbox-diag] requireSandbox LAZY CREATE OK: userId={}, sessionId={},"
                                + " sandboxSessionId={}, workspaceRoot={}",
                        call.runtimeContext.getUserId(), call.runtimeContext.getSessionId(),
                        acquired.getState() != null ? acquired.getState().getSessionId() : "?",
                        acquired.workspaceRoot());
                return acquired;
            } catch (SandboxException e) {
                throw e;
            } catch (Exception e) {
                throw new SandboxException.SandboxConfigurationException(
                        "Failed to acquire sandbox for this call: " + e.getMessage(), e);
            }
        }
    }

    /** Builds a single-file tar archive relative to the workspace root. */
    private byte[] buildSingleFileArchive(Sandbox active, String path, byte[] content)
            throws IOException {
        if (content == null) {
            throw new IOException("File content must not be null");
        }

        String archivePath = resolveArchivePath(active, path);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(output)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry entry = new TarArchiveEntry(archivePath);
            entry.setSize(content.length);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
            tar.finish();
        }
        return output.toByteArray();
    }

    /** Constrains an upload path to the workspace and converts it to an archive path. */
    private String resolveArchivePath(Sandbox active, String path) throws IOException {
        AbstractFilesystem.validatePath(path);
        String normalized = normalizeUploadPath(path);

        if (normalized.startsWith("/")) {
            String workspaceRoot = resolveWorkspaceRoot(active);
            String rootPrefix = "/".equals(workspaceRoot) ? "/" : workspaceRoot + "/";
            if (!normalized.startsWith(rootPrefix)) {
                throw new IOException("Upload path is outside the sandbox workspace: " + path);
            }
            normalized = normalized.substring(rootPrefix.length());
        }

        if (normalized.isBlank()) {
            throw new IOException("Upload path must identify a file: " + path);
        }
        return normalized;
    }

    /**
     * Normalizes an upload path and resolves it to its sandbox-absolute form so
     * workspace-relative paths can ride the native single-file transfer
     * ({@link SandboxFileTransfer#uploadFile}) instead of the archive-hydrate fallback. Throws
     * {@link IllegalArgumentException} for invalid paths — the caller fails just that file,
     * matching the archive fallback's validation contract.
     */
    private String resolveTransferPath(Sandbox active, String path) throws IOException {
        AbstractFilesystem.validatePath(path);
        String normalized = normalizeUploadPath(path);
        if (normalized.startsWith("/")) {
            return normalized;
        }
        return resolveWorkspaceRoot(active) + "/" + normalized;
    }

    /** Normalizes separators and strips leading {@code ./} segments. */
    private static String normalizeUploadPath(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    /** Resolves the normalized anchor for relative paths: exec cwd first, spec root second. */
    private String resolveWorkspaceRoot(Sandbox active) throws IOException {
        String root = active.workspaceRoot();
        if (root == null || root.isBlank()) {
            SandboxState state = active.getState();
            WorkspaceSpec workspaceSpec = state != null ? state.getWorkspaceSpec() : null;
            root = workspaceSpec != null ? workspaceSpec.getRoot() : null;
        }
        if (root == null || root.isBlank()) {
            throw new IOException("Sandbox workspace root is unavailable");
        }

        String normalized = root.replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.startsWith("/")) {
            throw new IOException("Sandbox workspace root must be absolute: " + root);
        }
        return normalized;
    }
}
