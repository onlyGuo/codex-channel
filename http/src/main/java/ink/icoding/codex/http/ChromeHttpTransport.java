package ink.icoding.codex.http;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/** Unified transport contract used by clients that require Chrome-style HTTP requests. */
public interface ChromeHttpTransport extends AutoCloseable {

    <T> ChromeHttpResponse<T> send(ChromeHttpRequest request, ChromeBodyHandler<T> bodyHandler)
            throws IOException, InterruptedException;

    default ChromeHttpResponse<String> send(ChromeHttpRequest request)
            throws IOException, InterruptedException {
        return send(request, ChromeBodyHandlers.ofString());
    }

    void streamSse(ChromeHttpRequest request, SseListener listener)
            throws IOException, InterruptedException;

    CompletableFuture<ChromeWebSocket> openWebSocket(
            ChromeWebSocketRequest request, ChromeWebSocketListener listener);

    ChromeHttpCapabilities capabilities();

    @Override
    default void close() {
    }
}
