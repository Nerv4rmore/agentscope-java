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

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@code AgentRunMcpChannel.parseExecPayload} against the three response shapes the
 * AgentRun MCP server may emit: bare JSON, markdown-wrapped (nested {@code result}) JSON, and
 * plain text. The markdown-wrapped case is the regression that previously caused every sandbox
 * {@code glob}/{@code read} to receive the wrapper as stdout and corrupt downstream state files.
 */
class AgentRunMcpChannelParseExecPayloadTest {

    /** Reflective accessor — parseExecPayload is private static. */
    private static Object parseExecPayload(String text) throws Exception {
        Class<?> execResultClass =
                Class.forName(
                        "io.agentscope.extensions.sandbox.agentrun.AgentRunMcpChannel$ExecResult");
        Method m = AgentRunMcpChannel.class.getDeclaredMethod("parseExecPayload", String.class);
        m.setAccessible(true);
        return m.invoke(null, text);
    }

    private static int exitCode(Object execResult) throws Exception {
        java.lang.reflect.Field f = execResult.getClass().getDeclaredField("exitCode");
        f.setAccessible(true);
        return (int) f.get(execResult);
    }

    private static String stdout(Object execResult) throws Exception {
        java.lang.reflect.Field f = execResult.getClass().getDeclaredField("stdout");
        f.setAccessible(true);
        return (String) f.get(execResult);
    }

    private static String stderr(Object execResult) throws Exception {
        java.lang.reflect.Field f = execResult.getClass().getDeclaredField("stderr");
        f.setAccessible(true);
        return (String) f.get(execResult);
    }

    @Test
    void bareJsonObjectWithStdout() throws Exception {
        String payload = "{\"exitCode\":0,\"stdout\":\"hello\\n\",\"stderr\":\"\"}";
        Object r = parseExecPayload(payload);
        assertNotNull(r);
        assertEquals(0, exitCode(r));
        assertEquals("hello\n", stdout(r));
        assertEquals("", stderr(r));
    }

    @Test
    void bareJsonObjectWithOutputAlias() throws Exception {
        // Legacy shape using "output" instead of "stdout" and "exit_code".
        String payload = "{\"exit_code\":1,\"output\":\"boom\",\"stderr\":\"err\"}";
        Object r = parseExecPayload(payload);
        assertEquals(1, exitCode(r));
        assertEquals("boom", stdout(r));
        assertEquals("err", stderr(r));
    }

    @Test
    void markdownWrappedNestedResultExtractsRealStdout() throws Exception {
        // The shape emitted by the AgentRun MCP server: a markdown header wrapping a JSON body
        // whose real command output sits under result.stdout. This is the regression case.
        String payload =
                "### process_exec_cmd\n\n"
                    + "**Sandbox ID (MCP Session ID):** `29ad05d5-17c6-4e7c-99e7-810cdf02b7ea`\n\n"
                    + "**Response:**\n"
                    + "{\n"
                    + "  \"executionId\": \"2af3995a-7e85-4239-9d02-c6b8ca1803ab\",\n"
                    + "  \"status\": \"completed\",\n"
                    + "  \"result\": {\n"
                    + "    \"exitCode\": 0,\n"
                    + "    \"stdout\": \"2026-06-01.md\\n"
                    + "2026-06-02.md\\n"
                    + "\",\n"
                    + "    \"stderr\": \"\",\n"
                    + "    \"cwd\": \"/home/user/workspace\",\n"
                    + "    \"executionTimeMs\": 11\n"
                    + "  }\n"
                    + "}";
        Object r = parseExecPayload(payload);
        assertNotNull(r);
        assertEquals(0, exitCode(r));
        // Must be the real stdout, NOT the markdown wrapper.
        assertEquals("2026-06-01.md\n2026-06-02.md\n", stdout(r));
        assertEquals("", stderr(r));
    }

    @Test
    void markdownWrappedWithBracesInsideStringLiteral() throws Exception {
        // The balanced-brace extractor must ignore braces that appear inside JSON string values
        // (e.g. a command whose stdout contains a literal '}' character).
        String payload =
                "**Response:**\n"
                        + "{\"result\":{\"exitCode\":0,\"stdout\":\"path has }"
                        + " brace\",\"stderr\":\"\"}}";
        Object r = parseExecPayload(payload);
        assertEquals(0, exitCode(r));
        assertEquals("path has } brace", stdout(r));
    }

    @Test
    void plainTextFallbackWhenNoJson() throws Exception {
        // A genuinely plain-text response (no JSON anywhere) falls back to whole-text stdout.
        String payload = "just some command output with no json";
        Object r = parseExecPayload(payload);
        assertEquals(0, exitCode(r));
        assertEquals("just some command output with no json", stdout(r));
        assertEquals("", stderr(r));
    }

    @Test
    void nullInputReturnsEmpty() throws Exception {
        Object r = parseExecPayload(null);
        assertNotNull(r);
        assertEquals(0, exitCode(r));
        assertEquals("", stdout(r));
    }

    @Test
    void nonExecJsonObjectFallsBackToPlainText() throws Exception {
        // A JSON object that is NOT an exec-result (no exitCode/stdout/output) must not be
        // misread as one — fall back to plain-text stdout so callers see the raw text.
        String payload = "{\"executionId\":\"x\",\"status\":\"completed\"}";
        Object r = parseExecPayload(payload);
        assertEquals(0, exitCode(r));
        assertEquals(payload, stdout(r));
    }
}
