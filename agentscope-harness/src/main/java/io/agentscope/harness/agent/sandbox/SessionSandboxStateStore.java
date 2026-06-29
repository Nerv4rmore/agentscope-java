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

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.harness.agent.IsolationScope;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Sandbox state store backed by the generic AgentScope {@link AgentStateStore} abstraction.
 *
 * <p>This store keeps sandbox lifecycle state in the same state backend as ReActAgent runtime
 * state. As a result, providing a distributed {@link AgentStateStore} implementation (for example Redis)
 * automatically enables distributed sandbox resume state.
 */
public final class SessionSandboxStateStore {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SessionSandboxStateStore.class);

    private static final String SANDBOX_STATE_KEY = "_sandbox_state";

    private final AgentStateStore stateStore;
    private final String agentId;

    public SessionSandboxStateStore(AgentStateStore stateStore, String agentId) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
        this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
    }

    public Optional<String> load(SandboxIsolationKey key) throws IOException {
        String slotSid = slotSessionId(key);
        try {
            Optional<SandboxStateSlot> state =
                    stateStore.get(null, slotSid, SANDBOX_STATE_KEY, SandboxStateSlot.class);
            if (state.isEmpty()) {
                // 诊断：slot 下根本没有记录（首轮正常；非首轮则说明上一轮没写进来）
                log.info(
                        "[sandbox-store-diag] load MISS(empty): agentId={}, slot={}, key={}",
                        agentId,
                        slotSid,
                        key);
                return Optional.empty();
            }
            if (state.get().deleted()) {
                // 诊断：被 tombstone 标记删除（clearState 调用 / 跨调用被清）
                log.info(
                        "[sandbox-store-diag] load MISS(tombstoned): agentId={}, slot={}, key={}",
                        agentId,
                        slotSid,
                        key);
                return Optional.empty();
            }
            if (state.get().json() == null) {
                log.info(
                        "[sandbox-store-diag] load MISS(null json): agentId={}, slot={}, key={}",
                        agentId,
                        slotSid,
                        key);
                return Optional.empty();
            }
            // 诊断：命中，记录 slot 便于和 persistState 写入侧对照
            log.info(
                    "[sandbox-store-diag] load HIT: agentId={}, slot={}, key={}, jsonLen={}",
                    agentId,
                    slotSid,
                    key,
                    state.get().json().length());
            return Optional.of(state.get().json());
        } catch (Exception e) {
            // 诊断：load 抛异常（IO / 反序列化），会被 SandboxManager 当作 Priority 3 ERROR
            log.warn(
                    "[sandbox-store-diag] load ERROR: agentId={}, slot={}, key={}, error={}",
                    agentId,
                    slotSid,
                    key,
                    e.getMessage(),
                    e);
            throw asIo("load", key, e);
        }
    }

    public void save(SandboxIsolationKey key, String json) throws IOException {
        String slotSid = slotSessionId(key);
        try {
            stateStore.save(null, slotSid, SANDBOX_STATE_KEY, new SandboxStateSlot(json, false));
            // 诊断：写盘完成，记录 slot + 底层 store 类型，便于核对读侧 load 是否走同一 slot
            log.info(
                    "[sandbox-store-diag] save OK: agentId={}, slot={}, key={}, jsonLen={},"
                            + " storeType={}",
                    agentId,
                    slotSid,
                    key,
                    json != null ? json.length() : 0,
                    stateStore.getClass().getSimpleName());
        } catch (Exception e) {
            // 诊断：save 抛异常 = 下一轮 load 必然 MISS 的直接原因
            log.warn(
                    "[sandbox-store-diag] save ERROR: agentId={}, slot={}, key={}, error={}",
                    agentId,
                    slotSid,
                    key,
                    e.getMessage(),
                    e);
            throw asIo("save", key, e);
        }
    }

    public void delete(SandboxIsolationKey key) throws IOException {
        String slotSid = slotSessionId(key);
        try {
            // 诊断：delete 会写 tombstone，是导致后续 load MISS 的关键来源（如正常流程不应调用）
            log.info(
                    "[sandbox-store-diag] delete(tombstone): agentId={}, slot={}, key={}",
                    agentId,
                    slotSid,
                    key);
            // Not all AgentStateStore implementations support per-key delete; tombstone keeps
            // behavior consistent across stores.
            stateStore.save(null, slotSid, SANDBOX_STATE_KEY, SandboxStateSlot.tombstone());
        } catch (Exception e) {
            throw asIo("delete", key, e);
        }
    }

    /**
     * Pack the sandbox isolation key into a single sessionId string that fits the
     * {@link AgentStateStore} 2-arg slot model. The userId column is always {@code null} because
     * sandbox state is conceptually agent-scoped, not user-scoped: USER/AGENT/GLOBAL scopes are
     * encoded into the sessionId prefix rather than the userId slot.
     */
    private String slotSessionId(SandboxIsolationKey key) {
        IsolationScope scope = key.getScope();
        return switch (scope) {
            case SESSION -> "sandbox/session/" + key.getValue();
            case USER -> "sandbox/user/" + agentId + "/" + key.getValue();
            case AGENT -> "sandbox/agent/" + agentId;
            case GLOBAL -> "sandbox/global";
        };
    }

    private static IOException asIo(String op, SandboxIsolationKey key, Exception e) {
        return new IOException("Failed to " + op + " sandbox state for " + key, e);
    }

    private record SandboxStateSlot(String json, boolean deleted) implements State {
        static SandboxStateSlot tombstone() {
            return new SandboxStateSlot("", true);
        }
    }
}
