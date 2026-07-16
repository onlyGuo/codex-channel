package ink.icoding.codex.core.oauth;

/** Receives the Server-Sent Events defined by the OpenAI Responses API. */
public interface OpenAiResponsesListener {

    default void onOpen() {
    }

    void onEvent(OpenAiResponsesEvent event);

    default void onComplete() {
    }

    default void onError(Throwable error) {
    }
}
