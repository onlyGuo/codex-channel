package ink.icoding.codex.http;

import java.io.IOException;

/** An execution, protocol, or size-limit failure reported by the HTTP transport. */
public class ChromeHttpException extends IOException {

    private final int exitCode;

    ChromeHttpException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    ChromeHttpException(String message, int exitCode, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    /** curl process exit code, or {@code -1} when no process exit code exists. */
    public int exitCode() {
        return exitCode;
    }
}
