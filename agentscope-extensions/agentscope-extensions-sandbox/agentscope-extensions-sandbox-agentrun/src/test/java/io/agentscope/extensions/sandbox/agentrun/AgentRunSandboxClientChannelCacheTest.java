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

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Verifies the per-sandboxId MCP channel cache in {@link AgentRunSandboxClient}: the same
 * sandboxId reuses one channel across resume cycles (keeping the MCP session alive), different
 * sandboxIds get distinct channels, and {@code delete} evicts the cached channel.
 */
class AgentRunSandboxClientChannelCacheTest {

    private static AgentRunSandboxClient clientWithDefaultUrl() {
        AgentRunSandboxClientOptions opts = new AgentRunSandboxClientOptions();
        opts.setMcpServerUrl("https://example.com/mcp");
        return new AgentRunSandboxClient(opts, null);
    }

    @Test
    void sameSandboxIdReusesChannelAcrossResumes() throws Exception {
        AgentRunSandboxClient client = clientWithDefaultUrl();
        AgentRunSandboxState state = stateWithSandboxId("01KE8DAJ35JC8SKP9CNFRZ8CW7");

        Sandbox first = client.resume(state);
        Sandbox second = client.resume(state);

        // Same sandboxId → same cached channel instance (MCP session survives across cycles).
        assertSame(channelOf(first), channelOf(second));
    }

    @Test
    void differentSandboxIdsGetDistinctChannels() throws Exception {
        AgentRunSandboxClient client = clientWithDefaultUrl();

        Sandbox a = client.resume(stateWithSandboxId("01KE8DAJ35JC8SKP9CNFRZ8CW7"));
        Sandbox b = client.resume(stateWithSandboxId("01KE8DAJ35JC8SKP9CNFRZ8CW8"));

        assertNotSame(channelOf(a), channelOf(b));
    }

    @Test
    void deleteEvictsCachedChannelSoNextResumeGetsFreshOne() throws Exception {
        AgentRunSandboxClient client = clientWithDefaultUrl();
        AgentRunSandboxState state = stateWithSandboxId("01KE8DAJ35JC8SKP9CNFRZ8CW7");
        // Not sandbox-owned so shutdown()/close() won't fire real HTTP deletes or MCP calls
        // during the test — we only care about the channel-cache eviction behaviour here.
        state.setSandboxOwned(false);

        Sandbox first = client.resume(state);
        AgentRunMcpChannel firstChannel = channelOf(first);

        // delete should remove the cached channel for this sandboxId.
        client.delete(first);

        Sandbox second = client.resume(state);
        // A fresh channel is built after eviction — different instance, new MCP session.
        assertNotSame(firstChannel, channelOf(second));
    }

    private static AgentRunSandboxState stateWithSandboxId(String sandboxId) {
        AgentRunSandboxState state = new AgentRunSandboxState();
        state.setSessionId("session-" + sandboxId);
        state.setSandboxId(sandboxId);
        state.setWorkspaceRoot("/home/user/workspace");
        state.setTemplateName("agentscope-default");
        state.setAccountId("123456789012");
        state.setRegion("cn-hangzhou");
        state.setMcpServerUrl("https://example.com/mcp");
        state.setSandboxOwned(false);
        WorkspaceSpec ws = new WorkspaceSpec();
        ws.setRoot("/home/user/workspace");
        state.setWorkspaceSpec(ws);
        return state;
    }

    /** Reflectively reads the private {@code mcp} field from an {@link AgentRunSandbox}. */
    private static AgentRunMcpChannel channelOf(Sandbox sandbox) throws Exception {
        Field f = sandbox.getClass().getDeclaredField("mcp");
        f.setAccessible(true);
        return (AgentRunMcpChannel) f.get(sandbox);
    }
}
