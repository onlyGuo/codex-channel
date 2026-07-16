package ink.icoding.codex.core.oauth;

/** Supported Codex originator values for the Responses endpoint. */
public enum CodexOriginator {
    CODEX_TUI("codex-tui"),
    CODEX_CLI_RS("codex_cli_rs");

    private final String headerValue;

    CodexOriginator(String headerValue) {
        this.headerValue = headerValue;
    }

    public String headerValue() {
        return headerValue;
    }
}
