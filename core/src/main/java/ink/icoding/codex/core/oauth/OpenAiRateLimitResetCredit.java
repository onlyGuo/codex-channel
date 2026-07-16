package ink.icoding.codex.core.oauth;

/** One server-issued Codex rate-limit reset credit. */
public record OpenAiRateLimitResetCredit(String id, int availableCount) {
}
