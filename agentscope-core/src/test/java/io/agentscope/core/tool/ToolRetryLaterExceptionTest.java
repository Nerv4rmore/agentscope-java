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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@DisplayName("ToolRetryLaterException Tests")
class ToolRetryLaterExceptionTest {

    @Test
    @DisplayName("Should default message and expose null reason / empty metadata")
    void testDefaults() {
        ToolRetryLaterException exception = new ToolRetryLaterException();

        assertEquals("Tool execution deferred for retry", exception.getMessage());
        assertNull(exception.getReason());
        assertTrue(exception.getMetadata().isEmpty());
    }

    @Test
    @DisplayName("Should carry reason and an immutable metadata payload")
    void testReasonAndMetadata() {
        ToolRetryLaterException exception =
                new ToolRetryLaterException(
                        "credits.insufficient", Map.of("balance", 3L, "required", 50L));

        assertEquals("credits.insufficient", exception.getReason());
        assertEquals("credits.insufficient", exception.getMessage());
        assertEquals(3L, exception.getMetadata().get("balance"));
        assertEquals(50L, exception.getMetadata().get("required"));
    }

    @Test
    @DisplayName("Should build a retry-later marker block that is not a suspend marker")
    void testRetryLaterMarkerBlock() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("tool-1")
                        .name("generate_image")
                        .input(Map.of("prompt", "a cat"))
                        .build();

        ToolRetryLaterException exception =
                new ToolRetryLaterException(
                        "credits.insufficient", Map.of("balance", 0L, "required", 20L));
        ToolResultBlock marker = ToolResultBlock.retryLater(toolUse, exception);

        assertEquals("tool-1", marker.getId());
        assertEquals("generate_image", marker.getName());
        assertTrue(marker.isRetryLater());
        // Orthogonal to the suspend (external-result) marker.
        assertFalse(marker.isSuspended());
        assertEquals("credits.insufficient", marker.getRetryPayload().get("reason"));
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) marker.getRetryPayload().get("metadata");
        assertEquals(0L, meta.get("balance"));
        assertEquals(20L, meta.get("required"));
    }

    @Test
    @DisplayName("Plain results are neither retry-later nor suspended")
    void testPlainResultIsNotRetry() {
        ToolResultBlock normal = ToolResultBlock.text("ok");

        assertFalse(normal.isRetryLater());
        assertFalse(normal.isSuspended());
        assertTrue(normal.getRetryPayload().isEmpty());
    }
}
