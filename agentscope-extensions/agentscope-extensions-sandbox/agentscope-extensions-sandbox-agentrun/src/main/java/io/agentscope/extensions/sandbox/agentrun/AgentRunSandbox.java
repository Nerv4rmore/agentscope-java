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
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link io.agentscope.harness.agent.sandbox.Sandbox} backed by an Alibaba Cloud AgentRun sandbox
 * (FC 3.0 Sandbox API 2025-09-10).
 *
 * <p>Execution runs over the AgentRun MCP server bundled with the sandbox template. When the
 * sandbox state declares {@code workspaceOnNas=true}, persistence is delegated entirely to the
 * NAS mount and {@link #doPersistWorkspace()} is a no-op; otherwise the sandbox falls back to a
 * tar-via-MCP archive identical in shape to Daytona's payload.
 *
 * <p><b>纯 MCP 方案：</b>沙箱实例由 MCP server 在 initialize 握手时自动创建，不再通过控制面
 * {@code createSandbox} 显式创建。这样只有一个沙箱实例（MCP 创建的），避免"控制面建一个 +
 * MCP 又建一个"的双沙箱问题。代价是沙箱实例不跨 JVM 重启复用（重启后 MCP 重新连接会创建新
 * 沙箱，workspace 走 Branch D 重新投射）。
 */
public class AgentRunSandbox extends AbstractBaseSandbox {

    private static final Logger log = LoggerFactory.getLogger(AgentRunSandbox.class);

    private static final int OUTPUT_TRUNCATE_BYTES = 512 * 1024;
    private static final int TAR_TIMEOUT_SECONDS = 300;
    private static final int B64_CHUNK = 4000;

    private final AgentRunSandboxState arState;
    private final AgentRunSandboxClientOptions options;
    private final AgentRunDataPlaneHttp http;
    // Non-final: start() may replace the channel when recreating the sandbox after an MCP
    // session-loss (the old channel's session is torn down and a fresh one established against
    // the newly created sandbox instance).
    private AgentRunMcpChannel mcp;
    // Guards against infinite recreate loops: a single start() call recreates the sandbox at
    // most once. If the recreated instance also fails, the error propagates to the caller.
    private boolean recreated;

    public AgentRunSandbox(
            AgentRunSandboxState state,
            AgentRunSandboxClientOptions options,
            AgentRunDataPlaneHttp http,
            AgentRunMcpChannel mcp) {
        super(state);
        this.arState = state;
        this.options = options;
        this.http = http;
        this.mcp = mcp;
    }

    @Override
    public void start() throws Exception {
        // 诊断：start 入口，记录 sandboxId / sessionId / workspaceRootReady / recreated，便于追踪复用与重建
        log.info(
                "[sandbox-diag] start ENTER: sandboxId={}, sessionId={}, workspaceRootReady={},"
                        + " workspaceOnNas={}, recreated={}, projectionHash={}",
                arState.getSandboxId(),
                arState.getSessionId(),
                arState.isWorkspaceRootReady(),
                arState.isWorkspaceOnNas(),
                recreated,
                arState.getWorkspaceProjectionHash());
        if (WorkspaceMountSupport.hasBindMounts(arState.getWorkspaceSpec())) {
            log.warn(
                    "[sandbox-agentrun] WorkspaceSpec contains bind_mount entries; "
                            + "AgentRun does not apply host bind mounts — paths are not mounted.");
        }
        try {
            doStart();
        } catch (Exception e) {
            // 诊断：start 失败，记录异常类型与 isSessionLost 判断结果，定位为何未触发重建
            log.warn(
                    "[sandbox-diag] start FAILED: sandboxId={}, errorType={}, msg={},"
                            + " isSessionLost={}, recreated={}",
                    arState.getSandboxId(),
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    AgentRunMcpChannel.isSessionLost(e),
                    recreated);
            if (!AgentRunMcpChannel.isSessionLost(e) || recreated) {
                throw e;
            }
            recreated = true;
            log.warn(
                    "[sandbox-agentrun] MCP session lost during start ({}); recreating sandbox"
                            + " instance '{}'",
                    e.getMessage(),
                    arState.getSandboxId());
            recreateSandbox();
            doStart();
        }
    }

    /**
     * 纯 MCP 方案下的重建：关闭旧 MCP channel（其 session 绑定的沙箱已失效），让下次
     * {@code connect()} 重新 initialize 建立新 session，由 MCP server 自动创建新沙箱实例。
     *
     * <p>不再调用 {@code http.deleteSandbox}：纯 MCP 方案下没有通过 createSandbox 显式创建
     * 的沙箱实例，确定性 sandboxId 只是 channelCache 的 key，删除它无意义。沙箱实例由
     * MCP server 创建和回收（idle timeout），重建只需断开旧 MCP session 即可。
     */
    private void recreateSandbox() throws Exception {
        // Close the stale MCP channel (its session is gone) so connect() builds a fresh one.
        try {
            mcp.close();
        } catch (Exception ignore) {
            // best-effort
        }
        // Force a full workspace re-init on the fresh instance.
        arState.setWorkspaceRootReady(false);
        arState.setWorkspaceProjectionHash(null);
    }

    private void doStart() throws Exception {
        // 纯 MCP 方案：不再调用 ensureSandbox/createSandbox。MCP server 在 initialize 握手时
        // 会自动创建沙箱实例，后续 mcp.exec 都落在该实例上。这样只有一个沙箱（MCP 创建的），
        // 不再出现 ensureSandbox 建一个、MCP 又建一个的"双沙箱"问题。
        log.info("[sandbox-diag] doStart step1 mcp.connect: sandboxId={}", arState.getSandboxId());
        mcp.connect();
        log.info("[sandbox-diag] doStart step2 super.start: sandboxId={}", arState.getSandboxId());
        super.start();
        log.info("[sandbox-diag] doStart DONE: sandboxId={}", arState.getSandboxId());
    }

    @Override
    public void stop() throws Exception {
        if (arState.isWorkspaceOnNas()) {
            try {
                mcp.exec("sync", null, 5);
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
        //
        // NOTE: do NOT close the MCP channel here either. The channel is cached per-sandboxId by
        // AgentRunSandboxClient and reused across acquire/release cycles so the MCP session
        // survives — closing it here would force a re-initialize on the next resume, which the
        // AgentRun MCP server rejects ("Session not found").
    }

    /**
     * Destroys the backend sandbox instance via an HTTP delete. Called only from {@link
     * AgentRunSandboxClient#delete} for explicit teardown — {@link #shutdown()} is intentionally a
     * no-op so instances live across turns.
     *
     * <p>纯 MCP 方案下，真实沙箱实例由 MCP server 创建（随机 id），持久化的派生 sandboxId 只是
     * channelCache 的 key，对应的 HTTP 沙箱可能不存在，{@code deleteSandbox} 会静默 404。
     * 真正的清理靠 {@link AgentRunSandboxClient#delete} 里关闭 MCP channel（断开 session），
     * 沙箱实例随后由 AgentRun idle-timeout 自然回收。
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
        AgentRunMcpChannel.ExecResult r =
                mcp.exec(command, relativeOrAbsoluteCwd(), timeoutSeconds);
        String out = r.stdout != null ? r.stdout : "";
        boolean truncated = out.length() >= OUTPUT_TRUNCATE_BYTES;
        if (truncated) {
            out = out.substring(0, OUTPUT_TRUNCATE_BYTES);
        }
        ExecResult result = new ExecResult(r.exitCode, out, r.stderr, truncated);
        if (!result.ok()) {
            throw new SandboxException.ExecException(r.exitCode, out, r.stderr);
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
        String cmd = "tar -cf - -C " + shellSingleQuote(root) + " . | base64 -w0";
        AgentRunMcpChannel.ExecResult r = mcp.exec(cmd, null, TAR_TIMEOUT_SECONDS);
        if (r.exitCode != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "AgentRun tar failed (exit=" + r.exitCode + "): " + r.stderr);
        }
        String b64 = (r.stdout != null ? r.stdout : "").replace("\n", "").replace("\r", "");
        byte[] raw = Base64.getDecoder().decode(b64);
        return new ByteArrayInputStream(raw);
    }

    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        String root = arState.getWorkspaceRoot();
        byte[] all = archive.readAllBytes();
        log.info("[sandbox-projection] doHydrateWorkspace: root={}, tarBytes={}", root, all.length);
        if (all.length == 0) {
            log.info("[sandbox-projection] doHydrateWorkspace: empty archive, skipping");
            return;
        }
        String b64 = Base64.getEncoder().encodeToString(all);
        mcp.exec("rm -f /tmp/agentscope-ws.b64", null, 30);
        for (int i = 0; i < b64.length(); i += B64_CHUNK) {
            String chunk = b64.substring(i, Math.min(b64.length(), i + B64_CHUNK));
            String py =
                    "import pathlib; pathlib.Path('/tmp/agentscope-ws.b64').open('a').write("
                            + jsonLiteral(chunk)
                            + ")";
            AgentRunMcpChannel.ExecResult chunkRes =
                    mcp.exec("python3 -c " + shellSingleQuote(py), null, 120);
            if (chunkRes.exitCode != 0) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                        "AgentRun chunk write failed: " + chunkRes.stderr);
            }
        }
        String pyFin =
                "import base64,pathlib,subprocess; d="
                        + jsonLiteral(root)
                        + "; raw=base64.standard_b64decode(pathlib.Path('/tmp/agentscope-ws.b64').read_text());"
                        + " subprocess.run(['tar','xf','-','-C',d],input=raw,check=True)";
        AgentRunMcpChannel.ExecResult finRes =
                mcp.exec("python3 -c " + shellSingleQuote(pyFin), null, TAR_TIMEOUT_SECONDS);
        if (finRes.exitCode != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "AgentRun tar extract failed: " + finRes.stderr);
        }
    }

    @Override
    protected void doSetupWorkspace() throws Exception {
        String root = arState.getWorkspaceRoot();
        log.info("[sandbox-projection] doSetupWorkspace: mkdir -p {}", root);
        AgentRunMcpChannel.ExecResult res =
                mcp.exec("mkdir -p " + shellSingleQuote(root), null, 30);
        if (res.exitCode != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "Failed to create workspace directory "
                            + root
                            + " (exitCode="
                            + res.exitCode
                            + ", stderr="
                            + res.stderr
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
            mcp.exec("rm -rf " + shellSingleQuote(arState.getWorkspaceRoot()), null, 30);
        } catch (Exception e) {
            // best-effort
        }
    }

    @Override
    protected String getWorkspaceRoot() {
        return arState.getWorkspaceRoot();
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
