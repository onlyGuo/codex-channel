package ink.icoding.codex.core.oauth;

import com.fasterxml.jackson.databind.JsonNode;

/** Receives OpenAI Chat Completions streaming chunks in their public JSON shape. */
public interface OpenAiChatCompletionsListener {

    default void onOpen() {
    }

    void onChunk(JsonNode chunk);

    /** Called at the position where the Chat Completions SSE stream emits {@code [DONE]}. */
    default void onComplete() {
    }

    default void onError(Throwable error) {
    }
}
