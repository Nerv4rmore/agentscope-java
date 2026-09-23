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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SandboxLifecycleMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SandboxLifecycleMiddleware.class);

    private final SandboxManager sandboxManager;
    private final SandboxBackedFilesystem filesystemProxy;
    private volatile Consumer<RuntimeContext> beforeStartCallback;

    public SandboxLifecycleMiddleware(
            SandboxManager sandboxManager, SandboxBackedFilesystem filesystemProxy) {
        this.sandboxManager = sandboxManager;
        this.filesystemProxy = filesystemProxy;
    }

    public void setBeforeStartCallback(Consumer<RuntimeContext> callback) {
        this.beforeStartCallback = callback;
    }

    public void acquireForCall(RuntimeContext ctx) {
        if (ctx == null) {
            return;
        }
        SandboxContext sandboxContext = ctx.get(SandboxContext.class);
        if (sandboxContext == null) {
            return;
        }
        log.info(
                "[sandbox-diag] acquireForCall: sessionId={}, userId={}, externalSandbox={},"
                        + " externalState={}",
                ctx.getSessionId(), ctx.getUserId(),
                sandboxContext.getExternalSandbox() != null,
                sandboxContext.getExternalSandboxState() != null);
        filesystemProxy.bindLifecycle(sandboxManager, sandboxContext, ctx);
        if (sandboxContext.getExternalSandbox() != null
                || sandboxContext.getExternalSandboxState() != null) {
            Consumer<RuntimeContext> callback = beforeStartCallback;
            if (callback != null) {
                try {
                    callback.accept(ctx);
                } catch (Exception e) {
                    log.warn("[sandbox-mw] beforeStartCallback failed; proceeding with sandbox start", e);
                }
            }
            filesystemProxy.requireSandbox(ctx);
        }
    }

    public void releaseForCall(RuntimeContext ctx) {
        if (ctx == null) {
            return;
        }
        // 异步子任务延迟释放时仍使用原绑定，不受调用方复用上下文的影响。
        RuntimeContext releaseContext = RuntimeContext.builder(ctx).build();
        filesystemProxy.requestRelease(releaseContext, () -> releaseNow(releaseContext));
    }

    private void releaseNow(RuntimeContext ctx) {
        SandboxAcquireResult result = filesystemProxy.consumeAcquireResult(ctx);
        log.info("[sandbox-diag] releaseForCall: sessionId={}, userId={}, acquired={}",
                ctx.getSessionId(), ctx.getUserId(), result != null);
        if (result == null) {
            return;
        }
        try {
            sandboxManager.persistState(result, ctx.get(SandboxContext.class), ctx);
        } catch (Exception e) {
            log.warn("[sandbox-mw] Failed to persist sandbox state", e);
        }
        try {
            sandboxManager.release(result);
        } catch (Exception e) {
            log.warn("[sandbox-mw] Failed to release sandbox session", e);
        } finally {
            result.getLease().close();
        }
    }
}
