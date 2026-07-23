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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Minimal data-plane HTTP client for the AgentRun sandbox API. */
final class AgentRunDataPlaneHttp {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final AgentRunSandboxClientOptions opt;

    AgentRunDataPlaneHttp(AgentRunSandboxClientOptions opt) {
        this.opt = Objects.requireNonNull(opt, "opt");
        OkHttpClient base = opt.getHttpClient();
        if (base != null) {
            this.http = base;
        } else {
            this.http =
                    new OkHttpClient.Builder()
                            .connectTimeout(opt.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                            .readTimeout(opt.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                            .build();
        }
    }

    /** Creates a sandbox with a deterministic id and returns the sandbox object as JSON. */
    JsonNode createSandbox(String sandboxId) throws IOException {
        ObjectNode body = json.createObjectNode();
        body.put("sandboxId", sandboxId);
        body.put("templateName", opt.getTemplateName());
        body.put("sandboxIdleTimeoutSeconds", opt.getSandboxIdleTimeoutSeconds());

        AgentRunNasMountConfig nas = opt.getNasConfig();
        if (nas != null
                && nas.getServerAddr() != null
                && !nas.getServerAddr().isBlank()
                && nas.getMountDir() != null
                && !nas.getMountDir().isBlank()) {
            ObjectNode nasNode = body.putObject("nasMountConfig");
            ArrayNode mounts = nasNode.putArray("mountPoints");
            ObjectNode mp = mounts.addObject();
            mp.put("serverAddr", nas.getServerAddr());
            mp.put("mountDir", nas.getMountDir());
            mp.put("remotePath", nas.getRemotePath());
            mp.put("enableTLS", nas.isEnableTLS());
        }

        List<AgentRunOssMountConfig> ossMounts = opt.getOssMountConfigs();
        if (ossMounts != null && !ossMounts.isEmpty()) {
            ObjectNode ossNode = body.putObject("ossMountConfig");
            ArrayNode mounts = ossNode.putArray("mountPoints");
            for (AgentRunOssMountConfig m : ossMounts) {
                if (m == null) {
                    continue;
                }
                ObjectNode mp = mounts.addObject();
                mp.put("bucketName", m.getBucketName());
                mp.put("bucketPath", m.getBucketPath());
                mp.put("endpoint", m.getEndpoint());
                mp.put("mountDir", m.getMountDir());
                mp.put("readOnly", m.isReadOnly());
            }
        }

        String url = opt.getResolvedDataPlaneBaseUrl() + "/sandboxes";
        return AgentRunRetry.withRetries(
                opt.getMaxRetries(), () -> unwrapData(postJson(url, body)));
    }

    JsonNode getSandbox(String sandboxId) throws IOException {
        String url = opt.getResolvedDataPlaneBaseUrl() + "/sandboxes/" + sandboxId;
        return AgentRunRetry.withRetries(opt.getMaxRetries(), () -> unwrapData(getJson(url)));
    }

    /**
     * Reads the lifecycle status text from a sandbox object returned by {@link #getSandbox}.
     *
     * <p>Returns {@code null} when no status can be located. Exposed so callers can decide whether
     * an existing instance is reusable (READY/RUNNING) or terminal (TERMINATED/FAILED) and must be
     * recreated, without relying on exception message parsing.
     */
    static String readStatus(JsonNode sandbox) {
        return textStatus(sandbox);
    }

    /**
     * Returns the sandbox object if it exists, or {@code null} when the data plane responds
     * HTTP 404 (instance not found — e.g. reclaimed by idle timeout).
     *
     * <p>All other errors propagate. Use this to probe whether a persisted sandboxId is still alive
     * before deciding to reuse or recreate.
     */
    JsonNode getSandboxOrNull(String sandboxId) throws IOException {
        try {
            return getSandbox(sandboxId);
        } catch (SandboxException e) {
            if (isNotFound(e)) {
                return null;
            }
            throw e;
        }
    }

    /** True when the given error is an HTTP 404 from the data plane (sandbox not found). */
    static boolean isNotFound(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null && msg.contains("HTTP 404")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * Executes a shell command in the sandbox via {@code POST /sandboxes/{id}/processes/cmd}.
     *
     * <p>The data plane gateway enforces a hard 30-second timeout regardless of the requested
     * {@code timeoutSeconds}. The response carries {@code exitCode}, {@code stdout} and
     * {@code stderr}.
     *
     * @param sandboxId target sandbox instance id
     * @param command shell command string
     * @param cwd working directory; omitted from the request body when {@code null}/blank
     * @param timeoutSeconds requested timeout (capped to 30 by the gateway)
     * @return exec result with exit code, stdout and stderr
     */
    ExecResult exec(String sandboxId, String command, String cwd, int timeoutSeconds)
            throws IOException {
        ObjectNode body = json.createObjectNode();
        body.put("command", command);
        if (cwd != null && !cwd.isBlank()) {
            body.put("cwd", cwd);
        }
        // Gateway hard-caps at 30s; send the smaller of requested/default to be explicit.
        body.put("timeout", Math.min(30, Math.max(1, timeoutSeconds)));

        String url = sandboxUrl(sandboxId) + "/processes/cmd";
        JsonNode resp =
                AgentRunRetry.withRetries(
                        opt.getMaxRetries(), () -> unwrapData(postJson(url, body)));
        return parseExecResult(resp);
    }

    /**
     * Uploads a file into the sandbox via {@code POST /sandboxes/{id}/filesystem/upload}
     * (multipart, max 100 MB). Parent directories are auto-created by the data plane.
     *
     * @param sandboxId target sandbox instance id
     * @param path absolute destination path inside the sandbox
     * @param content raw file bytes
     */
    void uploadFile(String sandboxId, String path, byte[] content) throws IOException {
        String url = sandboxUrl(sandboxId) + "/filesystem/upload";
        // AgentRun expects multipart/form-data with a "file" part (binary content) and an
        // optional "path" form field for the destination. Use the basename as the file part's
        // filename to avoid issues with absolute paths in the Content-Disposition header.
        String filename = path;
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            filename = path.substring(lastSlash + 1);
        }
        // OkHttp 5.x: use ByteString to wrap the raw bytes for the file part body.
        okhttp3.RequestBody filePart =
                okhttp3.RequestBody.create(
                        okio.ByteString.of(content), MediaType.get("application/octet-stream"));
        MultipartBody multipart =
                new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", filename, filePart)
                        .addFormDataPart("path", path)
                        .build();
        Request req = baseRequest().url(url).post(multipart).build();
        try (Response res = http.newCall(req).execute()) {
            String text = res.body() != null ? res.body().string() : "";
            if (!res.isSuccessful()) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                        "AgentRun upload failed: HTTP "
                                + res.code()
                                + " "
                                + res.message()
                                + ": "
                                + text);
            }
        }
    }

