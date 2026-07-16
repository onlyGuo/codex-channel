package ink.icoding.codex.cli;

import java.time.Instant;

record AccountMetadata(
        String alias,
        boolean enabled,
        int weight,
        Instant createdAt,
        Instant updatedAt) {

    AccountMetadata {
        if (weight < 1 || weight > 100) {
            throw new IllegalArgumentException("weight must be between 1 and 100");
        }
    }

    AccountMetadata withEnabled(boolean value) {
        return new AccountMetadata(alias, value, weight, createdAt, Instant.now());
    }

    AccountMetadata withWeight(int value) {
        return new AccountMetadata(alias, enabled, value, createdAt, Instant.now());
    }
}
