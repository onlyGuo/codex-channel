package ink.icoding.codex.cli;

import java.time.Duration;
import java.time.Instant;

final class CircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    private final int threshold;
    private final Duration openDuration;
    private int consecutiveFailures;
    private Instant openUntil;
    private boolean halfOpenProbe;

    CircuitBreaker(int threshold, Duration openDuration) {
        if (threshold < 1 || openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException("Invalid circuit breaker configuration");
        }
        this.threshold = threshold;
        this.openDuration = openDuration;
    }

    synchronized boolean tryAcquire(Instant now) {
        if (openUntil == null) {
            return true;
        }
        if (now.isBefore(openUntil)) {
            return false;
        }
        if (halfOpenProbe) {
            return false;
        }
        halfOpenProbe = true;
        return true;
    }

    synchronized void success() {
        consecutiveFailures = 0;
        openUntil = null;
        halfOpenProbe = false;
    }

    synchronized void failure(Instant now) {
        consecutiveFailures++;
        if (halfOpenProbe || consecutiveFailures >= threshold) {
            openUntil = now.plus(openDuration);
        }
        halfOpenProbe = false;
    }

    synchronized Snapshot snapshot(Instant now) {
        State state;
        if (openUntil == null) {
            state = State.CLOSED;
        } else if (now.isBefore(openUntil)) {
            state = State.OPEN;
        } else {
            state = State.HALF_OPEN;
        }
        return new Snapshot(state, consecutiveFailures, openUntil);
    }

    record Snapshot(State state, int consecutiveFailures, Instant openUntil) {
    }
}
