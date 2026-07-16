package ink.icoding.codex.http;

/** Selects whether WebSocket may use the JDK TLS stack. */
public enum WebSocketTlsPolicy {
    /** Use JDK WebSocket; HTTP and SSE still use curl-impersonate. */
    ALLOW_JDK_FALLBACK,
    /** Fail instead of silently opening a WebSocket without Chrome TLS impersonation. */
    REQUIRE_CHROME_IMPERSONATION
}
