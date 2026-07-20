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

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.dto.ResponsesInputItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Converts AgentScope {@link Msg} objects to Responses API {@link ResponsesInputItem}s.
 *
 * <p>Mirrors {@link OpenAIMessageConverter} but targets the Responses API input item format.
 * The key differences from Chat Completions:
 * <ul>
 *   <li>System messages use role {@code "developer"} (Responses API convention).</li>
 *   <li>Assistant tool calls become separate {@code function_call} input items (not
 *       embedded {@code tool_calls} on the message).</li>
 *   <li>Tool results become {@code function_call_output} input items (not {@code role:"tool"}
 *       messages).</li>
 * </ul>
 *
 * <p>The two {@code Function} dependencies ({@code textExtractor} and
 * {@code toolResultConverter}) are injected from the owning formatter, which inherits them
 * from {@link io.agentscope.core.formatter.AbstractBaseFormatter}.
 */
public class ResponsesMessageConverter {

    private final Function<Msg, String> textExtractor;
    private final Function<List<ContentBlock>, String> toolResultConverter;

    /**
     * Creates a converter with the given extraction functions.
     *
     * @param textExtractor       extracts text content from a Msg
     * @param toolResultConverter converts tool result content blocks to a string
     */
    public ResponsesMessageConverter(
            Function<Msg, String> textExtractor,
            Function<List<ContentBlock>, String> toolResultConverter) {
        this.textExtractor = textExtractor;
        this.toolResultConverter = toolResultConverter;
    }

    /**
     * Convert a single Msg to one or more ResponsesInputItems.
     *
     * <p>An assistant message with both text and tool calls produces multiple items:
     * one {@code message} item for the text, plus one {@code function_call} item per tool call.
     *
     * @param msg the source message
     * @return a list of input items (never null, may be empty)
     */
    public List<ResponsesInputItem> convertToItems(Msg msg) {
        if (msg == null) {
            return List.of();
        }
        MsgRole role = msg.getRole();
        if (role == MsgRole.SYSTEM) {
            return List.of(convertSystemMessage(msg));
        }
        if (role == MsgRole.USER) {
            return List.of(convertUserMessage(msg));
        }
        if (role == MsgRole.ASSISTANT) {
            return convertAssistantMessage(msg);
        }
        if (role == MsgRole.TOOL) {
            return convertToolMessage(msg);
        }
        // Unknown role: treat as user
        return List.of(convertUserMessage(msg));
    }

    private ResponsesInputItem convertSystemMessage(Msg msg) {
        ResponsesInputItem item = new ResponsesInputItem();
        item.setType("message");
        item.setRole("developer");
        item.setContent(textExtractor.apply(msg));
        return item;
    }

    private ResponsesInputItem convertUserMessage(Msg msg) {
        ResponsesInputItem item = new ResponsesInputItem();
        item.setType("message");
        item.setRole("user");

        // 检测消息中是否包含图片块，有则构建 Responses API 多模态 content 数组
        List<ImageBlock> imageBlocks = msg.getContentBlocks(ImageBlock.class);
        if (imageBlocks != null && !imageBlocks.isEmpty()) {
            item.setContent(buildMultimodalContent(msg, imageBlocks));
        } else {
            item.setContent(textExtractor.apply(msg));
        }
        return item;
    }

    /**
     * 构建 Responses API 多模态 content 数组：[{"type":"input_text","text":"..."}, {"type":"input_image","image_url":"..."}]
     *
     * <p>按消息内原始块顺序输出：文本块转为 input_text，图片块转为 input_image。
     * 图片 URL 支持公网链接和 base64 data URI 两种形式。
     */
    private List<Map<String, Object>> buildMultimodalContent(Msg msg, List<ImageBlock> imageBlocks) {
        List<Map<String, Object>> parts = new ArrayList<>();

        // 按原始 content 块顺序遍历，保持文本与图片的相对位置
        List<ContentBlock> allBlocks = msg.getContent();
        if (allBlocks != null) {
            for (ContentBlock block : allBlocks) {
                if (block instanceof TextBlock textBlock) {
                    String text = textBlock.getText();
                    if (text != null && !text.isEmpty()) {
                        Map<String, Object> textPart = new LinkedHashMap<>();
                        textPart.put("type", "input_text");
                        textPart.put("text", text);
                        parts.add(textPart);
                    }
                } else if (block instanceof ImageBlock imageBlock) {
                    Map<String, Object> imagePart = new LinkedHashMap<>();
                    imagePart.put("type", "input_image");
                    imagePart.put("image_url",
                            OpenAIConverterUtils.convertImageSourceToUrl(imageBlock.getSource()));
                    parts.add(imagePart);
                }
            }
        }

        // 兜底：若原始块遍历未产出任何 part（理论上不应发生），用 textExtractor 兜底
        if (parts.isEmpty()) {
            String text = textExtractor.apply(msg);
            if (text != null && !text.isEmpty()) {
                Map<String, Object> textPart = new LinkedHashMap<>();
                textPart.put("type", "input_text");
                textPart.put("text", text);
                parts.add(textPart);
            }
            for (ImageBlock imageBlock : imageBlocks) {
                Map<String, Object> imagePart = new LinkedHashMap<>();
                imagePart.put("type", "input_image");
                imagePart.put("image_url",
                        OpenAIConverterUtils.convertImageSourceToUrl(imageBlock.getSource()));
                parts.add(imagePart);
            }
        }

        return parts;
    }

    /**
     * Convert an assistant message. Produces a {@code message} item for text content
     * (if any), plus a {@code function_call} item for each {@link ToolUseBlock}.
     */
    private List<ResponsesInputItem> convertAssistantMessage(Msg msg) {
        List<ResponsesInputItem> items = new java.util.ArrayList<>();
        String text = textExtractor.apply(msg);
        if (text != null && !text.isEmpty()) {
            ResponsesInputItem messageItem = new ResponsesInputItem();
            messageItem.setType("message");
            messageItem.setRole("assistant");
            messageItem.setContent(text);
            items.add(messageItem);
        }
        List<ToolUseBlock> toolUses = msg.getContentBlocks(ToolUseBlock.class);
        for (ToolUseBlock toolUse : toolUses) {
            items.add(convertToolUseToFunctionCall(toolUse));
        }
        return items;
    }

    private ResponsesInputItem convertToolUseToFunctionCall(ToolUseBlock toolUse) {
        ResponsesInputItem item = new ResponsesInputItem();
        item.setType("function_call");
        item.setCallId(toolUse.getId());
        item.setName(toolUse.getName());
        // Serialize the tool input map as a JSON string (Responses API expects a string)
        item.setArguments(JsonUtils.resolveToolCallArgsJson(toolUse));
        return item;
    }

    /**
     * Convert a tool result message. Each {@link ToolResultBlock} becomes a
     * {@code function_call_output} input item.
     */
    private List<ResponsesInputItem> convertToolMessage(Msg msg) {
        List<ToolResultBlock> results = msg.getContentBlocks(ToolResultBlock.class);
        List<ResponsesInputItem> items = new java.util.ArrayList<>();
        for (ToolResultBlock result : results) {
            ResponsesInputItem item = new ResponsesInputItem();
            item.setType("function_call_output");
            item.setCallId(result.getId());
            item.setOutput(toolResultConverter.apply(result.getOutput()));
            items.add(item);
        }
        return items;
    }
}
