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
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
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
