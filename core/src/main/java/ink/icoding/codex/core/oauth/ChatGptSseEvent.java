package ink.icoding.codex.core.oauth;

/** @deprecated Use {@link OpenAiResponsesEvent}. */
@Deprecated
public record ChatGptSseEvent(String event, String data, String id, Long retry) {
}
