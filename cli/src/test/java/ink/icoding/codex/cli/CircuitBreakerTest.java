package ink.icoding.codex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    @Test
    void opensAfterThresholdAndAllowsOneHalfOpenProbe() {
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(30));
        Instant start = Instant.parse("2026-07-16T00:00:00Z");

        assertTrue(breaker.tryAcquire(start));
        breaker.failure(start);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.snapshot(start).state());

        assertTrue(breaker.tryAcquire(start));
        breaker.failure(start);
        assertEquals(CircuitBreaker.State.OPEN, breaker.snapshot(start).state());
        assertFalse(breaker.tryAcquire(start.plusSeconds(29)));

        assertTrue(breaker.tryAcquire(start.plusSeconds(30)));
        assertFalse(breaker.tryAcquire(start.plusSeconds(30)));
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.snapshot(start.plusSeconds(30)).state());

        breaker.success();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.snapshot(start.plusSeconds(30)).state());
        assertTrue(breaker.tryAcquire(start.plusSeconds(30)));
    }
}
