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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxFileTransfer;
import io.agentscope.harness.agent.sandbox.SandboxState;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    void uploadFiles_delegatesToSandboxUploadFile() {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeSandbox sandbox = new FakeSandbox();
        filesystem.setSandbox(sandbox);

        byte[] content = "hello".getBytes();
        List<FileUploadResponse> responses =
                filesystem.uploadFiles(
                        RT, List.of(java.util.Map.entry("/tmp/upload.txt", content)));

        assertEquals("/tmp/upload.txt", sandbox.lastUploadPath);
        assertArrayEquals(content, sandbox.lastUploadContent);
        assertEquals(1, responses.size());
        assertTrue(responses.get(0).isSuccess());
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
    void uploadFiles_fallsBackToSandboxUploadFileForUnsupportedPaths() {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeTransferSandbox sandbox = new FakeTransferSandbox("/workspace");
        filesystem.setSandbox(sandbox);

        List<FileUploadResponse> responses =
                filesystem.uploadFiles(RT, List.of(Map.entry("/etc/other.txt", new byte[] {1})));

        assertTrue(responses.get(0).isSuccess());
        // supportsFileTransfer 返回 false 时不走原生传输分支，回退到 Sandbox.uploadFile
        // （FakeTransferSandbox.uploadFile 即 Sandbox.uploadFile 实现，直接写入 uploaded）
        assertArrayEquals(new byte[] {1}, sandbox.uploaded.get("/etc/other.txt"));
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
    }

    private static class BaseFakeSandbox implements Sandbox {

        protected String lastCommand;
        protected String lastDownloadPath;
        protected byte[] downloadResult;
        protected Throwable downloadError;
        protected String lastUploadPath;
        protected byte[] lastUploadContent;

        protected BaseFakeSandbox(ExecResult execResult) {}

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
            return null;
        }

        @Override
        public ExecResult exec(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            this.lastCommand = command;
            return new ExecResult(0, "", "", false);
        }

        @Override
        public InputStream persistWorkspace() {
            return InputStream.nullInputStream();
        }

        @Override
        public void hydrateWorkspace(InputStream archive) {}

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
}
