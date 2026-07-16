package ink.icoding.codex.core.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Map;

/** Converts the losslessly representable Chat Completions request surface to Responses. */
final class ChatCompletionsToResponsesAdapter {

    private ChatCompletionsToResponsesAdapter() {
    }

    static ObjectNode toResponsesRequest(JsonNode chatRequest, ObjectMapper objectMapper) {
        return toResponsesRequest(chatRequest, objectMapper, true);
    }

    static ObjectNode toResponsesRequest(
            JsonNode chatRequest, ObjectMapper objectMapper, boolean streaming) {
        if (!(chatRequest instanceof ObjectNode chatObject)) {
            throw invalid("Chat Completions request body must be a JSON object");
        }
        ObjectNode normalizedChat = normalizeLegacyFunctionControls(chatObject, objectMapper);
        JsonNode messages = normalizedChat.path("messages");
        if (!messages.isArray() || messages.isEmpty()) {
            throw invalid("Chat Completions request requires a non-empty messages array");
        }
        if (normalizedChat.has("stream") && normalizedChat.path("stream").asBoolean() != streaming) {
            throw invalid(streaming
                    ? "streamChatCompletions requires stream=true"
                    : "createChatCompletion requires stream=false");
        }
        if (normalizedChat.path("n").asInt(1) != 1) {
            throw invalid("Chat Completions n must be 1 because Responses has one output sequence");
        }
        rejectPresent(normalizedChat, "logprobs", "top_logprobs", "logit_bias", "seed", "stop");
        validateStreamOptions(normalizedChat.path("stream_options"));

        ObjectNode responses = normalizedChat.deepCopy();
        responses.remove("messages");
        responses.remove("stream");
        responses.remove("stream_options");
        responses.remove("n");
        responses.set("input", messagesToInput((ArrayNode) messages, objectMapper));
        moveMaxTokens(responses, normalizedChat);
        moveResponseFormat(responses, normalizedChat, objectMapper);
        moveTools(responses, normalizedChat, objectMapper);
        moveToolChoice(responses, normalizedChat, objectMapper);
        return responses;
    }

    static boolean includesUsage(JsonNode chatRequest) {
        return chatRequest.path("stream_options").path("include_usage").asBoolean(false);
    }

    private static ArrayNode messagesToInput(ArrayNode messages, ObjectMapper objectMapper) {
        ArrayNode input = objectMapper.createArrayNode();
        Map<String, String> legacyCallIds = new HashMap<>();
        int legacyCallIndex = 0;
        for (JsonNode messageNode : messages) {
            if (!(messageNode instanceof ObjectNode message)) {
                throw invalid("Every Chat Completions message must be a JSON object");
            }
            String role = requiredText(message, "role", "Chat Completions message role");
            switch (role) {
                case "system", "developer", "user" -> input.add(textMessage(message, role, false, objectMapper));
                case "assistant" -> legacyCallIndex = appendAssistantItems(
                        input, message, objectMapper, legacyCallIds, legacyCallIndex);
                case "tool" -> input.add(toolResult(message, objectMapper));
                case "function" -> input.add(legacyFunctionResult(message, objectMapper, legacyCallIds));
                default -> throw invalid("Chat Completions role is not supported: " + role);
            }
        }
        return input;
    }

    private static ObjectNode textMessage(
            ObjectNode message, String role, boolean assistantOutput, ObjectMapper objectMapper) {
        ObjectNode converted = objectMapper.createObjectNode().put("role", role);
        JsonNode content = message.get("content");
        if (content == null || content.isNull()) {
            throw invalid("Chat Completions " + role + " message must contain content");
        }
        converted.set("content", contentParts(content, assistantOutput, objectMapper));
        return converted;
    }

