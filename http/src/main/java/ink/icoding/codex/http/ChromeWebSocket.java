package ink.icoding.codex.http;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/** Transport-neutral WebSocket session exposed by the HTTP module. */
public interface ChromeWebSocket {

    int NORMAL_CLOSURE = 1000;

    void request(long messageCount);

    CompletableFuture<ChromeWebSocket> sendText(CharSequence data, boolean last);

    CompletableFuture<ChromeWebSocket> sendBinary(ByteBuffer data, boolean last);

    CompletableFuture<ChromeWebSocket> sendPing(ByteBuffer message);

    CompletableFuture<ChromeWebSocket> sendPong(ByteBuffer message);

    CompletableFuture<ChromeWebSocket> sendClose(int statusCode, String reason);

    void abort();
}
