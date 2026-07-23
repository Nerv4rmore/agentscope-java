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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Verifies the build/resume behaviour of {@link AgentRunSandboxClient} after the MCP→HTTP
 * migration: each resume produces a fresh {@link AgentRunSandbox} (no channel cache), the state is
 * refreshed from live options, and delete is safe to call on non-owned sandboxes.
 */
class AgentRunSandboxClientChannelCacheTest {

    private static AgentRunSandboxClient clientWithDefaults() {
        AgentRunSandboxClientOptions opts = new AgentRunSandboxClientOptions();
        opts.setApiKey("test-key");
        opts.setAccountId("1234567890");
        opts.setRegion("cn-hangzhou");
        opts.setTemplateName("agentscope-default");
        return new AgentRunSandboxClient(opts, null);
    }

    @Test
    void resumeProducesSandboxWithRefreshedState() {
        AgentRunSandboxClient client = clientWithDefaults();
        AgentRunSandboxState state = stateWithSandboxId("01KE8DAJ35JC8SKP9CNFRZ8CW7");

        Sandbox first = client.resume(state);

        // The resumed sandbox should carry the sandboxId from the persisted state.
        assertEquals(
                "01KE8DAJ35JC8SKP9CNFRZ8CW7",
                ((AgentRunSandboxState) first.getState()).getSandboxId());
    }

    @Test
    void eachResumeProducesIndependentSandboxInstance() throws Exception {
        AgentRunSandboxClient client = clientWithDefaults();
        AgentRunSandboxState state = stateWithSandboxId("01KE8DAJ35JC8SKP9CNFRZ8CW7");

        Sandbox first = client.resume(state);
        Sandbox second = client.resume(state);

        // No channel cache → each resume builds a fresh AgentRunSandbox (different http instance).
        assertNotSame(httpOf(first), httpOf(second));
    }

    @Test
    void resumeSameStatePreservesSandboxId() {
        AgentRunSandboxClient client = clientWithDefaults();
        AgentRunSandboxState state = stateWithSandboxId("01KE8DAJ35JC8SKP9CNFRZ8CW7");

        Sandbox first = client.resume(state);
        Sandbox second = client.resume(state);

        // Same persisted sandboxId → both sandboxes reference the same logical instance.
        assertSame(
                ((AgentRunSandboxState) first.getState()).getSandboxId(),
                ((AgentRunSandboxState) second.getState()).getSandboxId());
    }

    @Test
    void deleteIsSafeForNonOwnedSandbox() {
        AgentRunSandboxClient client = clientWithDefaults();
        AgentRunSandboxState state = stateWithSandboxId("01KE8DAJ35JC8SKP9CNFRZ8CW7");
        // Not sandbox-owned so destroyInstance() won't fire a real HTTP delete during the test.
        state.setSandboxOwned(false);

        Sandbox first = client.resume(state);
        // Should not throw — delete on a non-owned sandbox is a no-op for destroyInstance.
        client.delete(first);
    }

    private static AgentRunSandboxState stateWithSandboxId(String sandboxId) {
        AgentRunSandboxState state = new AgentRunSandboxState();
        state.setSessionId("session-" + sandboxId);
        state.setSandboxId(sandboxId);
        state.setWorkspaceRoot("/home/user/workspace");
        state.setTemplateName("agentscope-default");
        state.setAccountId("123456789012");
        state.setRegion("cn-hangzhou");
        state.setSandboxOwned(false);
        WorkspaceSpec ws = new WorkspaceSpec();
        ws.setRoot("/home/user/workspace");
        state.setWorkspaceSpec(ws);
        return state;
    }

    /** Reflectively reads the private {@code http} field from an {@link AgentRunSandbox}. */
    private static Object httpOf(Sandbox sandbox) throws Exception {
        Field f = sandbox.getClass().getDeclaredField("http");
        f.setAccessible(true);
        return f.get(sandbox);
    }
}
