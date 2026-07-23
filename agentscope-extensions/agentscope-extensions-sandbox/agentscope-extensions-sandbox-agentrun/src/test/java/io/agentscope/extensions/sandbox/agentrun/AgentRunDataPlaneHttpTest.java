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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.harness.agent.sandbox.ExecResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentRunDataPlaneHttpTest {

    private MockWebServer mockServer;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    private AgentRunDataPlaneHttp httpWithMock() {
        String baseUrl = mockServer.url("/").toString();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        AgentRunSandboxClientOptions opt =
                new AgentRunSandboxClientOptions()
                        .setApiKey("test-key")
                        .setAccountId("1234567890")
                        .setRegion("cn-hangzhou")
                        .setTemplateName("agentscope-default")
                        .setDataPlaneBaseUrl(baseUrl)
                        .setHttpClient(new OkHttpClient());
        return new AgentRunDataPlaneHttp(opt);
    }

    @Test
    void usesSandboxPathsWithoutVersionPrefix() throws Exception {
        AgentRunDataPlaneHttp http = httpWithMock();

        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"sb-1\"}"));
        JsonNode created = http.createSandbox("sb-1");
        assertNotNull(created);
        assertEquals("sb-1", created.get("id").asText());
        RecordedRequest createReq = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(createReq);
        assertEquals("POST", createReq.getMethod());
        assertEquals("/sandboxes", createReq.getPath());
        assertEquals("test-key", createReq.getHeader("X-API-Key"));
        assertEquals("1234567890", createReq.getHeader("X-Acs-Parent-Id"));
        assertTrue(createReq.getBody().readUtf8().contains("\"sandboxId\":\"sb-1\""));

        mockServer.enqueue(
                new MockResponse().setResponseCode(200).setBody("{\"status\":\"READY\"}"));
        JsonNode fetched = http.getSandbox("sb-1");
        assertNotNull(fetched);
        assertEquals("READY", fetched.get("status").asText());
        RecordedRequest getReq = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(getReq);
        assertEquals("GET", getReq.getMethod());
        assertEquals("/sandboxes/sb-1", getReq.getPath());

        mockServer.enqueue(new MockResponse().setResponseCode(204));
        http.deleteSandbox("sb-1");
        RecordedRequest deleteReq = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(deleteReq);
        assertEquals("DELETE", deleteReq.getMethod());
        assertEquals("/sandboxes/sb-1", deleteReq.getPath());
    }

    @Test
    void execPostsToProcessesCmdAndParsesResult() throws Exception {
        AgentRunDataPlaneHttp http = httpWithMock();

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"exitCode\":0,\"stdout\":\"hello world\",\"stderr\":\"\"}"));

        ExecResult result = http.exec("sb-1", "echo hello world", "/home/user", 30);

        assertEquals(0, result.exitCode());
        assertEquals("hello world", result.stdout());
        assertEquals("", result.stderr());

        RecordedRequest req = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(req);
        assertEquals("POST", req.getMethod());
        assertEquals("/sandboxes/sb-1/processes/cmd", req.getPath());
        String body = req.getBody().readUtf8();
        assertTrue(body.contains("\"command\":\"echo hello world\""));
        assertTrue(body.contains("\"cwd\":\"/home/user\""));
        assertTrue(body.contains("\"timeout\":30"));
    }

    @Test
    void execAcceptsNestedResultShapeAndAliases() throws Exception {
        AgentRunDataPlaneHttp http = httpWithMock();

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(
                                "{\"result\":{\"exit_code\":1,\"output\":\"err\",\"stderr\":\"fail\"}}"));

        ExecResult result = http.exec("sb-1", "false", null, 10);

        assertEquals(1, result.exitCode());
        assertEquals("err", result.stdout());
        assertEquals("fail", result.stderr());

        RecordedRequest req = mockServer.takeRequest(1, TimeUnit.SECONDS);
        // cwd omitted when null
        String body = req.getBody().readUtf8();
        assertTrue(!body.contains("\"cwd\""));
    }

    @Test
    void uploadFilePostsMultipartToUploadEndpoint() throws Exception {
        AgentRunDataPlaneHttp http = httpWithMock();

        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        byte[] content = "file-content".getBytes(StandardCharsets.UTF_8);
        http.uploadFile("sb-1", "/home/user/test.txt", content);

        RecordedRequest req = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(req);
        assertEquals("POST", req.getMethod());
        assertEquals("/sandboxes/sb-1/filesystem/upload", req.getPath());
        String contentType = req.getHeader("Content-Type");
        assertNotNull(contentType);
        assertTrue(contentType.startsWith("multipart/form-data"));
        // The multipart body should carry the destination path as a form field and the
        // file content as the "file" part body. OkHttp 5.x may use chunked transfer encoding,
        // so we read the decoded body buffer and check for key markers.
        okio.Buffer body = req.getBody();
        assertNotNull(body);
        assertTrue(body.size() > content.length, "upload body should be larger than raw content");
        String bodyStr = body.readUtf8();
        assertTrue(
                bodyStr.contains("/home/user/test.txt"),
                "body should contain path field: " + bodyStr);
    }

    @Test
    void downloadFileReturnsRawBytes() throws Exception {
        AgentRunDataPlaneHttp http = httpWithMock();

        byte[] raw = "binary-data".getBytes(StandardCharsets.UTF_8);
        mockServer.enqueue(
                new MockResponse().setResponseCode(200).setBody(new okio.Buffer().write(raw)));

        byte[] downloaded = http.downloadFile("sb-1", "/home/user/data.bin");

        assertEquals(
                new String(raw, StandardCharsets.UTF_8),
                new String(downloaded, StandardCharsets.UTF_8));

        RecordedRequest req = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(req);
        assertEquals("GET", req.getMethod());
        assertTrue(req.getPath().startsWith("/sandboxes/sb-1/filesystem/download"));
        assertTrue(req.getPath().contains("path="));
    }

    @Test
    void getSandboxOrNullReturnsNullOn404() throws Exception {
        AgentRunDataPlaneHttp http = httpWithMock();

        mockServer.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));

        JsonNode result = http.getSandboxOrNull("sb-gone");
        assertNull(result);
    }

    @Test
    void getSandboxOrNullReturnsJsonOn200() throws Exception {
        AgentRunDataPlaneHttp http = httpWithMock();

        mockServer.enqueue(
                new MockResponse().setResponseCode(200).setBody("{\"status\":\"READY\"}"));

        JsonNode result = http.getSandboxOrNull("sb-1");
        assertNotNull(result);
        assertEquals("READY", result.get("status").asText());
    }

    @Test
    void isNotFoundDetects404InMessage() {
        assertTrue(
                AgentRunDataPlaneHttp.isNotFound(
                        new RuntimeException("AgentRun HTTP 404 Not Found")));
        assertTrue(
                AgentRunDataPlaneHttp.isNotFound(
                        new RuntimeException(
                                "wrapper", new RuntimeException("HTTP 404: sandbox not found"))));
    }

    @Test
    void isNotFoundReturnsFalseForOtherErrors() {
        assertTrue(
                !AgentRunDataPlaneHttp.isNotFound(
                        new RuntimeException("AgentRun HTTP 500 Internal Error")));
        assertTrue(!AgentRunDataPlaneHttp.isNotFound(new RuntimeException("timeout")));
    }
}
