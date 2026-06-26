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
package io.agentscope.harness.agent.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.sandbox.BaseSandboxFilesystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@link WorkspaceManager#listKnowledgeFiles} skips the sandbox glob (which returns
 * markdown-wrapped, non-path text over MCP) and falls back to the local knowledge/ directory,
 * while non-sandbox filesystems still union both sources.
 */
class WorkspaceManagerKnowledgeFilesTest {

    private static final RuntimeContext RC = RuntimeContext.empty();

    @Test
    void sandboxFilesystemSkipsGlobAndUsesLocalKnowledgeDir(@TempDir Path tmp) throws IOException {
        // Local knowledge file exists; sandbox glob would return junk but must NOT be called.
        Path knowledgeDir = tmp.resolve("knowledge");
        Files.createDirectories(knowledgeDir);
        Files.writeString(knowledgeDir.resolve("guide.md"), "# Guide\n");

        RecordingSandboxFilesystem sandboxFs = new RecordingSandboxFilesystem();
        try (WorkspaceManager wm = new WorkspaceManager(tmp, sandboxFs)) {
            List<Path> files = wm.listKnowledgeFiles(RC);

            // Sandbox glob must never have been invoked.
            assertTrue(sandboxFs.globInvocations.isEmpty(), "sandbox glob must be skipped");
            // The local knowledge file is still listed.
            assertEquals(1, files.size());
            assertTrue(files.get(0).endsWith("guide.md"));
        }
    }

    @Test
    void sandboxFilesystemWithoutLocalDirReturnsEmpty(@TempDir Path tmp) throws IOException {
        // No local knowledge/ dir AND sandbox glob skipped -> empty list, no WARNs.
        RecordingSandboxFilesystem sandboxFs = new RecordingSandboxFilesystem();
        try (WorkspaceManager wm = new WorkspaceManager(tmp, sandboxFs)) {
            List<Path> files = wm.listKnowledgeFiles(RC);

            assertTrue(sandboxFs.globInvocations.isEmpty());
            assertTrue(files.isEmpty());
        }
    }

    @Test
    void plainFilesystemStillUnionsGlobAndLocalDir(@TempDir Path tmp) throws IOException {
        // Non-sandbox filesystems keep the original union (glob + local) behaviour.
        Path knowledgeDir = tmp.resolve("knowledge");
        Files.createDirectories(knowledgeDir);
        Files.writeString(knowledgeDir.resolve("local.md"), "# Local\n");

        RecordingPlainFilesystem plainFs = new RecordingPlainFilesystem();
        try (WorkspaceManager wm = new WorkspaceManager(tmp, plainFs)) {
            List<Path> files = wm.listKnowledgeFiles(RC);

            // Glob was invoked (once, for the knowledge dir).
            assertEquals(1, plainFs.globInvocations.size());
            // Both the glob-sourced and local-sourced files appear.
            assertEquals(2, files.size());
            List<String> names =
                    files.stream().map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(List.of("from-glob.md", "local.md"), names);
        }
    }

    /** A minimal sandbox filesystem that records glob calls (they should never happen). */
    private static final class RecordingSandboxFilesystem extends BaseSandboxFilesystem {
        final List<String> globInvocations = new ArrayList<>();

        @Override
        public String id() {
            return "recording-sandbox";
        }

        @Override
        public ExecuteResponse execute(RuntimeContext rc, String command, Integer timeoutSeconds) {
            return new ExecuteResponse("", 0, false);
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext rc, List<Map.Entry<String, byte[]>> files) {
            return List.of();
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(RuntimeContext rc, List<String> paths) {
            return List.of();
        }

        @Override
        public GlobResult glob(RuntimeContext rc, String pattern, String path) {
            globInvocations.add(pattern + "@" + path);
            return GlobResult.success(List.of());
        }
    }

    /** A plain (non-sandbox) filesystem that returns one fake knowledge file from glob. */
    private static final class RecordingPlainFilesystem implements AbstractFilesystem {
        final List<String> globInvocations = new ArrayList<>();

        @Override
        public GlobResult glob(RuntimeContext rc, String pattern, String path) {
            globInvocations.add(pattern + "@" + path);
            return GlobResult.success(List.of(FileInfo.ofFile("knowledge/from-glob.md", 0, "")));
        }

        @Override
        public LsResult ls(RuntimeContext rc, String path) {
            return LsResult.success(List.of());
        }

        @Override
        public ReadResult read(RuntimeContext rc, String filePath, int offset, int limit) {
            return ReadResult.fail("not implemented");
        }

        @Override
        public WriteResult write(RuntimeContext rc, String filePath, String content) {
            return WriteResult.ok(filePath);
        }

        @Override
        public EditResult edit(
                RuntimeContext rc,
                String filePath,
                String oldString,
                String newString,
                boolean replaceAll) {
            return EditResult.fail("not implemented");
        }

        @Override
        public GrepResult grep(RuntimeContext rc, String pattern, String path, String glob) {
            return GrepResult.success(List.of());
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext rc, List<Map.Entry<String, byte[]>> files) {
            return List.of();
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(RuntimeContext rc, List<String> paths) {
            return List.of();
        }

        @Override
        public WriteResult delete(RuntimeContext rc, String path) {
            return WriteResult.ok(path);
        }

        @Override
        public WriteResult move(RuntimeContext rc, String fromPath, String toPath) {
            return WriteResult.ok(toPath);
        }

        @Override
        public boolean exists(RuntimeContext rc, String path) {
            return false;
        }
    }
}
