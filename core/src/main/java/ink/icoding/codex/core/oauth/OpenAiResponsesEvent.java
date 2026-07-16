package ink.icoding.codex.core.oauth;

/** One Server-Sent Event from an OpenAI Responses streaming response. */
public record OpenAiResponsesEvent(String event, String data, String id, Long retry) {
}
