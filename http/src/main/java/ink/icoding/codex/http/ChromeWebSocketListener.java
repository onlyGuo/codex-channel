package ink.icoding.codex.http;

import java.nio.ByteBuffer;

/** Callbacks for a transport-neutral WebSocket session. */
public interface ChromeWebSocketListener {

    default void onOpen(ChromeWebSocket webSocket) {
    }

    default void onText(ChromeWebSocket webSocket, CharSequence data, boolean last) {
    }

    default void onBinary(ChromeWebSocket webSocket, ByteBuffer data, boolean last) {
    }

    default void onPing(ChromeWebSocket webSocket, ByteBuffer message) {
    }

    default void onPong(ChromeWebSocket webSocket, ByteBuffer message) {
    }

    default void onClose(ChromeWebSocket webSocket, int statusCode, String reason) {
    }

    default void onError(ChromeWebSocket webSocket, Throwable error) {
    }
}
