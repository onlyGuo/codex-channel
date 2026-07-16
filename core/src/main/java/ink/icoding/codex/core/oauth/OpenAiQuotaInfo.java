package ink.icoding.codex.core.oauth;

/** Codex quota windows: primary is five-hour and secondary is weekly. */
public record OpenAiQuotaInfo(OpenAiQuotaWindow fiveHour, OpenAiQuotaWindow weekly) {
}
