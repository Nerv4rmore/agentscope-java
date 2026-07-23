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
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import java.io.InputStream;
import java.util.List;
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
        List<io.agentscope.harness.agent.filesystem.model.FileUploadResponse> responses =
                filesystem.uploadFiles(
                        RT, List.of(java.util.Map.entry("/tmp/upload.txt", content)));

        assertEquals("/tmp/upload.txt", sandbox.lastUploadPath);
        assertArrayEquals(content, sandbox.lastUploadContent);
        assertEquals(1, responses.size());
        assertTrue(responses.get(0).isSuccess());
    }

    private static final class FakeSandbox implements Sandbox {

        private String lastDownloadPath;
        private byte[] downloadResult;
        private Throwable downloadError;
        private String lastUploadPath;
        private byte[] lastUploadContent;

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
            return new ExecResult(0, "", "", false);
        }

        @Override
        public InputStream persistWorkspace() {
            return InputStream.nullInputStream();
        }

        @Override
        public void hydrateWorkspace(InputStream archive) {}

        @Override
        public void uploadFile(String path, byte[] content) {
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
