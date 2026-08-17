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
package io.agentscope.harness.agent.sandbox;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;

/**
 * Base class for sandbox client configuration options.
 *
 * <p>Each concrete subclass describes a specific sandbox backend (e.g. Docker) and can
 * self-instantiate the corresponding {@link SandboxClient} via {@link #createClient()}.
 * This allows callers to configure only the options object and rely on
 * {@link io.agentscope.harness.agent.HarnessAgent.Builder} to derive the client automatically.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = DockerSandboxClientOptions.class, name = "docker"),
})
public abstract class SandboxClientOptions {

    /**
     * Returns the type discriminator used in JSON serialization.
     *
     * @return type string (e.g. "docker")
     */
    public abstract String getType();

    /**
     * Creates the {@link SandboxClient} implementation that corresponds to these options.
     *
     * <p>Called by {@link io.agentscope.harness.agent.HarnessAgent.Builder} when no explicit
     * client has been provided, so callers only need to configure the options object.
     *
     * @return a new client instance ready for use
     */
    public abstract SandboxClient<? extends SandboxClientOptions> createClient();

    /**
     * Returns the absolute workspace root path inside the sandbox container.
     *
     * <p>Defaults to {@code /workspace}. Concrete subclasses override this when the sandbox
     * backend uses a different root (e.g. AgentRun uses {@code /home/agentscope/workspace}).
     *
     * @return workspace root path string
     */
    public String getWorkspaceRoot() {
        return "/workspace";
    }

    /**
     * Returns a copy of these options with the per-call user id applied.
     *
     * <p>Called by {@link SandboxManager#acquire} before invoking
     * {@link SandboxClient#create}, so concrete backends can resolve per-user configuration
     * (e.g. a per-user OSS mount path) from the call identity. The default implementation
     * returns {@code this} unchanged; backends that need the user id override this method
     * and return a mutable copy.
     *
     * @param userId the resolved user id for the current call (may be {@code null})
     * @return options to pass into {@code create}; {@code this} by default
     */
    public SandboxClientOptions withCallUserId(String userId) {
        return this;
    }

    /**
     * Returns a copy of these options with the per-call workspace root applied.
     *
     * <p>Called by {@link SandboxManager#acquire} before invoking
     * {@link SandboxClient#create} or {@link SandboxClient#resume(SandboxState,
     * SandboxClientOptions)}, so concrete backends can isolate the working directory per
     * call (e.g. a per-chat-session subdirectory under the shared workspace root). The
     * default implementation returns {@code this} unchanged; backends that support
     * per-call workspace roots override this method and return a mutable copy.
     *
     * @param workspaceRoot the per-call workspace root inside the sandbox (may be {@code null})
     * @return options to pass into {@code create}/{@code resume}; {@code this} by default
     */
    public SandboxClientOptions withCallWorkspaceRoot(String workspaceRoot) {
        return this;
    }
}
