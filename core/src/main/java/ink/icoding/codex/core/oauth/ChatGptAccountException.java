package ink.icoding.codex.core.oauth;

/** Indicates that a ChatGPT account or subscription request did not succeed. */
public final class ChatGptAccountException extends RuntimeException {

    private final int statusCode;

    ChatGptAccountException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** Returns the HTTP status code, or {@code -1} when no response was received. */
    public int statusCode() {
        return statusCode;
    }
}
