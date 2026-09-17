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
package io.agentscope.extensions.model.openai.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.extensions.model.openai.dto.ResponsesInputItem;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResponsesMessageConverterTest {

    private static final String IMAGE_URL = "https://sandbox.example/files?path=%2Fpage-1.jpg";

    private final ResponsesMessageConverter converter =
            new ResponsesMessageConverter(
                    msg -> "extracted text", blocks -> "the returned image can be found at: "
                            + IMAGE_URL);

    @Test
    @SuppressWarnings("unchecked")
    void toolResultWithImage_promotesImageIntoFollowingUserItem() {
        Msg toolMsg = Msg.builder().role(MsgRole.TOOL).content(resultWithImage()).build();

        List<ResponsesInputItem> items = converter.convertToItems(toolMsg);

        assertEquals(2, items.size(), () -> items.toString());
        assertEquals("function_call_output", items.get(0).getType());

        ResponsesInputItem promoted = items.get(1);
        assertEquals("message", promoted.getType());
        assertEquals("user", promoted.getRole());
        List<Map<String, Object>> parts = (List<Map<String, Object>>) promoted.getContent();
        Map<String, Object> imagePart =
                parts.stream()
                        .filter(p -> "input_image".equals(p.get("type")))
                        .findFirst()
                        .orElse(null);
        assertTrue(parts.stream().anyMatch(p -> "input_text".equals(p.get("type"))), () -> "" + parts);
        assertEquals(IMAGE_URL, imagePart == null ? null : imagePart.get("image_url"));
    }

    @Test
    void textOnlyToolResult_staysOneOutputItem() {
        Msg toolMsg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.of(
                                        "call-2",
                                        "read_file",
                                        List.of(text("file contents"))))
                        .build();

        assertEquals(1, converter.convertToItems(toolMsg).size());
    }

    private ToolResultBlock resultWithImage() {
        List<ContentBlock> output =
                List.of(
                        text("[{\"download_url\":\"" + IMAGE_URL + "\",\"is_image\":true}]"),
                        ImageBlock.builder()
                                .source(
                                        URLSource.builder()
                                                .url(IMAGE_URL)
                                                .mimeType("image/jpeg")
                                                .build())
                                .build());
        return ToolResultBlock.of("call-1", "get_file_public_url", output);
    }

    private static TextBlock text(String value) {
        return TextBlock.builder().text(value).build();
    }
}
