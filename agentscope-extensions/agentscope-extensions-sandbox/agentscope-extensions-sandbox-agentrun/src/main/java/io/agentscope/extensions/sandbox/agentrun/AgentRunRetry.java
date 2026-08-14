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
package io.agentscope.extensions.sandbox.agentrun;

import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.Callable;

/** Simple bounded retry for transient AgentRun HTTP failures (mirrors {@code DaytonaRetry}). */
final class AgentRunRetry {

    /**
     * Whether the wrapped call may be safely re-sent after a socket timeout.
     *
     * <p>A read timeout is ambiguous: the request reached the server, but the response was lost. For
     * a GET (or a POST keyed by a caller-supplied id) re-sending is harmless. For a command
     * execution it is not — the command may already be running, so a retry runs it a second time.
     */
    enum Idempotency {
        /** Safe to re-send on a socket timeout. */
        IDEMPOTENT,
        /** Must NOT be re-sent on a socket timeout — fail fast instead. */
        NON_IDEMPOTENT
    }

    private AgentRunRetry() {}

    static <T> T withRetries(int maxAttempts, Callable<T> call) throws IOException {
        return withRetries(maxAttempts, Idempotency.IDEMPOTENT, call);
    }

    static <T> T withRetries(int maxAttempts, Idempotency idempotency, Callable<T> call)
            throws IOException {
        int n = Math.max(1, maxAttempts);
        IOException last = null;
        for (int i = 0; i < n; i++) {
            try {
                return call.call();
            } catch (SandboxException e) {
                if (!retryable(e) || i == n - 1) {
                    throw e;
                }
                sleepBackoff(i);
            } catch (IOException e) {
                last = e;
                if (i == n - 1 || !retryableIo(e, idempotency)) {
                    throw e;
                }
                sleepBackoff(i);
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IOException("retry exhausted");
    }

    private static boolean retryable(Exception e) {
        String m = e.getMessage() != null ? e.getMessage() : "";
        return m.contains("HTTP 429") || m.contains("HTTP 503") || m.contains("HTTP 502");
    }

    /**
     * Decides whether an {@link IOException} is worth another attempt.
     *
     * <p>A timeout on a {@code NON_IDEMPOTENT} call is never retried, for two reasons observed in
     * production:
     *
     * <ul>
     *   <li><b>Correctness</b> — the command may already be executing in the sandbox. Re-sending
     *       runs it twice; for a script with side effects (downloads, file writes) that is a silent
     *       corruption.
     *   <li><b>Latency</b> — each attempt burns the full read timeout. With the default 120s read
     *       timeout and 3 attempts, one unresponsive request blocked the caller for 361s while the
     *       gateway had already abandoned the work at its own 30s cap.
     * </ul>
     *
     * <p>The check is on {@link InterruptedIOException} because it covers both timeout flavours
     * OkHttp raises — verified empirically against okhttp 5.3.2:
     *
     * <ul>
     *   <li>{@code readTimeout} expiry → {@link SocketTimeoutException} (a subclass of
     *       {@code InterruptedIOException});
     *   <li>{@code callTimeout} expiry → a plain {@code InterruptedIOException}, which is
     *       <em>not</em> a {@code SocketTimeoutException}.
     * </ul>
     *
     * Matching only {@code SocketTimeoutException} would silently let call-timeout failures retry,
     * defeating the bounded exec timeout. A genuine thread interrupt also lands here, and failing
     * fast is correct for that too.
     *
     * <p>Connect timeouts surface as {@code SocketTimeoutException} as well and would be safe to
     * re-send, but OkHttp does not distinguish them by type. Failing fast is the safer default: the
     * caller still sees the error and can decide. Non-timeout transport failures (connection refused
     * or reset) never reached the server, so they remain retryable.
     */
    private static boolean retryableIo(IOException e, Idempotency idempotency) {
        if (idempotency == Idempotency.IDEMPOTENT) {
            return true;
        }
        return !(e instanceof InterruptedIOException);
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(200L * (attempt + 1L));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