    /**
     * Downloads a file from the sandbox via {@code GET /sandboxes/{id}/filesystem/download?path=}.
     *
     * @param sandboxId target sandbox instance id
     * @param path absolute source path inside the sandbox
     * @return raw file bytes
     */
    byte[] downloadFile(String sandboxId, String path) throws IOException {
        String url =
                sandboxUrl(sandboxId)
                        + "/filesystem/download?path="
                        + java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8);
        Request req = baseRequest().url(url).get().build();
        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) {
                String text = res.body() != null ? res.body().string() : "";
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                        "AgentRun download failed: HTTP "
                                + res.code()
                                + " "
                                + res.message()
                                + ": "
                                + text);
            }
            return res.body() != null ? res.body().bytes() : new byte[0];
        }
    }

    /**
     * 解包 AgentRun 数据面响应的 {@code data} 外层包装。
     *
     * <p>AgentRun 数据面接口标准返回形如 {@code { "code":"SUCCESS", "data": { ... } }}，
     * 真正的沙箱对象挂在 {@code data} 下。这里把 {@code data} 取出返回；若响应没有
     * {@code data} 包装（直接返回沙箱对象），则原样返回，兼容两种形态。
     */
    private static JsonNode unwrapData(JsonNode response) {
        if (response == null) {
            return null;
        }
        JsonNode data = response.get("data");
        if (data != null && data.isObject()) {
            return data;
        }
        return response;
    }

    /**
     * Deletes the sandbox. Returns silently on HTTP 404 (already gone). All other non-2xx
     * responses raise.
     */
    void deleteSandbox(String sandboxId) throws IOException {
        String url = opt.getResolvedDataPlaneBaseUrl() + "/sandboxes/" + sandboxId;
        Request req = baseRequest().url(url).delete().build();
        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful() && res.code() != 404) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_STOP_ERROR,
                        "AgentRun delete failed: HTTP " + res.code() + " " + res.message());
            }
        }
    }

    /**
     * 轮询 {@link #getSandbox(String)}，直到沙箱进入 {@code READY} 状态或失败为止。
     */
    void waitUntilReady(String sandboxId, int maxWaitSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(maxWaitSeconds);
        String last = null;
        while (System.nanoTime() < deadline) {
            JsonNode s = getSandbox(sandboxId);
            String status = textStatus(s);
            last = status;
            if (status != null) {
                String upper = status.toUpperCase();
                if (upper.contains("READY") || upper.contains("RUNNING")) {
                    return;
                }
                if (upper.contains("FAILED") || upper.contains("TERMINATED")) {
                    throw new SandboxException.SandboxRuntimeException(
                            SandboxErrorCode.WORKSPACE_START_ERROR,
                            "AgentRun sandbox entered terminal state " + status + ": " + sandboxId);
                }
            }
            Thread.sleep(1500L);
        }
        throw new SandboxException.SandboxRuntimeException(
                SandboxErrorCode.WORKSPACE_START_ERROR,
                "AgentRun sandbox did not become READY in time (last status="
                        + last
                        + "): "
                        + sandboxId);
    }

    /**
     * 从沙箱 JSON 中解析状态字段。
     *
     * <p>AgentRun 数据面接口返回的响应体是包了一层的：
     * <pre>{@code
     * { "code": "SUCCESS", "requestId": "...", "data": { "sandboxId": "...", "status": "READY", ... } }
     * }</pre>
     * 因此这里依次尝试：根节点的 {@code status}/{@code state}，以及嵌套 {@code data} 节点下的
     * {@code status}/{@code state}。兼容直接返回沙箱对象（无 {@code data} 包装）和带包装两种形态。
     */
    private static String textStatus(JsonNode s) {
        if (s == null) {
            return null;
        }
        // 1) 直接挂在根节点上的状态字段（沙箱对象直接作为响应体时）
        String direct = readStatusField(s);
        if (direct != null) {
            return direct;
        }
        // 2) 包在 data 字段里的状态字段（AgentRun 数据面标准响应包装）
        JsonNode data = s.get("data");
        if (data != null) {
            return readStatusField(data);
        }
        return null;
    }

    /** 读取一个节点上的 {@code status} 或 {@code state} 文本字段，缺失或非文本时返回 {@code null}。 */
    private static String readStatusField(JsonNode node) {
        if (node == null) {
            return null;
        }
        JsonNode st = node.get("status");
        if (st != null && st.isTextual()) {
            return st.asText();
        }
        JsonNode state = node.get("state");
        if (state != null && state.isTextual()) {
            return state.asText();
        }
        return null;
    }

    private Request.Builder baseRequest() {
        Request.Builder b = new Request.Builder().addHeader("X-API-Key", requireApiKey());
        if (opt.getAccountId() != null && !opt.getAccountId().isBlank()) {
            b.addHeader("X-Acs-Parent-Id", opt.getAccountId());
        }
        return b;
    }

    /** Builds the {@code /sandboxes/{sandboxId}} URL prefix used by all per-instance endpoints. */
    private String sandboxUrl(String sandboxId) {
        return opt.getResolvedDataPlaneBaseUrl() + "/sandboxes/" + sandboxId;
    }

    /**
     * Parses an exec response into an {@link ExecResult}.
     *
     * <p>Accepts both nested {@code {result:{exitCode,stdout,stderr}}} and flat shapes, and the
     * field aliases {@code exit_code}/{@code output} (mirrors the old MCP payload parser).
     */
    private static ExecResult parseExecResult(JsonNode resp) {
        if (resp == null) {
            return new ExecResult(-1, "", "AgentRun exec returned null response", false);
        }
        JsonNode node = resp;
        JsonNode resultNode = resp.get("result");
        if (resultNode != null && resultNode.isObject()) {
            node = resultNode;
        }
        int exitCode = readInt(node, "exitCode", "exit_code", -1);
        String stdout = readText(node, "stdout", "output", "");
        String stderr = readText(node, "stderr", "");
        return new ExecResult(exitCode, stdout, stderr, false);
    }

    private static int readInt(JsonNode node, String primary, String fallback, int def) {
        JsonNode n = node.get(primary);
        if (n == null || !n.canConvertToInt()) {
            n = node.get(fallback);
        }
        return (n != null && n.canConvertToInt()) ? n.asInt() : def;
    }

    private static String readText(JsonNode node, String primary, String fallback, String def) {
        JsonNode n = node.get(primary);
        if (n == null || n.isNull()) {
            n = node.get(fallback);
        }
        if (n == null || n.isNull()) {
            return def;
        }
        return n.isTextual() ? n.asText() : n.toString();
    }

    private static String readText(JsonNode node, String primary, String def) {
        JsonNode n = node.get(primary);
        if (n == null || n.isNull()) {
            return def;
        }
        return n.isTextual() ? n.asText() : n.toString();
    }

    private String requireApiKey() {
        String key = opt.getApiKey();
        if (key == null || key.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    "AgentRun API key is required (set AgentRunSandboxClientOptions#setApiKey)");
        }
        return key;
    }

    private JsonNode postJson(String url, ObjectNode body) throws IOException {
        Request req =
                baseRequest().url(url).post(RequestBody.create(body.toString(), JSON)).build();
        try (Response res = http.newCall(req).execute()) {
            String text = res.body() != null ? res.body().string() : "";
            if (!res.isSuccessful()) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_START_ERROR,
                        "AgentRun HTTP " + res.code() + " " + res.message() + ": " + text);
            }
            if (text.isBlank()) {
                return json.createObjectNode();
            }
            return json.readTree(text);
        }
    }

    private JsonNode getJson(String url) throws IOException {
        Request req = baseRequest().url(url).get().build();
        try (Response res = http.newCall(req).execute()) {
            String text = res.body() != null ? res.body().string() : "";
            if (!res.isSuccessful()) {
                throw new SandboxException.SandboxRuntimeException(
                        SandboxErrorCode.WORKSPACE_START_ERROR,
                        "AgentRun HTTP " + res.code() + " " + res.message() + ": " + text);
            }
            return json.readTree(text);
        }
    }
}
