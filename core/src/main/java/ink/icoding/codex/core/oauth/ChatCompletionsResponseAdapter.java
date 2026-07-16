package ink.icoding.codex.core.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;

/** Converts a completed Responses object to a public Chat Completions response object. */
final class ChatCompletionsResponseAdapter {

    private ChatCompletionsResponseAdapter() {
    }

    static ObjectNode toChatCompletion(
            JsonNode response, String requestedModel, ObjectMapper objectMapper, Clock clock) {
        if (!response.isObject()) {
            throw invalid("Responses result must be a JSON object");
        }
        StringBuilder content = new StringBuilder();
        StringBuilder refusal = new StringBuilder();
        ArrayNode toolCalls = objectMapper.createArrayNode();
        for (JsonNode output : response.path("output")) {
            switch (output.path("type").asText()) {
                case "message" -> appendMessage(output, content, refusal);
                case "function_call" -> toolCalls.add(functionCall(output, objectMapper));
                case "reasoning" -> {
                    // Chat Completions does not expose Responses reasoning items.
                }
                case "" -> {
                    // Ignore malformed empty placeholders; the final message remains usable.
                }
                default -> throw invalid("Responses output type cannot be represented by Chat Completions: "
                        + output.path("type").asText());
            }
        }

        ObjectNode result = objectMapper.createObjectNode()
                .put("id", chatCompletionId(response.path("id").asText(null)))
                .put("object", "chat.completion")
                .put("created", response.path("created_at").asLong(clock.instant().getEpochSecond()))
                .put("model", response.path("model").asText(requestedModel == null ? "" : requestedModel));
        ObjectNode choice = result.putArray("choices").addObject().put("index", 0);
        ObjectNode message = choice.putObject("message").put("role", "assistant");
        if (content.isEmpty()) {
            message.putNull("content");
        } else {
            message.put("content", content.toString());
        }
        if (!refusal.isEmpty()) {
            message.put("refusal", refusal.toString());
        }
        if (!toolCalls.isEmpty()) {
            message.set("tool_calls", toolCalls);
        }
        choice.putNull("logprobs");
        choice.put("finish_reason", finishReason(response, !toolCalls.isEmpty()));
        result.set("usage", chatUsage(response.path("usage"), objectMapper));
        copyIfPresent(response, result, "service_tier", "system_fingerprint");
        return result;
    }

    private static void appendMessage(JsonNode output, StringBuilder content, StringBuilder refusal) {
        for (JsonNode part : output.path("content")) {
            switch (part.path("type").asText()) {
                case "output_text" -> content.append(part.path("text").asText(""));
                case "refusal" -> refusal.append(part.path("refusal").asText(""));
                default -> throw invalid("Responses message content cannot be represented by Chat Completions: "
                        + part.path("type").asText());
            }
        }
    }

    private static ObjectNode functionCall(JsonNode output, ObjectMapper objectMapper) {
        ObjectNode call = objectMapper.createObjectNode()
                .put("id", firstText(output, "call_id", "id"))
                .put("type", "function");
        call.putObject("function")
                .put("name", output.path("name").asText(""))
                .put("arguments", output.path("arguments").asText(""));
        return call;
    }

    private static ObjectNode chatUsage(JsonNode usage, ObjectMapper objectMapper) {
        ObjectNode result = objectMapper.createObjectNode()
                .put("prompt_tokens", usage.path("input_tokens").asLong(0))
                .put("completion_tokens", usage.path("output_tokens").asLong(0))
                .put("total_tokens", usage.path("total_tokens").asLong(0));
        JsonNode inputDetails = usage.path("input_tokens_details");
        if (inputDetails.isObject()) {
            result.putObject("prompt_tokens_details")
                    .put("cached_tokens", inputDetails.path("cached_tokens").asLong(0));
        }
        JsonNode outputDetails = usage.path("output_tokens_details");
        if (outputDetails.isObject()) {
            result.putObject("completion_tokens_details")
                    .put("reasoning_tokens", outputDetails.path("reasoning_tokens").asLong(0));
        }
        return result;
    }

    private static String finishReason(JsonNode response, boolean hasToolCalls) {
        if ("incomplete".equals(response.path("status").asText())) {
            String reason = response.path("incomplete_details").path("reason").asText();
            return "content_filter".equals(reason) ? "content_filter" : "length";
        }
        return hasToolCalls ? "tool_calls" : "stop";
    }

    private static String chatCompletionId(String responseId) {
        if (responseId == null || responseId.isBlank()) {
            return "chatcmpl-adapted";
        }
        return responseId.startsWith("resp_")
                ? "chatcmpl-" + responseId.substring("resp_".length())
                : responseId;
    }

    private static String firstText(JsonNode node, String first, String second) {
        String value = node.path(first).asText("");
        return value.isBlank() ? node.path(second).asText("") : value;
    }

    private static void copyIfPresent(JsonNode source, ObjectNode target, String... fields) {
        for (String field : fields) {
            if (source.has(field)) {
                target.set(field, source.get(field).deepCopy());
            }
        }
    }

    private static ChatGptAccountException invalid(String message) {
        return new ChatGptAccountException(message, -1, null);
    }
}
