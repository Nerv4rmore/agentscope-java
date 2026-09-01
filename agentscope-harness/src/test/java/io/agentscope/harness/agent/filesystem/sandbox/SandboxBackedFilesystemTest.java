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
package io.agentscope.harness.agent.filesystem.sandbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxFileTransfer;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;

class SandboxBackedFilesystemTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    @Test
    void downloadFiles_returnsBytesFromSandboxDownloadFile() {
        byte[] expected = new byte[] {1, 2, 3, 4, 5, 6};
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeSandbox sandbox = new FakeSandbox();
        sandbox.downloadResult = expected;
        filesystem.setSandbox(sandbox);

        List<FileDownloadResponse> responses =
                filesystem.downloadFiles(RT, List.of("/tmp/data.bin"));

        assertEquals("/tmp/data.bin", sandbox.lastDownloadPath);
        assertEquals(1, responses.size());
        assertTrue(responses.get(0).isSuccess());
        assertEquals("/tmp/data.bin", responses.get(0).path());
        assertArrayEquals(expected, responses.get(0).content());
    }

    @Test
    void downloadFiles_returnsEmptyBytesWhenDownloadReturnsEmpty() {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeSandbox sandbox = new FakeSandbox();
        sandbox.downloadResult = new byte[0];
        filesystem.setSandbox(sandbox);

        List<FileDownloadResponse> responses =
                filesystem.downloadFiles(RT, List.of("/tmp/empty.bin"));

        assertEquals("/tmp/empty.bin", sandbox.lastDownloadPath);
        assertEquals(1, responses.size());
        assertTrue(responses.get(0).isSuccess());
        assertEquals("/tmp/empty.bin", responses.get(0).path());
        assertArrayEquals(new byte[0], responses.get(0).content());
    }

    @Test
    void downloadFiles_returnsFailureWhenDownloadThrows() {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeSandbox sandbox = new FakeSandbox();
        sandbox.downloadError =
                new io.agentscope.harness.agent.sandbox.SandboxException.ExecException(
                        1, "", "boom");
        filesystem.setSandbox(sandbox);

        List<FileDownloadResponse> responses =
                filesystem.downloadFiles(RT, List.of("/tmp/fail.bin"));

        assertEquals("/tmp/fail.bin", sandbox.lastDownloadPath);
        assertEquals(1, responses.size());
        assertTrue(!responses.get(0).isSuccess());
        assertEquals("/tmp/fail.bin", responses.get(0).path());
    }

    @Test
    void uploadFiles_prefersNativeTransferWhenSupported() {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeTransferSandbox sandbox = new FakeTransferSandbox("/workspace");
        filesystem.setSandbox(sandbox);

        List<FileUploadResponse> responses =
                filesystem.uploadFiles(
                        RT, List.of(Map.entry("/workspace/a.txt", new byte[] {7, 8})));

        assertTrue(responses.get(0).isSuccess());
        assertArrayEquals(new byte[] {7, 8}, sandbox.uploaded.get("/workspace/a.txt"));
        // 原生传输命中后不应回退到 Sandbox.uploadFile
        assertEquals(null, sandbox.lastUploadPath);
    }

    @Test
    void uploadFiles_resolvesRelativePathForNativeTransfer() {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeTransferSandbox sandbox = new FakeTransferSandbox("/workspace");
        filesystem.setSandbox(sandbox);

        List<FileUploadResponse> responses =
                filesystem.uploadFiles(
                        RT,
                        List.of(
                                Map.entry(
                                        "agents/通用智能助手/sessions/sessions.json",
                                        new byte[] {3, 1, 4})));

        assertTrue(responses.get(0).isSuccess());
        assertArrayEquals(
                new byte[] {3, 1, 4},
                sandbox.uploaded.get("/workspace/agents/通用智能助手/sessions/sessions.json"));
        assertNull(sandbox.lastCommand);
        assertEquals(0, sandbox.hydrateCalls);
    }

    @Test
    void uploadFiles_rejectsNullContentOnNativeTransferPath() {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeTransferSandbox sandbox = new FakeTransferSandbox("/workspace");
        filesystem.setSandbox(sandbox);

        List<FileUploadResponse> responses =
                filesystem.uploadFiles(
                        RT, List.of(new AbstractMap.SimpleImmutableEntry<>("agents/a.txt", null)));

        assertTrue(!responses.get(0).isSuccess());
        assertEquals("File content must not be null", responses.get(0).error());
        assertTrue(sandbox.uploaded.isEmpty());
        assertEquals(0, sandbox.hydrateCalls);
    }

    @Test
    void uploadFiles_fallsBackToHydrationWhenRootUnavailable() throws Exception {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeTransferSandbox sandbox = new FakeTransferSandbox("/workspace");
        sandbox.state.setWorkspaceSpec(null);
        filesystem.setSandbox(sandbox);
        byte[] content = new byte[] {5};

        List<FileUploadResponse> responses =
                filesystem.uploadFiles(RT, List.of(Map.entry("agents/a.jsonl", content)));

        assertTrue(responses.get(0).isSuccess());
        assertTrue(sandbox.uploaded.isEmpty());
        assertEquals(1, sandbox.hydrateCalls);
        assertArchive(sandbox.hydratedArchive, "agents/a.jsonl", content);
    }

    @Test
    void uploadFiles_usesTarHydrationForUnsupportedNativePaths() throws Exception {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        // File API rooted outside the workspace: resolved paths stay unsupported natively.
        FakeTransferSandbox sandbox = new FakeTransferSandbox("/data");
        filesystem.setSandbox(sandbox);
        byte[] content = "session-data".getBytes(StandardCharsets.UTF_8);

        List<FileUploadResponse> responses =
                filesystem.uploadFiles(RT, List.of(Map.entry("./agents/session.jsonl", content)));

        assertTrue(responses.get(0).isSuccess());
        assertTrue(sandbox.uploaded.isEmpty());
        assertNull(sandbox.lastCommand);
        assertEquals(1, sandbox.hydrateCalls);
        assertArchive(sandbox.hydratedArchive, "agents/session.jsonl", content);
    }

    @Test
    void uploadFiles_streamsLargeAbsoluteWorkspaceFileWithoutExec() throws Exception {
        byte[] content = new byte[256 * 1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 251);
        }
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeSandbox sandbox = new FakeSandbox(new ExecResult(0, "", "", false));
        sandbox.workspaceSpec.setRoot("\\workspace\\\\");
        filesystem.setSandbox(sandbox);

        List<FileUploadResponse> responses =
                filesystem.uploadFiles(
                        RT,
                        List.of(Map.entry("\\workspace\\agents\\large-session.jsonl", content)));

        assertTrue(responses.get(0).isSuccess());
        assertNull(sandbox.lastCommand);
        assertEquals(1, sandbox.hydrateCalls);
        assertArchive(sandbox.hydratedArchive, "agents/large-session.jsonl", content);
    }

    @Test
    void uploadFiles_reportsHydrationFailure() {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeSandbox sandbox = new FakeSandbox(new ExecResult(0, "", "", false));
        sandbox.failHydration = true;
        filesystem.setSandbox(sandbox);

        List<FileUploadResponse> responses =
                filesystem.uploadFiles(RT, List.of(Map.entry("agents/a.jsonl", new byte[] {1})));

        assertTrue(!responses.get(0).isSuccess());
        assertEquals("hydrate down", responses.get(0).error());
    }

    @Test
    void uploadFiles_rejectsNullContentAndUnsafePaths() {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeSandbox sandbox = new FakeSandbox(new ExecResult(0, "", "", false));
        filesystem.setSandbox(sandbox);

        List<Map.Entry<String, byte[]>> files =
                List.of(
                        new AbstractMap.SimpleImmutableEntry<>("agents/null.jsonl", null),
                        Map.entry("../outside.jsonl", new byte[] {1}),
                        Map.entry("/etc/outside.jsonl", new byte[] {2}),
                        Map.entry("/workspace", new byte[] {3}),
                        Map.entry("./", new byte[] {4}));
        List<FileUploadResponse> responses = filesystem.uploadFiles(RT, files);

        assertEquals(5, responses.size());
        assertTrue(responses.stream().noneMatch(FileUploadResponse::isSuccess));
        assertEquals(0, sandbox.hydrateCalls);
    }

    @Test
    void uploadFiles_requiresWorkspaceStateForAbsolutePath() {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeSandbox sandbox = new FakeSandbox(new ExecResult(0, "", "", false));
        sandbox.state = null;
        filesystem.setSandbox(sandbox);

        List<FileUploadResponse> responses =
                filesystem.uploadFiles(RT, List.of(Map.entry("/workspace/a.txt", new byte[] {1})));

        assertTrue(!responses.get(0).isSuccess());
        assertEquals("Sandbox workspace root is unavailable", responses.get(0).error());
    }

    @Test
    void uploadFiles_rejectsMissingBlankAndRelativeWorkspaceRoots() {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeSandbox sandbox = new FakeSandbox(new ExecResult(0, "", "", false));
        filesystem.setSandbox(sandbox);

        sandbox.state.setWorkspaceSpec(null);
        FileUploadResponse missingSpec =
                filesystem
                        .uploadFiles(RT, List.of(Map.entry("/workspace/a.txt", new byte[] {1})))
                        .get(0);
        sandbox.state.setWorkspaceSpec(sandbox.workspaceSpec);
        sandbox.workspaceSpec.setRoot(" ");
        FileUploadResponse blankRoot =
                filesystem
                        .uploadFiles(RT, List.of(Map.entry("/workspace/b.txt", new byte[] {2})))
                        .get(0);
        sandbox.workspaceSpec.setRoot("workspace");
        FileUploadResponse relativeRoot =
                filesystem
                        .uploadFiles(RT, List.of(Map.entry("/workspace/c.txt", new byte[] {3})))
                        .get(0);

        assertEquals("Sandbox workspace root is unavailable", missingSpec.error());
        assertEquals("Sandbox workspace root is unavailable", blankRoot.error());
        assertEquals("Sandbox workspace root must be absolute: workspace", relativeRoot.error());
        assertEquals(0, sandbox.hydrateCalls);
    }

    @Test
    void uploadFiles_acceptsRootWorkspaceAndDotPrefix() throws Exception {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeSandbox sandbox = new FakeSandbox(new ExecResult(0, "", "", false));
        sandbox.workspaceSpec.setRoot("/");
        filesystem.setSandbox(sandbox);

        List<FileUploadResponse> responses =
                filesystem.uploadFiles(
                        RT, List.of(Map.entry("/agents/root.jsonl", new byte[] {4, 5})));

        assertTrue(responses.get(0).isSuccess());
        assertArchive(sandbox.hydratedArchive, "agents/root.jsonl", new byte[] {4, 5});
    }

    @Test
    void downloadFiles_prefersNativeTransferWhenSupported() {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeTransferSandbox sandbox = new FakeTransferSandbox("/workspace");
        sandbox.uploaded.put("/workspace/b.bin", new byte[] {9, 9});
        filesystem.setSandbox(sandbox);

        List<FileDownloadResponse> responses =
                filesystem.downloadFiles(RT, List.of("/workspace/b.bin"));

        assertTrue(responses.get(0).isSuccess());
        assertArrayEquals(new byte[] {9, 9}, responses.get(0).content());
        // 原生传输命中后不应回退到 Sandbox.downloadFile
        assertEquals(null, sandbox.lastDownloadPath);
    }

    @Test
    void uploadFiles_reportsNativeTransferFailure() {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeTransferSandbox sandbox = new FakeTransferSandbox("/workspace");
        sandbox.failTransfers = true;
        filesystem.setSandbox(sandbox);

        List<FileUploadResponse> responses =
                filesystem.uploadFiles(RT, List.of(Map.entry("/workspace/c.txt", new byte[] {1})));

        assertTrue(!responses.get(0).isSuccess());
        assertEquals("transfer down", responses.get(0).error());
    }

    @Test
    void sharedLease_defersReleaseUntilLastDetachedTaskCompletes() {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        java.util.concurrent.atomic.AtomicInteger releases =
                new java.util.concurrent.atomic.AtomicInteger();

        SandboxBackedFilesystem.SharedLease first = filesystem.retainForAsync();
        SandboxBackedFilesystem.SharedLease second = filesystem.retainForAsync();
        filesystem.requestRelease(releases::incrementAndGet);

        assertEquals(0, releases.get());
        first.close();
        assertEquals(0, releases.get());
        second.close();
        second.close();
        assertEquals(1, releases.get());
        assertThrows(IllegalStateException.class, filesystem::retainForAsync);
    }

    @Test
    void sharedLease_withoutDetachedWork_releasesImmediately() {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        java.util.concurrent.atomic.AtomicInteger releases =
                new java.util.concurrent.atomic.AtomicInteger();

        filesystem.requestRelease(releases::incrementAndGet);
        filesystem.requestRelease(releases::incrementAndGet);

        assertEquals(1, releases.get());
    }

    private static void assertArchive(byte[] archive, String expectedPath, byte[] expectedContent)
            throws IOException {
        try (TarArchiveInputStream tar =
                new TarArchiveInputStream(new ByteArrayInputStream(archive))) {
            TarArchiveEntry entry = tar.getNextTarEntry();
            assertEquals(expectedPath, entry.getName());
            assertArrayEquals(expectedContent, tar.readAllBytes());
            assertNull(tar.getNextTarEntry());
        }
    }

    private static final class FakeTransferSandbox extends BaseFakeSandbox
            implements SandboxFileTransfer {

        private final String rootPrefix;
        private final Map<String, byte[]> uploaded = new HashMap<>();
        private boolean failTransfers;

        private FakeTransferSandbox(String root) {
            super(new ExecResult(0, "", "", false));
            this.rootPrefix = root + "/";
        }

        @Override
        public boolean supportsFileTransfer(String absolutePath) {
            return absolutePath.startsWith(rootPrefix);
        }

        @Override
        public void uploadFile(String absolutePath, byte[] content) throws Exception {
            if (failTransfers) {
                throw new IllegalStateException("transfer down");
            }
            uploaded.put(absolutePath, content);
        }

        @Override
        public byte[] downloadFile(String absolutePath) throws Exception {
            if (failTransfers) {
                throw new IllegalStateException("transfer down");
            }
            return uploaded.get(absolutePath);
        }
    }

    private static final class FakeSandbox extends BaseFakeSandbox {

        private FakeSandbox() {
            super(new ExecResult(0, "", "", false));
        }

        private FakeSandbox(ExecResult execResult) {
            super(execResult);
        }
    }

    private static class BaseFakeSandbox implements Sandbox {

        protected String lastCommand;
        private final ExecResult execResult;
        protected String lastDownloadPath;
        protected byte[] downloadResult;
        protected Throwable downloadError;
        protected String lastUploadPath;
        protected byte[] lastUploadContent;
        protected WorkspaceSpec workspaceSpec = new WorkspaceSpec();
        protected SandboxState state;
        protected byte[] hydratedArchive;
        protected int hydrateCalls;
        protected boolean failHydration;

        protected BaseFakeSandbox(ExecResult execResult) {
            this.execResult = execResult;
            this.state = new TestSandboxState();
            this.state.setWorkspaceSpec(workspaceSpec);
        }

        @Override
        public void start() {}

        @Override
        public void stop() {}

        @Override
        public void shutdown() {}

        @Override
        public void close() {}

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public SandboxState getState() {
            return state;
        }

        @Override
        public ExecResult exec(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            this.lastCommand = command;
            return execResult;
        }

        @Override
        public InputStream persistWorkspace() {
            return InputStream.nullInputStream();
        }

        @Override
        public void hydrateWorkspace(InputStream archive) throws Exception {
            if (failHydration) {
                throw new IOException("hydrate down");
            }
            hydrateCalls++;
            hydratedArchive = archive.readAllBytes();
        }

        @Override
        public void uploadFile(String path, byte[] content) throws Exception {
            this.lastUploadPath = path;
            this.lastUploadContent = content;
        }

        @Override
        public byte[] downloadFile(String path) throws Exception {
            this.lastDownloadPath = path;
            if (downloadError != null) {
                if (downloadError instanceof Exception) {
                    throw (Exception) downloadError;
                }
                throw new RuntimeException(downloadError);
            }
            return downloadResult != null ? downloadResult : new byte[0];
        }
    }

    private static final class TestSandboxState extends SandboxState {}
}
