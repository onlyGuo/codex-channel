package ink.icoding.codex.core.oauth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/** Converts Responses SSE events to public Chat Completions streaming chunks. */
final class ChatCompletionsStreamAdapter implements OpenAiResponsesListener {

    private final OpenAiChatCompletionsListener listener;
    private final boolean includeUsage;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, ToolCall> toolCalls = new LinkedHashMap<>();

    private String responseId;
    private String model;
    private long createdAt;
    private boolean assistantRoleSent;

    ChatCompletionsStreamAdapter(
            OpenAiChatCompletionsListener listener,
            String requestedModel,
            boolean includeUsage,
            ObjectMapper objectMapper,
            Clock clock) {
        this.listener = listener;
        this.model = requestedModel;
        this.includeUsage = includeUsage;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.createdAt = clock.instant().getEpochSecond();
    }

    @Override
    public void onOpen() {
        listener.onOpen();
    }

    @Override
    public void onEvent(OpenAiResponsesEvent event) {
        if (event.data().isBlank() || "[DONE]".equals(event.data())) {
            return;
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(event.data());
        } catch (JsonProcessingException exception) {
            fail("Could not parse a Responses SSE event", exception);
            return;
        }

        String type = "message".equals(event.event())
                ? payload.path("type").asText("message")
                : event.event();
        switch (type) {
            case "response.created" -> captureResponse(responseNode(payload));
            case "response.output_item.added" -> outputItemAdded(payload.path("item"));
            case "response.output_text.delta" -> outputTextDelta(payload.path("delta").asText(""));
            case "response.refusal.delta" -> refusalDelta(payload.path("delta").asText(""));
            case "response.function_call_arguments.delta" -> functionArgumentsDelta(payload);
            case "response.completed" -> completeResponse(responseNode(payload), false);
            case "response.incomplete" -> completeResponse(responseNode(payload), true);
            case "response.failed", "error" -> fail(errorMessage(payload), null);
            default -> {
                // Lifecycle, done, annotation, and reasoning events do not have Chat chunk equivalents.
            }
        }
    }

    @Override
    public void onComplete() {
        listener.onComplete();
    }

    @Override
    public void onError(Throwable error) {
        listener.onError(error);
    }

    private void outputItemAdded(JsonNode item) {
        String type = item.path("type").asText();
        if ("message".equals(type)) {
            emitAssistantRole();
            return;
        }
        if ("function_call".equals(type)) {
            String itemId = firstText(item, "id", "call_id");
            String callId = firstText(item, "call_id", "id");
            String name = item.path("name").asText("");
            ToolCall toolCall = new ToolCall(toolCalls.size(), callId, name);
            toolCalls.put(itemId, toolCall);

            ObjectNode delta = objectMapper.createObjectNode();
            ObjectNode call = delta.putArray("tool_calls").addObject()
                    .put("index", toolCall.index())
                    .put("id", toolCall.callId())
                    .put("type", "function");
            call.putObject("function").put("name", toolCall.name()).put("arguments", "");
            emitChunk(delta, null);
            return;
        }
        if (!type.isBlank() && !"reasoning".equals(type)) {
            fail("Responses output type cannot be represented by Chat Completions: " + type, null);
        }
    }

    private void outputTextDelta(String text) {
        if (text.isEmpty()) {
            return;
        }
        emitAssistantRole();
        emitChunk(objectMapper.createObjectNode().put("content", text), null);
    }

    private void refusalDelta(String refusal) {
        if (refusal.isEmpty()) {
            return;
        }
        emitAssistantRole();
        emitChunk(objectMapper.createObjectNode().put("refusal", refusal), null);
    }

    private void functionArgumentsDelta(JsonNode payload) {
        String itemId = payload.path("item_id").asText("");
        ToolCall toolCall = toolCalls.get(itemId);
        if (toolCall == null) {
            toolCall = new ToolCall(toolCalls.size(), itemId, "");
            toolCalls.put(itemId, toolCall);
        }
        ObjectNode delta = objectMapper.createObjectNode();
        ObjectNode call = delta.putArray("tool_calls").addObject().put("index", toolCall.index());
        call.putObject("function").put("arguments", payload.path("delta").asText(""));
        emitChunk(delta, null);
    }

    private void completeResponse(JsonNode response, boolean incomplete) {
        captureResponse(response);
        String finishReason = incomplete ? incompleteFinishReason(response) : toolCalls.isEmpty() ? "stop" : "tool_calls";
        emitChunk(objectMapper.createObjectNode(), finishReason);
        if (includeUsage) {
            ObjectNode usageChunk = baseChunk();
            usageChunk.set("choices", objectMapper.createArrayNode());
            usageChunk.set("usage", chatUsage(response.path("usage")));
            listener.onChunk(usageChunk);
        }
    }

    private void emitAssistantRole() {
        if (!assistantRoleSent) {
            assistantRoleSent = true;
            emitChunk(objectMapper.createObjectNode().put("role", "assistant").put("content", ""), null);
        }
    }

    private void emitChunk(ObjectNode delta, String finishReason) {
        ObjectNode chunk = baseChunk();
        ObjectNode choice = chunk.putArray("choices").addObject().put("index", 0);
        choice.set("delta", delta);
        choice.putNull("logprobs");
        if (finishReason == null) {
            choice.putNull("finish_reason");
        } else {
            choice.put("finish_reason", finishReason);
        }
        listener.onChunk(chunk);
    }

    private ObjectNode baseChunk() {
        return objectMapper.createObjectNode()
                .put("id", chatCompletionId())
                .put("object", "chat.completion.chunk")
                .put("created", createdAt)
                .put("model", model == null ? "" : model);
    }

    private ObjectNode chatUsage(JsonNode usage) {
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

    private void captureResponse(JsonNode response) {
        if (!response.isObject()) {
            return;
        }
        String id = response.path("id").asText(null);
        if (id != null && !id.isBlank()) {
            responseId = id;
        }
        String responseModel = response.path("model").asText(null);
        if (responseModel != null && !responseModel.isBlank()) {
            model = responseModel;
        }
        if (response.has("created_at")) {
            createdAt = response.path("created_at").asLong(createdAt);
        }
    }

    private String chatCompletionId() {
        if (responseId == null || responseId.isBlank()) {
            return "chatcmpl-adapted";
        }
        return responseId.startsWith("resp_")
                ? "chatcmpl-" + responseId.substring("resp_".length())
                : responseId;
    }

    private static JsonNode responseNode(JsonNode payload) {
        return payload.has("response") ? payload.path("response") : payload;
    }

    private static String incompleteFinishReason(JsonNode response) {
        String reason = response.path("incomplete_details").path("reason").asText();
        return "content_filter".equals(reason) ? "content_filter" : "length";
    }

    private static String firstText(JsonNode node, String first, String second) {
        String value = node.path(first).asText("");
        return value.isBlank() ? node.path(second).asText("") : value;
    }

    private static String errorMessage(JsonNode payload) {
        JsonNode error = payload.path("error");
        String message = error.path("message").asText();
        return message.isBlank() ? "Responses stream reported an error" : message;
    }

    private void fail(String message, Throwable cause) {
        ChatGptAccountException exception = new ChatGptAccountException(message, -1, cause);
        listener.onError(exception);
        throw exception;
    }

    private record ToolCall(int index, String callId, String name) {
    }
}