    private static int appendAssistantItems(
            ArrayNode input,
            ObjectNode message,
            ObjectMapper objectMapper,
            Map<String, String> legacyCallIds,
            int legacyCallIndex) {
        JsonNode content = message.get("content");
        if (content != null && !content.isNull()) {
            input.add(textMessage(message, "assistant", true, objectMapper));
        }
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode toolCallNode : toolCalls) {
                if (!(toolCallNode instanceof ObjectNode toolCall)
                        || !"function".equals(toolCall.path("type").asText())) {
                    throw invalid("Only function tool_calls can be mapped to Responses");
                }
                ObjectNode function = requireObject(toolCall, "function", "Chat Completions tool_call.function");
                input.add(objectMapper.createObjectNode()
                        .put("type", "function_call")
                        .put("call_id", requiredText(toolCall, "id", "Chat Completions tool_call id"))
                        .put("name", requiredText(function, "name", "Chat Completions function name"))
                        .put("arguments", function.path("arguments").asText("")));
            }
        }
        JsonNode legacyFunctionCall = message.get("function_call");
        if (legacyFunctionCall != null && !legacyFunctionCall.isNull()) {
            ObjectNode function = requireObject(legacyFunctionCall, "assistant.function_call");
            String name = requiredText(function, "name", "assistant.function_call.name");
            String callId = "call_legacy_" + legacyCallIndex++;
            legacyCallIds.put(name, callId);
            input.add(objectMapper.createObjectNode()
                    .put("type", "function_call")
                    .put("call_id", callId)
                    .put("name", name)
                    .put("arguments", function.path("arguments").asText("")));
        }
        if ((content == null || content.isNull()) && !toolCalls.isArray()
                && (legacyFunctionCall == null || legacyFunctionCall.isNull())) {
            throw invalid("Assistant message must contain content or tool_calls");
        }
        return legacyCallIndex;
    }

    private static ObjectNode toolResult(ObjectNode message, ObjectMapper objectMapper) {
        JsonNode content = message.get("content");
        if (content == null || content.isNull()) {
            throw invalid("Tool message must contain content");
        }
        return objectMapper.createObjectNode()
                .put("type", "function_call_output")
                .put("call_id", requiredText(message, "tool_call_id", "Chat Completions tool_call_id"))
                .put("output", content.isTextual() ? content.textValue() : content.toString());
    }

    private static ObjectNode legacyFunctionResult(
            ObjectNode message, ObjectMapper objectMapper, Map<String, String> legacyCallIds) {
        String name = requiredText(message, "name", "Legacy function message name");
        String callId = legacyCallIds.get(name);
        if (callId == null) {
            throw invalid("Legacy function result has no preceding assistant function_call: " + name);
        }
        JsonNode content = message.get("content");
        if (content == null || content.isNull()) {
            throw invalid("Legacy function message must contain content");
        }
        return objectMapper.createObjectNode()
                .put("type", "function_call_output")
                .put("call_id", callId)
                .put("output", content.isTextual() ? content.textValue() : content.toString());
    }

    private static ObjectNode normalizeLegacyFunctionControls(
            ObjectNode chatRequest, ObjectMapper objectMapper) {
        ObjectNode normalized = chatRequest.deepCopy();
        JsonNode functions = normalized.remove("functions");
        if (functions != null && !functions.isNull()) {
            if (normalized.hasNonNull("tools")) {
                throw invalid("functions and tools cannot both be supplied");
            }
            if (!functions.isArray()) {
                throw invalid("Chat Completions functions must be an array");
            }
            ArrayNode tools = objectMapper.createArrayNode();
            for (JsonNode function : functions) {
                tools.add(objectMapper.createObjectNode()
                        .put("type", "function")
                        .set("function", function.deepCopy()));
            }
            normalized.set("tools", tools);
        }
        JsonNode functionCall = normalized.remove("function_call");
        if (functionCall != null && !functionCall.isNull()) {
            if (normalized.hasNonNull("tool_choice")) {
                throw invalid("function_call and tool_choice cannot both be supplied");
            }
            if (functionCall.isTextual()) {
                normalized.set("tool_choice", functionCall.deepCopy());
            } else {
                ObjectNode choice = requireObject(functionCall, "function_call");
                normalized.set("tool_choice", objectMapper.createObjectNode()
                        .put("type", "function")
                        .set("function", choice.deepCopy()));
            }
        }
        return normalized;
    }

    private static void validateStreamOptions(JsonNode streamOptions) {
        if (!streamOptions.isObject()) {
            return;
        }
        streamOptions.fieldNames().forEachRemaining(field -> {
            if (!"include_usage".equals(field)) {
                throw invalid("Chat Completions stream_options field cannot be mapped: " + field);
            }
        });
    }

    private static ArrayNode contentParts(JsonNode content, boolean assistantOutput, ObjectMapper objectMapper) {
        ArrayNode parts = objectMapper.createArrayNode();
        if (content.isTextual()) {
            parts.add(textPart(content.textValue(), assistantOutput, objectMapper));
            return parts;
        }
        if (!content.isArray()) {
            throw invalid("Chat Completions message content must be text or an array of content parts");
        }
        for (JsonNode partNode : content) {
            if (!(partNode instanceof ObjectNode part)) {
                throw invalid("Chat Completions content part must be a JSON object");
            }
            String type = requiredText(part, "type", "Chat Completions content part type");
            switch (type) {
                case "text" -> parts.add(textPart(requiredText(part, "text", "Chat Completions text"), assistantOutput, objectMapper));
                case "image_url" -> {
                    if (assistantOutput) {
                        throw invalid("Assistant image_url content cannot be mapped to Responses input");
                    }
                    parts.add(imagePart(part, objectMapper));
                }
                case "refusal" -> parts.add(objectMapper.createObjectNode()
                        .put("type", "refusal")
                        .put("refusal", requiredText(part, "refusal", "Chat Completions refusal")));
                default -> throw invalid("Chat Completions content type is not supported: " + type);
            }
        }
        return parts;
    }

    private static ObjectNode textPart(String text, boolean assistantOutput, ObjectMapper objectMapper) {
        return objectMapper.createObjectNode()
                .put("type", assistantOutput ? "output_text" : "input_text")
                .put("text", text);
    }

    private static ObjectNode imagePart(ObjectNode part, ObjectMapper objectMapper) {
        JsonNode imageUrl = part.get("image_url");
        if (imageUrl == null || imageUrl.isNull()) {
            throw invalid("Chat Completions image_url content must contain image_url");
        }
        ObjectNode image = objectMapper.createObjectNode().put("type", "input_image");
        if (imageUrl.isTextual()) {
            image.put("image_url", imageUrl.textValue());
        } else if (imageUrl instanceof ObjectNode imageObject) {
            image.put("image_url", requiredText(imageObject, "url", "Chat Completions image_url.url"));
            if (imageObject.has("detail")) {
                image.set("detail", imageObject.get("detail").deepCopy());
            }
        } else {
            throw invalid("Chat Completions image_url must be text or an object");
        }
        return image;
    }

    private static void moveMaxTokens(ObjectNode responses, ObjectNode chatRequest) {
        JsonNode oldValue = chatRequest.get("max_tokens");
        JsonNode newValue = chatRequest.get("max_completion_tokens");
        responses.remove("max_tokens");
        responses.remove("max_completion_tokens");
        if (oldValue != null && newValue != null && !oldValue.equals(newValue)) {
            throw invalid("max_tokens and max_completion_tokens conflict");
        }
        JsonNode value = newValue != null ? newValue : oldValue;
        if (value != null) {
            responses.set("max_output_tokens", value.deepCopy());
        }
    }

    private static void moveResponseFormat(ObjectNode responses, ObjectNode chatRequest, ObjectMapper objectMapper) {
        JsonNode responseFormat = chatRequest.get("response_format");
        responses.remove("response_format");
        if (responseFormat == null || responseFormat.isNull()) {
            return;
        }
        ObjectNode format = requireObject(responseFormat, "response_format");
        String type = requiredText(format, "type", "response_format.type");
        if ("text".equals(type)) {
            return;
        }
        ObjectNode text = objectMapper.createObjectNode();
        ObjectNode targetFormat = objectMapper.createObjectNode().put("type", type);
        if ("json_schema".equals(type)) {
            ObjectNode schema = requireObject(format, "json_schema", "response_format.json_schema");
            copyIfPresent(schema, targetFormat, "name", "description", "schema", "strict");
        } else if (!"json_object".equals(type)) {
            throw invalid("Chat Completions response_format type is not supported: " + type);
        }
        text.set("format", targetFormat);
        responses.set("text", text);
    }

    private static void moveTools(ObjectNode responses, ObjectNode chatRequest, ObjectMapper objectMapper) {
        JsonNode tools = chatRequest.get("tools");
        responses.remove("tools");
        if (tools == null || tools.isNull()) {
            return;
        }
        if (!tools.isArray()) {
            throw invalid("Chat Completions tools must be an array");
        }
        ArrayNode converted = objectMapper.createArrayNode();
        for (JsonNode toolNode : tools) {
            ObjectNode tool = requireObject(toolNode, "Chat Completions tool");
            if (!"function".equals(tool.path("type").asText())) {
                throw invalid("Only function tools can be mapped to Responses");
            }
            ObjectNode function = requireObject(tool, "function", "Chat Completions tool.function");
            ObjectNode target = objectMapper.createObjectNode()
                    .put("type", "function")
                    .put("name", requiredText(function, "name", "Chat Completions function name"));
            copyIfPresent(function, target, "description", "parameters", "strict");
            converted.add(target);
        }
        responses.set("tools", converted);
    }

    private static void moveToolChoice(ObjectNode responses, ObjectNode chatRequest, ObjectMapper objectMapper) {
        JsonNode toolChoice = chatRequest.get("tool_choice");
        responses.remove("tool_choice");
        if (toolChoice == null || toolChoice.isNull() || toolChoice.isTextual()) {
            if (toolChoice != null && !toolChoice.isNull()) {
                responses.set("tool_choice", toolChoice.deepCopy());
            }
            return;
        }
        ObjectNode choice = requireObject(toolChoice, "tool_choice");
        if (!"function".equals(choice.path("type").asText())) {
            throw invalid("Only function tool_choice can be mapped to Responses");
        }
        ObjectNode function = requireObject(choice, "function", "tool_choice.function");
        responses.set("tool_choice", objectMapper.createObjectNode()
                .put("type", "function")
                .put("name", requiredText(function, "name", "tool_choice.function.name")));
    }

    private static void rejectPresent(ObjectNode request, String... fields) {
        for (String field : fields) {
            if (request.hasNonNull(field)) {
                throw invalid("Chat Completions " + field + " cannot be losslessly mapped to Responses");
            }
        }
    }

    private static void copyIfPresent(ObjectNode source, ObjectNode target, String... fields) {
        for (String field : fields) {
            if (source.has(field)) {
                target.set(field, source.get(field).deepCopy());
            }
        }
    }

    private static ObjectNode requireObject(JsonNode node, String description) {
        if (!(node instanceof ObjectNode object)) {
            throw invalid(description + " must be a JSON object");
        }
        return object;
    }

    private static ObjectNode requireObject(ObjectNode parent, String field, String description) {
        return requireObject(parent.get(field), description);
    }

    private static String requiredText(ObjectNode object, String field, String description) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid(description + " must be a non-empty string");
        }
        return value.textValue();
    }

    private static ChatGptAccountException invalid(String message) {
        return new ChatGptAccountException(message, -1, null);
    }
}
