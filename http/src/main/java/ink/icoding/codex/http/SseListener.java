package ink.icoding.codex.http;

/** Callback listener for a live Server-Sent Events response. */
public interface SseListener {

    default void onOpen(ChromeHttpResponse.Metadata response) {
    }

    void onEvent(SseEvent event);

    default void onClosed() {
    }

    default void onError(Throwable error) {
    }
}
