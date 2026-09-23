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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import org.junit.jupiter.api.Test;

class AgentRunSandboxShutdownTest {

    private static AgentRunSandboxState state(String id, boolean owned) {
        AgentRunSandboxState state = new AgentRunSandboxState();
        state.setSandboxId(id);
        state.setSandboxOwned(owned);
        state.setWorkspaceRoot(AgentRunSandboxState.DEFAULT_WORKSPACE_ROOT);
        WorkspaceSpec ws = new WorkspaceSpec();
        ws.setRoot(AgentRunSandboxState.DEFAULT_WORKSPACE_ROOT);
        state.setWorkspaceSpec(ws);
        return state;
    }

    private static AgentRunSandboxClientOptions options() {
        AgentRunSandboxClientOptions opt = new AgentRunSandboxClientOptions();
        opt.setApiKey("test-key");
        opt.setTemplateName("agentscope-default");
        return opt;
    }

    private static AgentRunSandbox sandbox(String id, boolean owned, AgentRunDataPlaneHttp http) {
        return new AgentRunSandbox(state(id, owned), options(), http);
    }

    @Test
    void shutdown_neverDeletesInstanceRegardlessOfOwnership() throws Exception {
        AgentRunDataPlaneHttp http = mock(AgentRunDataPlaneHttp.class);

        sandbox("sbx-shared", false, http).shutdown();
        sandbox("sbx-owned", true, http).shutdown();

        // AgentRun reclaims instances on its own idle timeout; teardown is explicit via delete().
        verify(http, never()).deleteSandbox("sbx-shared");
        verify(http, never()).deleteSandbox("sbx-owned");
    }

    @Test
    void destroyInstance_skipsNonOwnedSandbox() throws Exception {
        AgentRunDataPlaneHttp http = mock(AgentRunDataPlaneHttp.class);

        sandbox("sbx-shared", false, http).destroyInstance();

        verify(http, never()).deleteSandbox("sbx-shared");
    }

    @Test
    void destroyInstance_deletesOwnedSandbox() throws Exception {
        AgentRunDataPlaneHttp http = mock(AgentRunDataPlaneHttp.class);

        sandbox("sbx-owned", true, http).destroyInstance();

        verify(http, times(1)).deleteSandbox("sbx-owned");
    }
}
