package ink.icoding.codex.core.oauth;

import java.util.List;

/** Available Codex rate-limit reset credits and the account-level credit counters. */
public record OpenAiRateLimitResetCredits(
        List<OpenAiRateLimitResetCredit> credits,
        int availableCount,
        int totalEarnedCount) {

    public OpenAiRateLimitResetCredits {
        credits = List.copyOf(credits);
    }
}
