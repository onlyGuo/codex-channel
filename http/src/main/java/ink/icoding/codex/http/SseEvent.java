package ink.icoding.codex.http;

import java.time.Duration;

/** One Server-Sent Events message after WHATWG field folding. */
public record SseEvent(String event, String data, String id, Duration retry) {
}
