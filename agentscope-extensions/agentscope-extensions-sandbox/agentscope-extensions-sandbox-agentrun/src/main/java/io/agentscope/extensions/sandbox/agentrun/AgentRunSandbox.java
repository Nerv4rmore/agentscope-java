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
package io.agentscope.extensions.sandbox.agentrun;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.AbstractBaseSandbox;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.WorkspaceMountSupport;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link io.agentscope.harness.agent.sandbox.Sandbox} backed by an Alibaba Cloud AgentRun sandbox
 * (FC 3.0 Sandbox API 2025-09-10).
 *
 * <p>All operations — sandbox lifecycle, command execution, file upload/download — run over the
 * AgentRun data-plane REST API. When the sandbox state declares {@code workspaceOnNas=true},
 * persistence is delegated entirely to the NAS mount and {@link #doPersistWorkspace()} is a no-op;
 * otherwise the sandbox falls back to a tar-via-HTTP archive (pack on the sandbox, download the
 * tarball, and reverse on hydrate).
 *
 * <p><b>HTTP REST 方案：</b>沙箱实例通过控制面 {@code POST /sandboxes} 显式创建（带确定性
 * sandboxId），命令执行走 {@code POST /sandboxes/{id}/processes/cmd}，文件上传/下载走
 * {@code /sandboxes/{id}/filesystem/upload|download}。不再依赖 MCP 协议，避免 MCP session 管理
 * 的复杂性。沙箱实例由 {@code sandboxIdleTimeoutSeconds} 控制 idle 回收；resume 时通过
 * {@code GET /sandboxes/{id}} 探活，若已被回收则重建。
 */
public class AgentRunSandbox extends AbstractBaseSandbox {

    private static final Logger log = LoggerFactory.getLogger(AgentRunSandbox.class);

    private static final int OUTPUT_TRUNCATE_BYTES = 512 * 1024;
    private static final int SANDBOX_READY_WAIT_SECONDS = 60;
    private static final String WS_TAR_PATH = "/tmp/agentscope-ws.tar";
    // createAndReady 的最大“创建+探活”尝试次数（首次 + 探活失败后再重建一次）
    private static final int CREATE_MAX_ATTEMPTS = 2;
    // 重建后验证 exec 通道是否真正可用的探活命令（无副作用，任意镜像均可用）
    private static final String PROBE_COMMAND = "echo __agentscope_sandbox_probe__";
    // 探活命令的预期输出内容
    private static final String PROBE_EXPECTED = "__agentscope_sandbox_probe__";

    private final AgentRunSandboxState arState;
    private final AgentRunSandboxClientOptions options;
    private final AgentRunDataPlaneHttp http;

    public AgentRunSandbox(
            AgentRunSandboxState state,
            AgentRunSandboxClientOptions options,
            AgentRunDataPlaneHttp http) {
        super(state);
        this.arState = state;
        this.options = options;
        this.http = http;
    }

    @Override
    public void start() throws Exception {
        log.info(
                "[sandbox-diag] start ENTER: sandboxId={}, sessionId={}, workspaceRootReady={},"
                        + " workspaceOnNas={}, projectionHash={}",
                arState.getSandboxId(),
                arState.getSessionId(),
                arState.isWorkspaceRootReady(),
                arState.isWorkspaceOnNas(),
                arState.getWorkspaceProjectionHash());
        if (WorkspaceMountSupport.hasBindMounts(arState.getWorkspaceSpec())) {
            log.warn(
                    "[sandbox-agentrun] WorkspaceSpec contains bind_mount entries; "
                            + "AgentRun does not apply host bind mounts — paths are not mounted.");
        }
        doStart();
    }

    private void doStart() throws Exception {
        // HTTP REST 方案：显式创建/探活沙箱实例，再走 4-Branch 工作区初始化。
        log.info(
                "[sandbox-diag] doStart step1 ensureSandboxInstance: sandboxId={}",
                arState.getSandboxId());
        ensureSandboxInstance();
        log.info("[sandbox-diag] doStart step2 super.start: sandboxId={}", arState.getSandboxId());
        super.start();
        log.info("[sandbox-diag] doStart DONE: sandboxId={}", arState.getSandboxId());
    }

    /**
     * 确保沙箱实例处于可用状态。统一处理三种场景：
     * <ol>
     *   <li>全新创建：getSandbox 返回 404 → createSandbox + waitUntilReady</li>
     *   <li>resume 存活实例：getSandbox 返回 READY/RUNNING → 复用</li>
     *   <li>resume 已回收实例：getSandbox 返回 TERMINATED/FAILED → delete + create + wait</li>
     * </ol>
     *
     * <p>沙箱实例由 {@code sandboxIdleTimeoutSeconds} 控制 idle 回收。resume 时持久化的
     * sandboxId 对应的实例可能已被回收（404）或已终止，此时需要重建。重建后若工作区在
     * NAS 挂载上（workspaceOnNas=true），4-Branch 走 Branch A 自动恢复；否则走 Branch D
     * 重新初始化。
     */
    private void ensureSandboxInstance() throws Exception {
        String sandboxId = arState.getSandboxId();
        if (sandboxId == null || sandboxId.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    "AgentRun sandboxId is required (set on AgentRunSandboxState)");
        }

        com.fasterxml.jackson.databind.JsonNode existing = http.getSandboxOrNull(sandboxId);
        if (existing == null) {
            // 场景 1：实例不存在（404），全新创建
            log.info(
                    "[sandbox-diag] ensureSandboxInstance: CREATE (not found) sandboxId={}",
                    sandboxId);
            createAndReady(sandboxId);
            return;
        }

        String status = AgentRunDataPlaneHttp.readStatus(existing);
        log.info(
                "[sandbox-diag] ensureSandboxInstance: EXISTS sandboxId={}, status={}",
                sandboxId,
                status);
        if (status != null) {
            String upper = status.toUpperCase();
            if (upper.contains("READY") || upper.contains("RUNNING")) {
                // 场景 2：实例存活，复用
                return;
            }
            if (upper.contains("FAILED") || upper.contains("TERMINATED")) {
                // 场景 3：实例已终止，删除后重建
                log.info(
                        "[sandbox-diag] ensureSandboxInstance: RECREATE (terminal) sandboxId={},"
                                + " status={}",
                        sandboxId,
                        status);
                try {
                    http.deleteSandbox(sandboxId);
                } catch (Exception e) {
                    log.warn(
                            "[sandbox-agentrun] delete before recreate failed (ignoring): {}",
                            e.getMessage());
                }
                // 重建后工作区丢失，强制走 Branch B/D 重新初始化
                arState.setWorkspaceRootReady(false);
                arState.setWorkspaceProjectionHash(null);
                createAndReady(sandboxId);
                return;
            }
        }
        // 状态未知，保守起见尝试复用（若不可用后续 exec 会 404 触发恢复）
        log.warn(
                "[sandbox-agentrun] ensureSandboxInstance: unknown status '{}', assuming reusable",
                status);
    }

    private void createAndReady(String sandboxId) throws Exception {
        Exception lastProbeFailure = null;
        for (int attempt = 1; attempt <= CREATE_MAX_ATTEMPTS; attempt++) {
            createOnce(sandboxId);
            // 探活：实例状态 READY 不代表命令通道可用（sess-dec89eb5 事故：重建
            // “成功”后所有 execute 返回 exitCode=-1 无输出，模型空转 11 轮）。
            // 探活失败则删除重建，最多 CREATE_MAX_ATTEMPTS 次。
            Exception probeError = probeExecOrNull();
            if (probeError == null) {
                if (attempt > 1) {
                    log.info(
                            "[sandbox-diag] createAndReady: exec probe OK after {} attempts",
                            attempt);
                }
                return;
            }
            lastProbeFailure = probeError;
            log.warn(
                    "[sandbox-diag] createAndReady: exec probe FAILED (attempt {}/{}), will"
                            + " recreate. error={}",
                    attempt,
                    CREATE_MAX_ATTEMPTS,
                    probeError.getMessage());
            // 重建前清理“假活”实例，避免残留
            try {
                http.deleteSandbox(arState.getSandboxId());
            } catch (Exception delErr) {
                log.warn(
                        "[sandbox-agentrun] delete unhealthy instance failed (ignoring): {}",
                        delErr.getMessage());
            }
        }
        throw new SandboxException.SandboxRuntimeException(
                "Sandbox exec channel unhealthy after "
                        + CREATE_MAX_ATTEMPTS
                        + " recreate attempts: "
                        + (lastProbeFailure != null ? lastProbeFailure.getMessage() : "unknown"));
    }

    /** 单次创建实例并等待状态 READY（不含 exec 通道探活）。 */
    private void createOnce(String sandboxId) throws Exception {
        com.fasterxml.jackson.databind.JsonNode resp = http.createSandbox(sandboxId);
        // 若 API 返回了不同的 sandboxId，更新 state（通常返回值与请求一致）
        if (resp != null && resp.has("sandboxId")) {
            String respId = resp.get("sandboxId").asText();
            if (respId != null && !respId.isBlank() && !respId.equals(sandboxId)) {
                log.info(
                        "[sandbox-agentrun] createSandbox returned different sandboxId: {} → {}",
                        sandboxId,
                        respId);
                arState.setSandboxId(respId);
            }
        }
        http.waitUntilReady(arState.getSandboxId(), SANDBOX_READY_WAIT_SECONDS);
    }

    /** 探测 exec 通道是否真正可用：可用返回 null，否则返回失败异常。 */
    private Exception probeExecOrNull() {
        try {
            ExecResult r = http.exec(arState.getSandboxId(), PROBE_COMMAND, null, 10);
            String out =
                    (r.stdout() != null ? r.stdout() : "")
                            + (r.stderr() != null ? r.stderr() : "");
            if (r.exitCode() == 0 && out.contains(PROBE_EXPECTED)) {
                return null;
            }
            return new SandboxException.ExecException(r.exitCode(), r.stdout(), r.stderr());
        } catch (Exception e) {
            return e;
        }
    }

    @Override
    public void stop() throws Exception {
        if (arState.isWorkspaceOnNas()) {
            try {
                http.exec(arState.getSandboxId(), "sync", null, 5);
            } catch (Exception e) {
                log.warn("[sandbox-agentrun] sync before stop failed: {}", e.getMessage());
            }
        }
        super.stop();
    }

    @Override
    public void shutdown() throws Exception {
        // The sandbox instance survives across acquire/release cycles: AgentRun reclaims it via
        // the configured idle timeout (sandboxIdleTimeoutSeconds), not on shutdown. Real teardown
        // is done via destroyInstance(), called only from AgentRunSandboxClient#delete().
    }

    /**
     * Destroys the backend sandbox instance via an HTTP delete. Called only from {@link
     * AgentRunSandboxClient#delete} for explicit teardown — {@link #shutdown()} is intentionally a
     * no-op so instances live across turns.
     */
    void destroyInstance() throws Exception {
        if (!arState.isSandboxOwned()) {
            return;
        }
        String id = arState.getSandboxId();
        if (id != null && !id.isBlank()) {
            http.deleteSandbox(id);
        }
    }

    @Override
    protected boolean probeWorkspaceRootForPreservedResume() {
        if (arState.isWorkspaceOnNas()) {
            // Files live on a persistent mount — the directory is durable across sandbox
            // recreations, so we treat it as preserved without probing.
            return true;
        }
        return super.probeWorkspaceRootForPreservedResume();
    }

    @Override
    protected ExecResult doExec(RuntimeContext runtimeContext, String command, int timeoutSeconds)
            throws Exception {
        String sandboxId = arState.getSandboxId();
        try {
            return execOnce(sandboxId, command, timeoutSeconds);
        } catch (Exception e) {
            // 实例可能被 idle-timeout 回收（HTTP 404），重建后重试一次
            if (AgentRunDataPlaneHttp.isNotFound(e)) {
                log.warn(
                        "[sandbox-agentrun] doExec: sandbox not found (404), recreating instance"
                                + " sandboxId={}",
                        sandboxId);
                arState.setWorkspaceRootReady(false);
                arState.setWorkspaceProjectionHash(null);
                ensureSandboxInstance();
                return execOnce(arState.getSandboxId(), command, timeoutSeconds);
            }
            // “假活”实例：状态仍是 READY 但内部命令通道已死（HTTP 200 + exitCode=-1 +
            // 无输出）。此时 ensureSandboxInstance 会看到 READY 而直接复用，必须强制
            // delete + 重建（含探活）后重试一次。sess-dec89eb5 事故：该失败曾被当作
            // 普通命令失败直接抛出，从此再也不会触发第二次重建，模型重试 11 轮烧光
            // 迭代预算。
            if (isUnhealthyExec(e)) {
                log.warn(
                        "[sandbox-agentrun] doExec: unhealthy exec channel (exitCode=-1, empty"
                                + " output), force recreating instance sandboxId={}",
                        sandboxId);
                arState.setWorkspaceRootReady(false);
                arState.setWorkspaceProjectionHash(null);
                try {
                    http.deleteSandbox(sandboxId);
                } catch (Exception delErr) {
                    log.warn(
                            "[sandbox-agentrun] delete before force recreate failed (ignoring):"
                                    + " {}",
                            delErr.getMessage());
                }
                createAndReady(sandboxId);
                return execOnce(arState.getSandboxId(), command, timeoutSeconds);
            }
            throw e;
        }
    }

    /**
     * 判定“假活”实例的 exec 失败：HTTP 200 但 exitCode=-1 且 stdout/stderr 均为空。普通
     * 命令失败会携带退出码与输出，只有命令通道本身死亡才会表现为 -1 无输出。
     */
    private static boolean isUnhealthyExec(Exception e) {
        if (!(e instanceof SandboxException.ExecException exec)) {
            return false;
        }
        if (exec.getExitCode() != -1) {
            return false;
        }
        String out = exec.getStdout();
        String err = exec.getStderr();
        return (out == null || out.isBlank()) && (err == null || err.isBlank());
    }

    private ExecResult execOnce(String sandboxId, String command, int timeoutSeconds)
            throws Exception {
        ExecResult r = http.exec(sandboxId, command, relativeOrAbsoluteCwd(), timeoutSeconds);
        String out = r.stdout() != null ? r.stdout() : "";
        boolean truncated = out.length() >= OUTPUT_TRUNCATE_BYTES;
        if (truncated) {
            out = out.substring(0, OUTPUT_TRUNCATE_BYTES);
        }
        ExecResult result = new ExecResult(r.exitCode(), out, r.stderr(), truncated);
        if (!result.ok()) {
            throw new SandboxException.ExecException(r.exitCode(), out, r.stderr());
        }
        return result;
    }

    @Override
    protected InputStream doPersistWorkspace() throws Exception {
        if (arState.isWorkspaceOnNas()) {
            // Persistence is handled by the NAS/OSS mount — nothing to archive.
            return InputStream.nullInputStream();
        }
        String root = arState.getWorkspaceRoot();
        String sandboxId = arState.getSandboxId();
        // 在沙箱内打包 tar，再通过 HTTP 下载
        String packCmd =
                "tar -cf " + shellSingleQuote(WS_TAR_PATH) + " -C " + shellSingleQuote(root) + " .";
        ExecResult pack = http.exec(sandboxId, packCmd, null, 30);
        if (pack.exitCode() != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "AgentRun tar failed (exit=" + pack.exitCode() + "): " + pack.stderr());
        }
        byte[] tarBytes = http.downloadFile(sandboxId, WS_TAR_PATH);
        // 清理临时文件
        try {
            http.exec(sandboxId, "rm -f " + shellSingleQuote(WS_TAR_PATH), null, 10);
        } catch (Exception e) {
            log.debug("[sandbox-agentrun] cleanup of {} failed: {}", WS_TAR_PATH, e.getMessage());
        }
        return new ByteArrayInputStream(tarBytes);
    }

    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        String root = arState.getWorkspaceRoot();
        String sandboxId = arState.getSandboxId();
        byte[] all = archive.readAllBytes();
        log.info("[sandbox-projection] doHydrateWorkspace: root={}, tarBytes={}", root, all.length);
        if (all.length == 0) {
            log.info("[sandbox-projection] doHydrateWorkspace: empty archive, skipping");
            return;
        }
        // 通过 /filesystem/upload 上传 tar 到 /tmp，再在沙箱内解压
        http.uploadFile(sandboxId, WS_TAR_PATH, all);
        String extractCmd =
                "tar -xf " + shellSingleQuote(WS_TAR_PATH) + " -C " + shellSingleQuote(root);
        ExecResult extract = http.exec(sandboxId, extractCmd, null, 30);
        if (extract.exitCode() != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "AgentRun tar extract failed: " + extract.stderr());
        }
        // 清理临时文件
        try {
            http.exec(sandboxId, "rm -f " + shellSingleQuote(WS_TAR_PATH), null, 10);
        } catch (Exception e) {
            log.debug("[sandbox-agentrun] cleanup of {} failed: {}", WS_TAR_PATH, e.getMessage());
        }
    }

    @Override
    protected void doSetupWorkspace() throws Exception {
        String root = arState.getWorkspaceRoot();
        log.info("[sandbox-projection] doSetupWorkspace: mkdir -p {}", root);
        ExecResult res =
                http.exec(arState.getSandboxId(), "mkdir -p " + shellSingleQuote(root), null, 30);
        if (res.exitCode() != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "Failed to create workspace directory "
                            + root
                            + " (exitCode="
                            + res.exitCode()
                            + ", stderr="
                            + res.stderr()
                            + ")."
                            + " The sandbox user may lack write permission on the parent."
                            + " Verify workspaceRoot is under a writable path (e.g. /home/user/).");
        }
    }

    @Override
    protected void doDestroyWorkspace() throws Exception {
        if (arState.isWorkspaceOnNas()) {
            // Do not destroy NAS-backed workspaces; the mount is shared/persistent.
            return;
        }
        try {
            http.exec(
                    arState.getSandboxId(),
                    "rm -rf " + shellSingleQuote(arState.getWorkspaceRoot()),
                    null,
                    30);
        } catch (Exception e) {
            // best-effort
        }
    }

    @Override
    protected String getWorkspaceRoot() {
        return arState.getWorkspaceRoot();
    }

    /**
     * Uploads a file via the dedicated {@code POST /filesystem/upload} endpoint instead of the
     * default exec-based base64 approach. Supports binary content and is more efficient.
     */
    @Override
    public void uploadFile(String path, byte[] content) throws Exception {
        http.uploadFile(arState.getSandboxId(), path, content);
    }

    /**
     * Downloads a file via the dedicated {@code GET /filesystem/download} endpoint instead of the
     * default exec-based base64 approach. Supports binary content and is more efficient.
     */
    @Override
    public byte[] downloadFile(String path) throws Exception {
        return http.downloadFile(arState.getSandboxId(), path);
    }

    private String relativeOrAbsoluteCwd() {
        String root = arState.getWorkspaceRoot();
        return root != null && !root.isBlank() ? root : null;
    }

    private static String shellSingleQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private static String jsonLiteral(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '\'') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else {
                sb.append(c);
            }
        }
        sb.append('\'');
        return sb.toString();
    }
}
