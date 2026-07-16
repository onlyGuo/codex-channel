package ink.icoding.codex.core.oauth;

/** Indicates that an ID token cannot be decoded or has expired. */
public final class OpenAiIdTokenException extends RuntimeException {

    OpenAiIdTokenException(String message) {
        super(message);
    }

    OpenAiIdTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
