package ink.icoding.codex.http;

import java.time.Duration;
import java.util.function.Consumer;

final class SseParser {

    private final Consumer<SseEvent> consumer;
    private final StringBuilder data = new StringBuilder();
    private String event;
    private String lastEventId;
    private Duration retry;
    private boolean firstLine = true;

    SseParser(Consumer<SseEvent> consumer) {
        this.consumer = consumer;
    }

    void accept(String input) {
        String line = input;
        if (firstLine) {
            firstLine = false;
            if (!line.isEmpty() && line.charAt(0) == '\ufeff') {
                line = line.substring(1);
            }
        }
        if (line.isEmpty()) {
            dispatch();
            return;
        }
        if (line.charAt(0) == ':') {
            return;
        }
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        switch (field) {
            case "event" -> event = value;
            case "data" -> data.append(value).append('\n');
            case "id" -> {
                if (value.indexOf('\0') < 0) {
                    lastEventId = value;
                }
            }
            case "retry" -> {
                if (value.matches("[0-9]+")) {
                    try {
                        retry = Duration.ofMillis(Long.parseLong(value));
                    } catch (ArithmeticException | NumberFormatException ignored) {
                        // Values outside Duration's range are ignored by the SSE parser.
                    }
                }
            }
            default -> {
                // Unknown fields are ignored by the SSE specification.
            }
        }
    }

    void endOfInput() {
        dispatch();
    }

    private void dispatch() {
        if (data.isEmpty()) {
            event = null;
            return;
        }
        data.setLength(data.length() - 1);
        consumer.accept(new SseEvent(
                event == null || event.isEmpty() ? "message" : event,
                data.toString(), lastEventId, retry));
        data.setLength(0);
        event = null;
    }
}
