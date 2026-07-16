package ink.icoding.codex.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SseParserTest {

    @Test
    void parsesBomMultilineDataIdsAndRetry() {
        List<SseEvent> events = new ArrayList<>();
        SseParser parser = new SseParser(events::add);

        parser.accept("\ufeff: keep-alive");
        parser.accept("id: 42");
        parser.accept("event: update");
        parser.accept("retry: 1500");
        parser.accept("data: first");
        parser.accept("data: second");
        parser.accept("");
        parser.accept("data: final");
        parser.endOfInput();

        assertEquals(2, events.size());
        assertEquals(new SseEvent("update", "first\nsecond", "42", Duration.ofMillis(1500)), events.get(0));
        assertEquals(new SseEvent("message", "final", "42", Duration.ofMillis(1500)), events.get(1));
    }
}
