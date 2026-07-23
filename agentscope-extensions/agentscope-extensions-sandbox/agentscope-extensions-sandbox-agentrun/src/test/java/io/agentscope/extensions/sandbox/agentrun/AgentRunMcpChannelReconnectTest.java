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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Verifies the session-loss detection that drives {@code AgentRunMcpChannel}'s auto-reconnect.
 * The detection must recognise the MCP SDK's {@link McpTransportSessionNotFoundException} as
 * well as Reactor-wrapped variants whose message mentions session termination — while not
 * false-triggering on unrelated errors.
 */
class AgentRunMcpChannelReconnectTest {

    @Test
    void directSessionNotFoundExceptionIsDetected() throws Exception {
        assertTrue(
                isSessionLost(
                        new McpTransportSessionNotFoundException(
                                "Session not found for session ID: 9ba66051")));
    }

    @Test
    void wrappedSessionLossIsDetectedViaCause() throws Exception {
        // Reactor's Mono.block() may wrap the original exception; the cause chain must still match.
        Throwable root = new McpTransportSessionNotFoundException("session expired");
        RuntimeException wrapped = new RuntimeException("MCP session with server terminated", root);
        assertTrue(isSessionLost(wrapped));
    }

    @Test
    void sessionLossDetectedByMessageOnly() throws Exception {
        // Some transports throw a plain RuntimeException without the typed exception class.
        assertTrue(isSessionLost(new RuntimeException("Session not found for session ID: abc")));
        assertTrue(
                isSessionLost(
                        new RuntimeException("Server does not recognize session Optional[9ba6]")));
        assertTrue(isSessionLost(new RuntimeException("MCP session with server terminated")));
    }

    @Test
    void agentRunSessionExpiredIsDetected() throws Exception {
        // AgentRun returns {"Code":"SessionExpired","Message":"session <id> is expired"} after the
        // sandbox idle-timeout reclaims the session. The MCP SDK surfaces this as a plain
        // RuntimeException whose message embeds the JSON — neither a typed
        // McpTransportSessionNotFoundException nor matching "session not found". Without explicit
        // recognition, recreateSandbox() never fires and start() fails permanently.
        assertTrue(
                isSessionLost(
                        new RuntimeException(
                                "Failed to send message: AggregateResponseEvent[...data="
                                        + "{\"RequestId\":\"1-6a4f69e9-015c168c-4b9304b3dbe8\","
                                        + "\"Code\":\"SessionExpired\",\"Message\":\"session"
                                        + " 9ec21cc6-18e4-4bdc-bb96-22072a8fa88f is expired\"}]")));
        assertTrue(isSessionLost(new RuntimeException("SessionExpired")));
        assertTrue(isSessionLost(new RuntimeException("session 9ec21cc6 is expired")));
    }

    @Test
    void unrelatedErrorsAreNotTreatedAsSessionLoss() throws Exception {
        assertFalse(isSessionLost(new RuntimeException("command timed out")));
        assertFalse(isSessionLost(new IllegalStateException("tool not found")));
        assertFalse(isSessionLost(new NullPointerException()));
    }

    @Test
    void nullMessageDoesNotCrash() throws Exception {
        assertFalse(isSessionLost(new RuntimeException()));
    }

    /** Reflectively invokes the private {@code isSessionLost(Throwable)} static method. */
    private static boolean isSessionLost(Throwable e) throws Exception {
        Method m = AgentRunMcpChannel.class.getDeclaredMethod("isSessionLost", Throwable.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, e);
    }
}
