package ink.icoding.codex.http;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** HTTP, HTTPS, or SOCKS proxy configuration accepted by curl. */
public record ChromeProxy(URI uri, String username, String password) {

    public ChromeProxy {
        Objects.requireNonNull(uri, "uri");
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https")
                || scheme.equals("socks4") || scheme.equals("socks4a")
                || scheme.equals("socks5") || scheme.equals("socks5h"))) {
            throw new IllegalArgumentException("Proxy scheme must be http, https, socks4(a), or socks5(h)");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Proxy URI must contain a host");
        }
        if ((username == null) != (password == null)) {
            throw new IllegalArgumentException("Proxy username and password must both be set or both be null");
        }
    }

    public ChromeProxy(URI uri) {
        this(uri, null, null);
    }

    boolean hasCredentials() {
        return username != null;
    }
}
