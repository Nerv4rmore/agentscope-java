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
import io.agentscope.harness.agent.filesystem.sandbox.PinnedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;

/** 按调用绑定懒加载沙箱，待共享异步任务结束后释放并持久化状态。 */
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

    /** 生命周期由 HarnessAgent 显式驱动，不参与扩展点调度。 */
    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.noneOf(ExtensionPoint.class);
    }

    /** 在显式外部沙箱启动前准备工作区资源；传入 null 可清除回调。 */
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
                ctx.getSessionId(),
                ctx.getUserId(),
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
                    log.warn(
                            "[sandbox-mw] beforeStartCallback failed; proceeding with sandbox"
                                    + " start",
                            e);
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
        log.info(
                "[sandbox-diag] releaseForCall: sessionId={}, userId={}, acquired={}",
                ctx.getSessionId(),
                ctx.getUserId(),
                result != null);
        if (result == null) {
            return;
        }
        if (!result.beginRelease()) {
            return;
        }
        try {
            // 先释放，再保存 stop 更新后的工作区及快照状态。
            try {
                markMirrorSandboxReleased(result);
                sandboxManager.release(result);
            } catch (Exception e) {
                log.warn("[sandbox-mw] Failed to release sandbox session", e);
            }
            try {
                sandboxManager.persistState(result, ctx.get(SandboxContext.class), ctx);
            } catch (Exception e) {
                log.warn("[sandbox-mw] Failed to persist sandbox state", e);
            }
        } finally {
            result.getLease().close();
        }
    }

    private static void markMirrorSandboxReleased(SandboxAcquireResult result) {
        if (result.isSelfManaged()) {
            PinnedSandboxFilesystem.markSandboxReleased(result.getSandbox());
        }
    }
}
