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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Execution channel for an AgentRun sandbox built on the AgentScope MCP client.
 *
 * <p>Reuses {@link McpClientBuilder#streamableHttpTransport(String)} with the AgentRun API-key
 * header, and exposes the three tool names that an AgentRun sandbox template enables for
 * AgentScope: {@code process_exec_cmd}, {@code read_file}, {@code write_file}.
 */
final class AgentRunMcpChannel implements AutoCloseable {

    // 诊断日志：MCP 层连接与 exec 失败排查
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AgentRunMcpChannel.class);

    /** MCP tool name for shell-style command execution. */
    static final String TOOL_EXEC = "process_exec_cmd";

    /** MCP tool name for reading a file from the sandbox filesystem. */
    static final String TOOL_READ_FILE = "read_file";

    /** MCP tool name for writing a file to the sandbox filesystem. */
    static final String TOOL_WRITE_FILE = "write_file";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentRunSandboxClientOptions opt;
    private final String url;
    private volatile McpClientWrapper client;

    /**
     * 重连锁：保证同一 channel 上并发检测到 session 丢失时，只有一个线程执行 close+connect
     * 重建，其余线程等待后在已重建的新 session 上重试。避免多线程同时 close/connect 导致
     * 重复 initialize 或 client 引用错乱。
     */
    private final Object reconnectLock = new Object();

    AgentRunMcpChannel(AgentRunSandboxClientOptions opt) {
        this.opt = Objects.requireNonNull(opt, "opt");
        this.url = resolveUrl(opt);
    }

    /**
     * 连接 MCP 客户端。幂等——重复调用为空操作。
     *
     * <p>注意：此方法仅判断 {@code client != null} 就直接复用，不会探测服务端 session 是否仍然存活。
     * AgentRun 服务端会在 idle-timeout 后回收 MCP session，但客户端对象不会感知，因此跨轮次
     * 复用时可能命中已过期的 session。真正的过期恢复由 {@link #callWithReconnect} 在每次
     * callTool 失败时按 {@link #isSessionLost(Throwable)} 检测并重建连接。
     */
    void connect() {
        if (client != null) {
            // 诊断：MCP channel 已连接，复用现有 session（channelCache 按 sandboxId 复用的体现）
            log.info(
                    "[sandbox-diag] mcp.connect REUSE: already connected, url={}, channel={}",
                    url,
                    Integer.toHexString(System.identityHashCode(this)));
            return;
        }
        // 诊断：MCP channel 首次连接，建立新 MCP session（注意：URL 是 template 级，不含 sandboxId）
        log.info(
                "[sandbox-diag] mcp.connect NEW: url={}, channel={}",
                url,
                Integer.toHexString(System.identityHashCode(this)));
        McpClientWrapper c =
                McpClientBuilder.create("agentrun-" + opt.getTemplateName())
                        .streamableHttpTransport(url)
                        .header("X-API-Key", requireApiKey())
                        .header("X-Acs-Parent-Id", nullToEmpty(opt.getAccountId()))
                        .timeout(Duration.ofSeconds(Math.max(60, opt.getReadTimeoutSeconds())))
                        .protocolVersions("2024-11-05", "2025-03-26")
                        .buildSync();
        c.initialize().block();
        this.client = c;
        log.info(
                "[sandbox-diag] mcp.connect NEW OK: url={}, channel={}",
                url,
                Integer.toHexString(System.identityHashCode(this)));
    }

    /**
     * 执行一次 MCP callTool 调用，若检测到 session 丢失（过期/回收/404）则关闭旧连接、
     * 重新握手并重试一次。
     *
     * <p>懒创建沙箱后，两次沙箱调用之间可能间隔很久（多轮纯文本对话），AgentRun 服务端的
     * MCP session 会被 idle-timeout 回收。此时 {@link #connect()} 的 REUSE 分支仍认为已连接，
     * 首次 callTool 会返回 SessionExpired。此方法统一在 exec/readFile/writeFile 三条路径上
     * 捕获 session 丢失异常，断开旧 channel 并重建新 session 后重试，避免把过期错误抛给上层。
     *
     * <p>session 丢失有两种表现形式：
     * <ul>
     *   <li>SDK 抛异常（HTTP 层 SessionExpired）——通过 {@link #isSessionLost(Throwable)} 检测；</li>
     *   <li>MCP 返回 {@code isError()==true} 的结果（结果文本含 "SessionExpired"/"Sandbox not found"）
     *       ——通过 {@link #resultIndicatesSessionLost(McpSchema.CallToolResult)} 检测。</li>
     * </ul>
     *
     * @param toolName MCP 工具名
     * @param args     调用参数
     * @param timeout  单次 callTool 超时
     * @return MCP 调用结果
     */
    private McpSchema.CallToolResult callWithReconnect(
            String toolName, Map<String, Object> args, Duration timeout) {
        ensureConnected();
        try {
            McpSchema.CallToolResult result = doCallTool(toolName, args, timeout);
            // 结果层 session 丢失（isError=true 且文本含 session 失效特征）也需要重连重试
            if (resultIndicatesSessionLost(result)) {
                return reconnectAndRetry(toolName, args, timeout, extractText(result));
            }
            return result;
        } catch (Exception firstErr) {
            if (!isSessionLost(firstErr)) {
                // 非 session 丢失的异常直接抛出，不重试
                throw firstErr;
            }
            return reconnectAndRetry(toolName, args, timeout, firstErr.getMessage());
        }
    }

    /**
     * 关闭旧 channel、重建 session 并重试一次 callTool。
     *
     * <p>使用 {@link #reconnectLock} 保证并发安全：多个线程同时检测到 session 丢失时，
     * 只有持锁线程执行 close+connect 重建；其余线程排队拿锁后，{@code connect()} 发现
     * client 已被前一个线程重建，直接复用新 session，不会重复 initialize。重试的 callTool
     * 在锁外执行，避免持锁期间因命令执行耗时阻塞其他线程。
     */
    private McpSchema.CallToolResult reconnectAndRetry(
            String toolName, Map<String, Object> args, Duration timeout, String reason) {
        log.warn(
                "[sandbox-diag] mcp.call session lost, reconnecting: tool={}, channel={},"
                        + " reason={}",
                toolName,
                Integer.toHexString(System.identityHashCode(this)),
                reason);
        synchronized (reconnectLock) {
            // 关闭旧 channel（其 session 已失效），强制 connect() 重新 initialize 建立新 session。
            // 若已有其他线程持锁完成了重建，close() 是空操作（client==null），connect() 复用新 session。
            close();
            connect();
        }
        // 重试在锁外执行，避免持锁期间阻塞过久（callTool 可能耗时较长）
        return doCallTool(toolName, args, timeout);
    }

    /**
     * 判断 MCP 返回的 error 结果是否表明 session 已丢失（过期/沙箱回收/404）。
     * 与 {@link #isSessionLost(Throwable)} 的文本匹配保持一致，只是数据来源从异常 message
     * 换成了结果文本。
     */
    private boolean resultIndicatesSessionLost(McpSchema.CallToolResult result) {
        if (result == null || !Boolean.TRUE.equals(result.isError())) {
            return false;
        }
        String text = extractText(result);
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("session not found")
                || lower.contains("sessionexpired")
                || lower.contains("session expired")
                || lower.contains("is expired")
                || lower.contains("sandbox not found")
                || lower.contains("http 404")
                || lower.contains("err_not_found")
                || lower.contains("not_found");
    }

    /** 真正执行 callTool 并阻塞等待结果，集中处理超时与异常包装。 */
    private McpSchema.CallToolResult doCallTool(
            String toolName, Map<String, Object> args, Duration timeout) {
        McpSchema.CallToolResult result;
        try {
            result = client.callTool(toolName, args).block(timeout);
        } catch (Exception e) {
            // 诊断：MCP callTool 抛异常（如 session 失效），记录工具与异常，定位 MCP 层失效
            log.warn(
                    "[sandbox-diag] mcp.callTool ERROR: tool={}, channel={}, error={}",
                    toolName,
                    Integer.toHexString(System.identityHashCode(this)),
                    e.getMessage());
            // 保留原始异常作为 cause：isSessionLost 会沿 cause 链查找
            // McpTransportSessionNotFoundException 及 message 子串，丢弃 cause 会使
            // 基于 instanceof 的检测失效（即便 SDK 后续改抛类型化异常也认不出 session 丢失）。
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.EXEC_TIMEOUT,
                    "AgentRun MCP callTool failed: " + e.getMessage(),
                    e);
        }
        return result;
    }

    /** Result of a shell command executed in the sandbox. */
    static final class ExecResult {
        final int exitCode;
        final String stdout;
        final String stderr;

        ExecResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
        }
    }

    /** Runs {@code command} via the AgentRun {@code process_exec_cmd} MCP tool. */
    ExecResult exec(String command, String cwd, int timeoutSeconds) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("command", command);
        if (cwd != null && !cwd.isBlank()) {
            args.put("cwd", cwd);
        }
        args.put("timeout", Math.max(1, timeoutSeconds));
        McpSchema.CallToolResult result =
                callWithReconnect(
                        TOOL_EXEC, args, Duration.ofSeconds(Math.max(5, timeoutSeconds) + 30L));
        if (result == null) {
            log.warn(
                    "[sandbox-diag] mcp.exec NULL: channel={}, cmd={}, cwd={}",
                    Integer.toHexString(System.identityHashCode(this)),
                    command,
                    cwd);
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.EXEC_TIMEOUT, "AgentRun MCP exec returned null");
        }
        if (Boolean.TRUE.equals(result.isError())) {
            String msg = extractText(result);
            // 诊断：MCP 返回 error（如 "Sandbox not found" 404），记录命令与错误文本
            log.warn(
                    "[sandbox-diag] mcp.exec RESULT ERROR: channel={}, cmd={}, cwd={}, error={}",
                    Integer.toHexString(System.identityHashCode(this)),
                    command,
                    cwd,
                    msg);
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "AgentRun MCP " + TOOL_EXEC + " error: " + msg);
        }
        return parseExecPayload(extractText(result));
    }

    /** Reads a file via the AgentRun {@code read_file} MCP tool, returning its text content. */
    String readFile(String absolutePath) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", absolutePath);
        McpSchema.CallToolResult result =
                callWithReconnect(TOOL_READ_FILE, args, Duration.ofSeconds(120));
        if (result == null || Boolean.TRUE.equals(result.isError())) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "AgentRun MCP "
                            + TOOL_READ_FILE
                            + " failed for "
                            + absolutePath
                            + ": "
                            + (result == null ? "null" : extractText(result)));
        }
        return extractText(result);
    }

    /** Writes a file via the AgentRun {@code write_file} MCP tool. */
    void writeFile(String absolutePath, String content) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", absolutePath);
        args.put("content", content != null ? content : "");
        McpSchema.CallToolResult result =
                callWithReconnect(TOOL_WRITE_FILE, args, Duration.ofSeconds(120));
        if (result == null || Boolean.TRUE.equals(result.isError())) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "AgentRun MCP "
                            + TOOL_WRITE_FILE
                            + " failed for "
                            + absolutePath
                            + ": "
                            + (result == null ? "null" : extractText(result)));
        }
    }

    /** Returns the MCP endpoint URL this channel uses. */
    String getUrl() {
        return url;
    }

    @Override
    public void close() {
        McpClientWrapper c = client;
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignore) {
                // best-effort
            }
            client = null;
        }
    }

    private void ensureConnected() {
        if (client == null) {
            connect();
        }
    }

    /**
     * Returns {@code true} when an exception indicates the MCP session is no longer recognised by
     * the server (expired, invalidated, or terminated). Covers both the direct
     * {@link McpTransportSessionNotFoundException} and Reactor-wrapped variants whose causal
     * chain or message mentions session termination. Used by {@link AgentRunSandbox} to decide
     * whether to tear down and recreate the whole sandbox instance.
     *
     * <p>纯 MCP 方案下，沙箱实例被 idle-timeout 回收后，MCP exec 会返回 "Sandbox not found" /
     * HTTP 404 / ERR_NOT_FOUND。这些也需要识别为"session 丢失"，否则沙箱回收后无法触发重建，
     * 会导致 execute 反复失败。因此这里同时识别 sandbox 实例失效的错误特征。
     */
    static boolean isSessionLost(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof McpTransportSessionNotFoundException) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("session not found")
                        || lower.contains("does not recognize session")
                        || lower.contains("mcp session with server terminated")) {
                    return true;
                }
                // 沙箱实例被回收 / 不存在：MCP 返回 "Sandbox not found" / 404 / err_not_found
                if (lower.contains("sandbox not found")
                        || lower.contains("http 404")
                        || lower.contains("err_not_found")
                        || lower.contains("not_found")) {
                    return true;
                }
                // MCP session 过期：AgentRun 在 session 被 idle-timeout 回收后返回 JSON
                // {"Code":"SessionExpired","Message":"session <id> is expired"}。SDK 把整段
                // JSON 拼进 RuntimeException 的 message（非 McpTransportSessionNotFoundException），
                // 因此必须靠子串识别，否则 isSessionLost 返回 false，recreateSandbox 不触发，
                // 沙箱 start 直接失败。匹配 Code 值 "sessionexpired" 与 Message 措辞 "is expired"。
                if (lower.contains("sessionexpired")
                        || lower.contains("session expired")
                        || lower.contains("is expired")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    private String requireApiKey() {
        String key = opt.getApiKey();
        if (key == null || key.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    "AgentRun API key is required (set AgentRunSandboxClientOptions#setApiKey)");
        }
        return key;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String resolveUrl(AgentRunSandboxClientOptions opt) {
        String base = opt.getMcpServerUrl();
        if (base == null || base.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    "AgentRun MCP server URL is required (set #setMcpServerUrl)");
        }
        String endpoint = opt.getMcpEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return base;
        }
        // Treat base as either a host root or already including the endpoint.
        if (base.endsWith(endpoint) || base.contains(endpoint + "?")) {
            return base;
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String tail = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        return trimmed + tail;
    }

    private static String extractText(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content c : result.content()) {
            if (c instanceof McpSchema.TextContent t && t.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    /**
     * Parses an AgentRun exec MCP response. AgentRun may return any of:
     * <ul>
     *   <li>a bare JSON object {@code {"exitCode":0,"stdout":"...","stderr":"..."}};</li>
     *   <li>a markdown-wrapped response whose TextContent looks like
     *       {@code ### process_exec_cmd\n\n**Sandbox ID ...:**\n\n**Response:**\n{ ... }}
     *       — the real payload is the embedded JSON, with the command output under
     *       {@code result.stdout} / {@code result.exitCode} (or top-level
     *       {@code stdout}/{@code exitCode});</li>
     *   <li>a plain stdout/stderr string (no JSON at all).</li>
     * </ul>
     * We accept all three shapes. Critically, when the response is markdown-wrapped we must
     * extract the embedded JSON rather than treating the whole markdown blob as stdout —
     * otherwise every {@code glob}/{@code read}/{@code uploadFiles} call (which all rely on
     * {@code execute()} returning clean stdout) sees the markdown wrapper as its output and
     * corrupts downstream state files.
     */
    private static ExecResult parseExecPayload(String text) {
        if (text == null) {
            return new ExecResult(0, "", "");
        }
        String trimmed = text.strip();
        ExecResult parsed = tryParseJson(trimmed);
        if (parsed != null) {
            return parsed;
        }
        return new ExecResult(0, text, "");
    }

    /**
     * Attempts to interpret {@code text} as a (possibly markdown-wrapped) JSON exec payload.
     * Returns {@code null} when no usable JSON payload can be located, signalling the caller
     * to fall back to plain-text handling.
     */
    private static ExecResult tryParseJson(String text) {
        // First try the whole text as JSON (bare-object shape).
        ExecResult direct = parseJsonNode(text);
        if (direct != null) {
            return direct;
        }
        // Otherwise scan for the first balanced { ... } object — the markdown wrapper
        // emitted by the AgentRun MCP server embeds the real payload there.
        String json = extractFirstJsonObject(text);
        if (json != null && !json.equals(text)) {
            return parseJsonNode(json);
        }
        return null;
    }

    /** Parses {@code json} as an exec-result JSON node, supporting nested and flat shapes. */
    private static ExecResult parseJsonNode(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isObject()) {
                return null;
            }
            // Nested shape: { "result": { "exitCode":0, "stdout":"...", "stderr":"..." } }
            JsonNode result = node.path("result");
            JsonNode src = result.isObject() ? result : node;
            if (!src.has("exitCode")
                    && !src.has("exit_code")
                    && !src.has("stdout")
                    && !src.has("output")) {
                // Not an exec-result object (e.g. a metadata blob); don't pretend it is.
                return null;
            }
            int exit =
                    src.has("exitCode")
                            ? src.path("exitCode").asInt(0)
                            : src.path("exit_code").asInt(0);
            String stdout =
                    src.has("stdout")
                            ? src.path("stdout").asText("")
                            : src.path("output").asText("");
            String stderr = src.path("stderr").asText("");
            return new ExecResult(exit, stdout, stderr);
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * Locates the first balanced JSON object ({@code { ... }}) inside {@code text}, accounting
     * for string-literal braces. Returns the raw substring, or {@code null} when no balanced
     * object is found. Used to peel the AgentRun markdown wrapper off the embedded payload.
     */
    private static String extractFirstJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                if (depth > 0) {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        return text.substring(start, i + 1);
                    }
                }
            }
        }
        return null;
    }
}
