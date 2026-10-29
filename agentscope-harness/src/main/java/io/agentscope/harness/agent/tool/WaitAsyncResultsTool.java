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
package io.agentscope.harness.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Tool that blocks until async results arrive, either for a specific task barrier or (legacy)
 * until any message appears in the session inbox.
 *
 * <p>Prefer barrier mode ({@code task_ids} or {@code wait_all=true}): when the wait set reaches
 * terminal state, this tool embeds each task's status and result in the tool return so the model
 * can continue reasoning immediately without waiting for push-back delivery.
 *
 * <p>Without {@code task_ids} / {@code wait_all}, this keeps the legacy inbox-any behavior: the
 * tool returns as soon as <em>any</em> inbox message is present — it is not a wait-all barrier.
 *
 * <p>Guardrails prevent unbounded blocking:
 * <ul>
 *   <li>Timeout is clamped to {@value #MAX_TIMEOUT_SECONDS}s regardless of the LLM-supplied value.
 *   <li>After {@value #MAX_CONSECUTIVE_EMPTY_WAITS} consecutive timeouts with no results, the tool
 *       refuses further blocking waits and directs the LLM to use non-blocking alternatives.
 * </ul>
 */
public class WaitAsyncResultsTool {

    private static final Logger log = LoggerFactory.getLogger(WaitAsyncResultsTool.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    // 10 × 120s = 20 min max waiting capacity — covers the longest subagent
    // (slide-designer: 38 iterations, 53 tool calls, ~20 min). The previous
    // value of 2 (4 min) was exhausted after the first subagent, blocking all
    // subsequent waits for the second subagent.
    private static final int MAX_CONSECUTIVE_EMPTY_WAITS = 10;
    private static final long POLL_INTERVAL_MS = 1000;

    private final MessageBus messageBus;
    private final TaskRepository taskRepository;
    private final LongSupplier currentTimeMillis;
    private final Sleeper sleeper;
    private final long pollIntervalMs;
    private final TaskWaiter taskWaiter;
    private final ConcurrentHashMap<String, AtomicInteger> consecutiveEmptyWaitsBySession =
            new ConcurrentHashMap<>();
    private volatile BooleanSupplier externalWorkProbe;

    public WaitAsyncResultsTool(MessageBus messageBus) {
        this(messageBus, null);
    }

    public WaitAsyncResultsTool(MessageBus messageBus, TaskRepository taskRepository) {
        this(
                messageBus,
                taskRepository,
                System::currentTimeMillis,
                Thread::sleep,
                POLL_INTERVAL_MS,
                BackgroundTask::waitForCompletion);
    }

    /**
     * Same as {@link #WaitAsyncResultsTool(MessageBus, TaskRepository)}, with a custom polling
     * granularity for tests: mid-budget task completion is noticed after one test interval instead
     * of one production interval.
     */
    WaitAsyncResultsTool(
            MessageBus messageBus, TaskRepository taskRepository, long pollIntervalMs) {
        this(
                messageBus,
                taskRepository,
                System::currentTimeMillis,
                Thread::sleep,
                pollIntervalMs,
                BackgroundTask::waitForCompletion);
    }

    /** Allows timeout tests to advance elapsed time without waiting for the real deadline. */
    WaitAsyncResultsTool(
            MessageBus messageBus,
            TaskRepository taskRepository,
            LongSupplier currentTimeMillis,
            Sleeper sleeper) {
        this(messageBus, taskRepository, currentTimeMillis, sleeper, POLL_INTERVAL_MS);
    }

    /**
     * Allows timeout tests to advance elapsed time without waiting for the real deadline, and to
     * shrink the polling granularity so waits complete promptly.
     */
    WaitAsyncResultsTool(
            MessageBus messageBus,
            TaskRepository taskRepository,
            LongSupplier currentTimeMillis,
            Sleeper sleeper,
            long pollIntervalMs) {
        this(
                messageBus,
                taskRepository,
                currentTimeMillis,
                sleeper,
                pollIntervalMs,
                (task, millis) -> {
                    // 注入时钟时由同一个 sleeper 推进时间，不能阻塞在真实 Future 超时上。
                    if (!task.waitForCompletion(0L)) {
                        sleeper.sleep(millis);
                    }
                });
    }

    private WaitAsyncResultsTool(
            MessageBus messageBus,
            TaskRepository taskRepository,
            LongSupplier currentTimeMillis,
            Sleeper sleeper,
            long pollIntervalMs,
            TaskWaiter taskWaiter) {
        this.messageBus = messageBus;
        this.taskRepository = taskRepository;
        this.currentTimeMillis = currentTimeMillis;
        this.sleeper = sleeper;
        this.pollIntervalMs = pollIntervalMs;
        this.taskWaiter = taskWaiter;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    private interface TaskWaiter {
        void await(BackgroundTask task, long millis) throws InterruptedException;
    }

    /**
     * Registers a probe for outstanding work owned outside the subagent task repository (currently
     * AgentTeams teammates). Without it a lead with no subagent tasks would be told to stop waiting
     * while its teammates are still running.
     */
    public WaitAsyncResultsTool setExternalWorkProbe(BooleanSupplier probe) {
        this.externalWorkProbe = probe;
        return this;
    }

    private boolean hasExternalWork() {
        BooleanSupplier probe = this.externalWorkProbe;
        if (probe == null) {
            return false;
        }
        try {
            return probe.getAsBoolean();
        } catch (RuntimeException e) {
            log.debug("external work probe failed: {}", e.toString());
            return false;
        }
    }

    @Tool(
            name = "wait_async_results",
            description =
                    "Wait for background async tool, subagent, or teammate results. "
                            + "Prefer barrier mode: task_ids waits until those specific tasks are "
                            + "all terminal and returns their results in this tool output; "
                            + "wait_all=true waits for the snapshot of currently running tasks "
                            + "(tasks created while waiting are not added) and also returns their "
                            + "results. "
                            + "Without task_ids and without wait_all, this is legacy inbox-any "
                            + "mode: returns when ANY inbox message arrives — not wait-all. For "
                            + "must-collect-all groups use task_ids or wait_all=true. "
                            + "Also covers AgentTeams teammate work via the external-work probe. "
                            + "Max timeout is 120 seconds. "
                            + "If you have already waited without results, use task_list or "
                            + "task_output(block=false) to check status instead of waiting again.",
            readOnly = true)
    public ToolResultBlock waitForResults(
            @ToolParam(
                            name = "timeout_seconds",
                            description =
                                    "Maximum seconds to wait. Default 60, max 120. "
                                            + "Values above 120 are clamped.")
                    Integer timeoutSeconds,
            @ToolParam(
                            name = "task_ids",
                            description =
                                    "Optional comma-separated task IDs to wait for. When set, the "
                                            + "tool returns only after all listed tasks are "
                                            + "terminal (or timeout), and embeds each task's "
                                            + "result in the response.",
                            required = false)
                    String taskIds,
            @ToolParam(
                            name = "wait_all",
                            description =
                                    "Optional barrier mode. When true and task_ids is empty, wait"
                                        + " for the snapshot of currently non-terminal tasks in"
                                        + " this session and embed their results. Tasks created"
                                        + " later are not added to the wait set. Not the same as"
                                        + " legacy no-arg inbox-any wait.",
                            required = false)
                    Boolean waitAll,
            RuntimeContext runtimeContext) {
        try {
            return doWaitForResults(timeoutSeconds, taskIds, waitAll, runtimeContext);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResultBlock.text("status: interrupted")
                    .withState(ToolResultState.INTERRUPTED);
        }
    }

    private ToolResultBlock doWaitForResults(
            Integer timeoutSeconds, String taskIds, Boolean waitAll, RuntimeContext runtimeContext)
            throws InterruptedException {

        String sessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        if (sessionId == null) {
            return ToolResultBlock.text("Cannot wait: no session context available.")
                    .withState(ToolResultState.ERROR);
        }

        AtomicInteger emptyWaits =
                consecutiveEmptyWaitsBySession.computeIfAbsent(
                        sessionId, k -> new AtomicInteger(0));
        List<String> explicitTaskIds = parseTaskIds(taskIds);
        if (!explicitTaskIds.isEmpty() || Boolean.TRUE.equals(waitAll)) {
            return waitForTaskBarrier(
                    timeoutSeconds, runtimeContext, sessionId, explicitTaskIds, emptyWaits);
        }

        if (emptyWaits.get() >= MAX_CONSECUTIVE_EMPTY_WAITS) {
            Boolean hasMessages = messageBus.inboxHasMessages(sessionId).block();
            if (Boolean.TRUE.equals(hasMessages)) {
                log.info(
                        "wait_async_results: budget was exhausted but inbox now has messages,"
                                + " resetting counter, session={}",
                        sessionId);
                emptyWaits.set(0);
                return ToolResultBlock.success(
                        "Async results have arrived (inbox-any: at least one message). "
                                + "Continue reasoning — the results will be injected into your"
                                + " context automatically. Prefer wait_async_results(task_ids=...)"
                                + " or wait_all=true when you need every task in a group.");
            }
            log.info(
                    "wait_async_results: rejected — {} consecutive empty waits reached, session={}",
                    emptyWaits.get(),
                    sessionId);
            return ToolResultBlock.success(
                    "Wait budget exhausted: you have already waited "
                            + emptyWaits.get()
                            + " times without receiving results. "
                            + "Do NOT call wait_async_results again. Instead use task_list to"
                            + " check task status, or task_output(block=false) to poll for"
                            + " results without blocking.");
        }

        if (taskRepository != null) {
            Collection<BackgroundTask> tasks =
                    taskRepository.listTasks(runtimeContext, sessionId, null);
            boolean hasNonTerminal =
                    tasks.stream().anyMatch(task -> !task.getTaskStatus().isTerminal())
                            || hasExternalWork();
            if (!hasNonTerminal) {
                Boolean hasMessages = messageBus.inboxHasMessages(sessionId).block();
                if (!Boolean.TRUE.equals(hasMessages)) {
                    log.info(
                            "wait_async_results: all tasks terminal and inbox empty,"
                                    + " returning immediately, session={}",
                            sessionId);
                    if (tasks.isEmpty()) {
                        return ToolResultBlock.success(
                                "status: no_tasks\n"
                                        + "No background tasks exist in this session. An empty"
                                        + " completion inbox is not evidence of running work."
                                        + " Continue the assigned work or report the missing"
                                        + " capability; do not keep waiting.");
                    }
                    return ToolResultBlock.text(
                                    "All background tasks have completed and no pending results"
                                            + " in inbox. Use task_list to review results, or"
                                            + " task_output(task_id) to read a specific result.")
                            .withState(barrierState(List.copyOf(tasks)));
                }
            }
        }

        int timeout = normalizeTimeout(timeoutSeconds, sessionId);

        log.info(
                "wait_async_results: waiting up to {}s for any async result"
                        + " (legacy inbox-any, event-driven), session={}",
                timeout,
                sessionId);

        long deadlineMs = currentTimeMillis.getAsLong() + (timeout * 1000L);

        // 先检查 inbox，再等待任务完成信号；无任务事件源时使用注入的轮询间隔。
        while (true) {
            Boolean hasMessages = messageBus.inboxHasMessages(sessionId).block();
            if (Boolean.TRUE.equals(hasMessages)) {
                log.info("wait_async_results: inbox has messages, session={}", sessionId);
                emptyWaits.set(0);
                return ToolResultBlock.success(
                        "Async results have arrived (inbox-any: at least one message). "
                                + "Continue reasoning — the results will be injected into your"
                                + " context automatically. Prefer wait_async_results(task_ids=...)"
                                + " or wait_all=true when you need every task in a group.");
            }
            long remainingMs = deadlineMs - currentTimeMillis.getAsLong();
            if (remainingMs <= 0) {
                break;
            }
            if (!waitForAnyTaskSignal(runtimeContext, sessionId, remainingMs)) {
                // 查询任务也会消耗预算，休眠前使用同一时钟重新计算。
                remainingMs = deadlineMs - currentTimeMillis.getAsLong();
                if (remainingMs > 0) {
                    sleepForPollInterval(remainingMs);
                }
            }
        }

        int emptyCount = emptyWaits.incrementAndGet();
        log.info(
                "wait_async_results: timeout after {}s (consecutive empty waits: {}), session={}",
                timeout,
                emptyCount,
                sessionId);
        return ToolResultBlock.success(
                "Timeout after "
                        + timeout
                        + "s. No async results yet (empty wait "
                        + emptyCount
                        + "/"
                        + MAX_CONSECUTIVE_EMPTY_WAITS
                        + "). "
                        + "Use task_list to check task status, or task_output(block=false) to"
                        + " poll without blocking.");
    }

    public ToolResultBlock waitForResults(Integer timeoutSeconds, RuntimeContext runtimeContext) {
        return waitForResults(timeoutSeconds, null, null, runtimeContext);
    }

    private ToolResultBlock waitForTaskBarrier(
            Integer timeoutSeconds,
            RuntimeContext runtimeContext,
            String sessionId,
            List<String> explicitTaskIds,
            AtomicInteger emptyWaits)
            throws InterruptedException {
        if (taskRepository == null) {
            return ToolResultBlock.text(
                            "Cannot wait for task completion: task repository is unavailable. "
                                    + "Use task_list or task_output(block=false) to check"
                                    + " status.")
                    .withState(ToolResultState.ERROR);
        }

        if (!explicitTaskIds.isEmpty()) {
            List<String> missingTaskIds =
                    explicitTaskIds.stream()
                            .filter(
                                    id ->
                                            taskRepository.getTask(runtimeContext, sessionId, id)
                                                    == null)
                            .toList();
            if (!missingTaskIds.isEmpty()) {
                return ToolResultBlock.text(
                                "status: not_found\nCannot wait: unknown task_ids "
                                        + missingTaskIds
                                        + ". No running work is implied. Use task_list to find"
                                        + " valid task IDs.")
                        .withState(ToolResultState.ERROR);
            }
        }

        List<String> waitSet =
                !explicitTaskIds.isEmpty()
                        ? explicitTaskIds
                        : snapshotNonTerminalTaskIds(runtimeContext, sessionId);
        if (waitSet.isEmpty()) {
            log.info(
                    "wait_async_results: wait_all snapshot empty,"
                            + " returning immediately, session={}",
                    sessionId);
            emptyWaits.set(0);
            return ToolResultBlock.success(
                    "No running background tasks found at wait start. Continue reasoning, "
                            + "or use task_list to review existing task status.");
        }

        ToolResultBlock completed = completeTaskBarrierIfReady(runtimeContext, sessionId, waitSet);
        if (completed != null) {
            emptyWaits.set(0);
            return completed;
        }

        ToolResultBlock rejected = rejectIfWaitBudgetExhausted(sessionId, emptyWaits);
        if (rejected != null) {
            return rejected;
        }

        int timeout = normalizeTimeout(timeoutSeconds, sessionId);
        log.info(
                "wait_async_results: waiting up to {}s for task barrier (event-driven),"
                        + " session={}, tasks={}",
                timeout,
                sessionId,
                waitSet);

        long deadlineMs = currentTimeMillis.getAsLong() + (timeout * 1000L);
        while (true) {
            completed = completeTaskBarrierIfReady(runtimeContext, sessionId, waitSet);
            if (completed != null) {
                emptyWaits.set(0);
                return completed;
            }
            long remainingMs = deadlineMs - currentTimeMillis.getAsLong();
            if (remainingMs <= 0) {
                break;
            }
            // 仅等待初始 waitSet 中的任务；每次信号或时间片结束后重新读取终态和结果。
            for (String taskId : waitSet) {
                BackgroundTask task = taskRepository.getTask(runtimeContext, sessionId, taskId);
                if (task == null) {
                    return ToolResultBlock.error(
                            "Cannot wait: task_id "
                                    + taskId
                                    + " is no longer available. Use task_list to refresh task"
                                    + " status.");
                }
                if (!task.getTaskStatus().isTerminal()) {
                    remainingMs = deadlineMs - currentTimeMillis.getAsLong();
                    if (remainingMs > 0) {
                        taskWaiter.await(task, Math.min(pollIntervalMs, remainingMs));
                    }
                    break;
                }
            }
        }

        completed = completeTaskBarrierIfReady(runtimeContext, sessionId, waitSet);
        if (completed != null) {
            emptyWaits.set(0);
            return completed;
        }

        int emptyCount = emptyWaits.incrementAndGet();
        log.info(
                "wait_async_results: timeout after {}s waiting for tasks {}"
                        + " (consecutive empty waits: {}), session={}",
                timeout,
                waitSet,
                emptyCount,
                sessionId);
        return ToolResultBlock.success(
                "Timeout after "
                        + timeout
                        + "s. Requested background tasks are not all terminal yet (empty wait "
                        + emptyCount
                        + "/"
                        + MAX_CONSECUTIVE_EMPTY_WAITS
                        + "). "
                        + "Use task_list to check task status, or task_output(block=false) to"
                        + " poll without blocking.");
    }

    private ToolResultBlock completeTaskBarrierIfReady(
            RuntimeContext runtimeContext, String sessionId, List<String> waitSet) {
        List<BackgroundTask> terminalTasks = new ArrayList<>(waitSet.size());
        for (String taskId : waitSet) {
            BackgroundTask task = taskRepository.getTask(runtimeContext, sessionId, taskId);
            if (task == null) {
                log.info(
                        "wait_async_results: task barrier missing task {}, session={}",
                        taskId,
                        sessionId);
                return ToolResultBlock.text(
                                "Cannot wait: task_id "
                                        + taskId
                                        + " is no longer available. Use task_list to refresh"
                                        + " task status.")
                        .withState(ToolResultState.ERROR);
            }
            if (!task.getTaskStatus().isTerminal()) {
                return null;
            }
            terminalTasks.add(task);
        }
        log.info(
                "wait_async_results: task barrier complete, tasks={}, session={}",
                waitSet,
                sessionId);
        return formatBarrierResults(runtimeContext, sessionId, terminalTasks);
    }

    /**
     * Embeds each terminal task's status/result in the tool return so the parent can continue
     * without waiting for inbox push-back. Marks tasks delivered to avoid duplicate reminders.
     */
    private ToolResultBlock formatBarrierResults(
            RuntimeContext runtimeContext, String sessionId, List<BackgroundTask> tasks) {
        StringBuilder sb = new StringBuilder();
        sb.append("All requested background tasks are terminal. Results are included below.")
                .append('\n');
        for (BackgroundTask task : tasks) {
            task.updateLastCheckedAt();
            if (task.isCompleted() && task.getTaskStatus().isTerminal()) {
                try {
                    taskRepository.markDelivered(runtimeContext, sessionId, task.getTaskId());
                } catch (RuntimeException ignore) {
                    // Best-effort: failure only risks a redundant push reminder.
                }
            }
            sb.append("---").append('\n');
            sb.append("task_id: ").append(task.getTaskId()).append('\n');
            if (task.getAgentId() != null) {
                sb.append("agent_id: ").append(task.getAgentId()).append('\n');
            }
            sb.append("status: ").append(task.getStatus()).append('\n');
            if (task.getResult() != null) {
                sb.append("Result:\n").append(task.getResult()).append('\n');
            } else if (task.getError() != null) {
                Exception err = task.getError();
                sb.append("Error:\n").append(err.getMessage()).append('\n');
                if (err.getCause() != null) {
                    sb.append("Cause: ").append(err.getCause().getMessage()).append('\n');
                }
            } else {
                sb.append("Task completed with no result.").append('\n');
            }
        }
        return ToolResultBlock.text(sb.toString().trim()).withState(barrierState(tasks));
    }

    private static ToolResultState barrierState(List<BackgroundTask> tasks) {
        boolean interrupted = false;
        for (BackgroundTask task : tasks) {
            if (task.getTaskStatus() == TaskStatus.FAILED) {
                return ToolResultState.ERROR;
            }
            if (task.getTaskStatus() == TaskStatus.CANCELLED) {
                interrupted = true;
            }
        }
        return interrupted ? ToolResultState.INTERRUPTED : ToolResultState.SUCCESS;
    }

    private ToolResultBlock rejectIfWaitBudgetExhausted(
            String sessionId, AtomicInteger emptyWaits) {
        if (emptyWaits.get() < MAX_CONSECUTIVE_EMPTY_WAITS) {
            return null;
        }
        Boolean hasMessages = messageBus.inboxHasMessages(sessionId).block();
        if (Boolean.TRUE.equals(hasMessages)) {
            log.info(
                    "wait_async_results: budget was exhausted but inbox now has messages,"
                            + " resetting counter, session={}",
                    sessionId);
            emptyWaits.set(0);
            return ToolResultBlock.success(
                    "Async results have arrived (inbox-any: at least one message). "
                            + "Continue reasoning — the results will be injected into your"
                            + " context automatically. Prefer wait_async_results(task_ids=...)"
                            + " or wait_all=true when you need every task in a group.");
        }
        log.info(
                "wait_async_results: rejected — {} consecutive empty waits reached, session={}",
                emptyWaits.get(),
                sessionId);
        return ToolResultBlock.success(
                "Wait budget exhausted: you have already waited "
                        + emptyWaits.get()
                        + " times without receiving results. "
                        + "Do NOT call wait_async_results again. Instead use task_list to"
                        + " check task status, or task_output(block=false) to poll for"
                        + " results without blocking.");
    }

    private int normalizeTimeout(Integer timeoutSeconds, String sessionId) {
        int raw =
                timeoutSeconds != null && timeoutSeconds > 0
                        ? timeoutSeconds
                        : DEFAULT_TIMEOUT_SECONDS;
        int timeout = Math.min(raw, MAX_TIMEOUT_SECONDS);
        if (raw > MAX_TIMEOUT_SECONDS) {
            log.info(
                    "wait_async_results: clamped timeout from {}s to {}s, session={}",
                    raw,
                    timeout,
                    sessionId);
        }
        return timeout;
    }

    /**
     * 等待后台任务的完成信号，每轮最多占用一个轮询时间片，避免慢任务挡住其他 inbox 消息。
     *
     * @return false 表示无任务事件源，调用方需使用 sleeper 等待外部消息
     */
    private boolean waitForAnyTaskSignal(RuntimeContext rc, String sessionId, long remainingMs)
            throws InterruptedException {
        if (taskRepository == null) {
            return false;
        }
        long deadlineMs = currentTimeMillis.getAsLong() + Math.min(pollIntervalMs, remainingMs);
        List<BackgroundTask> running =
                taskRepository.listTasks(rc, sessionId, null).stream()
                        .filter(t -> !t.getTaskStatus().isTerminal())
                        .toList();
        if (running.isEmpty()) {
            return false;
        }
        for (int i = 0; i < running.size(); i++) {
            long budget = deadlineMs - currentTimeMillis.getAsLong();
            if (budget <= 0) {
                break;
            }
            BackgroundTask task = running.get(i);
            // 在本轮时间片内分配等待预算，任务完成时立即唤醒。
            taskWaiter.await(task, Math.max(1L, budget / (running.size() - i)));
            if (task.getTaskStatus().isTerminal()) {
                log.info(
                        "wait_async_results: task {} reached terminal state, waking up,"
                                + " session={}",
                        task.getTaskId(),
                        sessionId);
                break;
            }
        }
        return true;
    }

    private void sleepForPollInterval(long remainingMs) throws InterruptedException {
        sleeper.sleep(Math.min(pollIntervalMs, remainingMs));
    }

    private List<String> parseTaskIds(String taskIds) {
        if (taskIds == null || taskIds.isBlank()) {
            return List.of();
        }
        List<String> parsed = new ArrayList<>();
        for (String raw : taskIds.split(",")) {
            String id = raw.trim();
            if (!id.isEmpty()) {
                parsed.add(id);
            }
        }
        return parsed;
    }

    private List<String> snapshotNonTerminalTaskIds(RuntimeContext rc, String sessionId) {
        Collection<BackgroundTask> tasks = taskRepository.listTasks(rc, sessionId, null);
        return tasks.stream()
                .filter(t -> !t.getTaskStatus().isTerminal())
                .map(BackgroundTask::getTaskId)
                .toList();
    }
}
