package ink.icoding.codex.core.oauth;

/**
 * @deprecated Use {@link OpenAiResponsesListener}. The events already follow the OpenAI
 * Responses SSE format; this name is retained for source compatibility.
 */
@Deprecated
public interface ChatGptResponsesListener extends OpenAiResponsesListener {

    void onEvent(ChatGptSseEvent event);

    @Override
    default void onEvent(OpenAiResponsesEvent event) {
        onEvent(new ChatGptSseEvent(event.event(), event.data(), event.id(), event.retry()));
    }
}
