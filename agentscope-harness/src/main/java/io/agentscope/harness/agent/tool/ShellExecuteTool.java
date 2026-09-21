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
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;

/**
 * Shell execution tool backed by a {@link AbstractSandboxFilesystem}.
 */
public class ShellExecuteTool {

    /**
     * Registered tool name (derived from the {@link #execute} method name).
     */
    public static final String NAME = "execute";

    /** Foreground wait applied when the model omits {@code timeout}. */
    static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /**
     * Upper bound for a single foreground {@code execute}.
     *
     * <p>The backend applies {@code timeout} purely as an HTTP read timeout and never sends it to
     * the sandbox, so an unbounded value would let one tool call stall for as long as the model
     * asks while the process keeps running unobserved. Anything genuinely longer belongs on the
     * background path described in the tool description.
     */
    static final int MAX_TIMEOUT_SECONDS = 600;

    private final AbstractSandboxFilesystem sandbox;

    public ShellExecuteTool(AbstractSandboxFilesystem sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * @param runtimeContext per-call agent runtime injected by the framework (not an LLM argument);
     *                       may be {@code null} when no merged context is available
     */
    @Tool(
            description =
                    "Execute a shell command. Use for git, npm, build, test, and other terminal"
                        + " operations. Returns combined output and exit code. If a dedicated tool"
                        + " exists (e.g., read_file, write_file), you MUST use it instead of shell"
                        + " commands. The call blocks until the command exits or 'timeout' seconds"
                        + " elapse (default 30, max 600). IMPORTANT: reaching the timeout does NOT"
                        + " kill the command — it only stops this call from waiting. The process"
                        + " keeps running in the sandbox, its output is lost, and you get no exit"
                        + " code. So never rely on the timeout to bound work. For anything that may"
                        + " run longer than ~30s (pip install, npm install, large builds, long test"
                        + " suites), start it in the background instead: append ' & echo $!' to get"
                        + " a pid back immediately, redirect its output to a file (e.g. 'cmd >"
                        + " /tmp/out.log 2>&1 & echo $!'), then run 'wait <pid>' in a later call to"
                        + " collect the exit code and output. The file redirect is what makes the"
                        + " result recoverable, so always include it for long work.")
    public String execute(
            RuntimeContext runtimeContext,
            @ToolParam(name = "command", description = "Shell command to execute") String command,
            @ToolParam(
                            name = "working_directory",
                            description =
                                    "Sub-path of the current working directory to run in"
                                        + " (optional). Commands already start in your session"
                                        + " working directory, so omit this for the common case."
                                        + " Must be relative — absolute paths, '~' and '..' are"
                                        + " rejected.",
                            required = false)
                    String workingDirectory,
            @ToolParam(
                            name = "timeout",
                            description =
                                    "Seconds this call waits for the command to finish (default:"
                                        + " 30, max: 600). Raise it when you know the command needs"
                                        + " longer. It bounds only how long this call waits — it"
                                        + " never kills the process — so for open-ended work use"
                                        + " background execution (' & echo $!') instead.",
                            required = false)
                    Integer timeout) {
        String effectiveCommand = command;
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            String wd = workingDirectory.strip();
            if (wd.startsWith("/") || wd.startsWith("~") || wd.contains("..")) {
                return "Error: working_directory must be a relative path within the workspace"
                        + " (absolute paths, '~', and '..' are not allowed). Commands already run"
                        + " in the session working directory, so omit working_directory (or use"
                        + " '.') — an absolute path you may have copied from `pwd` points at that"
                        + " same directory.";
            }
            // 命令始终在 Linux 沙箱内执行，与宿主机 OS 无关，恒用 POSIX cd 语法；
            // 宿主机为 Windows 时若生成 `cd /d "..."` 会导致沙箱 bash 报 "cd: too many arguments"
            effectiveCommand = commandWithWorkingDirectory(wd, command);
        }

        int timeoutSeconds =
                timeout == null || timeout <= 0
                        ? DEFAULT_TIMEOUT_SECONDS
                        : Math.min(timeout, MAX_TIMEOUT_SECONDS);
        ExecuteResponse result = sandbox.execute(runtimeContext, effectiveCommand, timeoutSeconds);

        StringBuilder sb = new StringBuilder();
        sb.append("Exit code: ").append(result.exitCode()).append("\n");
        if (result.output() != null && !result.output().isBlank()) {
            sb.append("\n").append(result.output());
        }
        if (result.truncated()) {
            sb.append("\n(output was truncated)");
        }
        return sb.toString();
    }

    static String commandWithWorkingDirectory(String workingDirectory, String command) {
        return "cd '" + workingDirectory.replace("'", "'\\''") + "' && " + command;
    }
}
