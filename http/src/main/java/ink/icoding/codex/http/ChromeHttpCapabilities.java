package ink.icoding.codex.http;

/** Describes which protocols use the curl-impersonate Chrome TLS stack. */
public record ChromeHttpCapabilities(
        boolean httpChromeTls,
        boolean sseChromeTls,
        boolean webSocketChromeTls,
        boolean webSocketAvailable) {
}
