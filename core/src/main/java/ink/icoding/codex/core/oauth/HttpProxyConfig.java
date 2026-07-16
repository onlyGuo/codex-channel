package ink.icoding.codex.core.oauth;

import java.util.Objects;

/** Configuration for an HTTP proxy, optionally with proxy authentication. */
public record HttpProxyConfig(String host, int port, String username, String password) {

    public HttpProxyConfig {
        host = Objects.requireNonNull(host, "host").trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if ((username == null) != (password == null)) {
            throw new IllegalArgumentException("username and password must either both be set or both be null");
        }
    }

    /** Creates an HTTP proxy configuration without authentication. */
    public HttpProxyConfig(String host, int port) {
        this(host, port, null, null);
    }

    boolean hasCredentials() {
        return username != null;
    }
}
