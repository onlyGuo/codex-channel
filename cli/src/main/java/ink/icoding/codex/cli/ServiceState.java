package ink.icoding.codex.cli;

import java.time.Instant;

record ServiceState(
        long pid,
        String host,
        int port,
        String adminToken,
        Instant startedAt) {
}
