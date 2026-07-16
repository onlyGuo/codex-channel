package ink.icoding.codex.core.oauth;

/** Indicates that OpenAI did not return a successful OAuth token response. */
public final class OpenAiTokenExchangeException extends RuntimeException {

    private final int statusCode;

    OpenAiTokenExchangeException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** Returns the HTTP status code, or {@code -1} when no response was received. */
    public int statusCode() {
        return statusCode;
    }
}
