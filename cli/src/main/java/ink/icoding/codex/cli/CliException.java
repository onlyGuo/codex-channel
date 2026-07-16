package ink.icoding.codex.cli;

final class CliException extends RuntimeException {
    CliException(String message) {
        super(message);
    }

    CliException(String message, Throwable cause) {
        super(message, cause);
    }
}
