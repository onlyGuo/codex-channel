package ink.icoding.codex.http;

/** Raised when no usable curl-impersonate executable can be found. */
public final class CurlImpersonateNotFoundException extends IllegalStateException {

    CurlImpersonateNotFoundException(String message) {
        super(message);
    }

    CurlImpersonateNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
