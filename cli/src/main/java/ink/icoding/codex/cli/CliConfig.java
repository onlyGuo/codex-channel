package ink.icoding.codex.cli;

record CliConfig(
        String activeAccount,
        String schedulePolicy,
        int failureThreshold,
        long circuitOpenSeconds) {

    CliConfig {
        if (schedulePolicy == null || schedulePolicy.isBlank()) schedulePolicy = "weighted-round-robin";
        if (!"weighted-round-robin".equals(schedulePolicy)) {
            throw new IllegalArgumentException("Unsupported schedule policy: " + schedulePolicy);
        }
        if (failureThreshold < 1 || failureThreshold > 100) {
            throw new IllegalArgumentException("failureThreshold must be between 1 and 100");
        }
        if (circuitOpenSeconds < 1 || circuitOpenSeconds > 86_400) {
            throw new IllegalArgumentException("circuitOpenSeconds must be between 1 and 86400");
        }
    }

    static CliConfig defaults() {
        return new CliConfig(null, "weighted-round-robin", 3, 60);
    }

    CliConfig withActiveAccount(String alias) {
        return new CliConfig(alias, schedulePolicy, failureThreshold, circuitOpenSeconds);
    }

    CliConfig withCircuitBreaker(int threshold, long openSeconds) {
        return new CliConfig(activeAccount, schedulePolicy, threshold, openSeconds);
    }
}
