package ink.icoding.codex.core.oauth;

import java.time.Instant;

/** Account claims decoded locally from an OpenAI ID token. */
public record OpenAiAccount(
        String email,
        String chatgptAccountId,
        String chatgptUserId,
        String planType,
        String organizationId,
        Instant subscriptionExpiresAt) {
}
