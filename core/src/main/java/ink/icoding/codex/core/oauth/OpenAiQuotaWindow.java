package ink.icoding.codex.core.oauth;

import java.time.Instant;

/** Usage information for one Codex rate-limit window. */
public record OpenAiQuotaWindow(
        Double usedPercent,
        Double remainingPercent,
        Long limitWindowSeconds,
        Instant resetsAt) {
}
