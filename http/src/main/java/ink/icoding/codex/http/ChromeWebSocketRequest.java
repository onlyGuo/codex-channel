package ink.icoding.codex.http;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable options for the WebSocket compatibility transport. */
public final class ChromeWebSocketRequest {

    private final URI uri;
    private final Map<String, List<String>> headers;
    private final Duration connectTimeout;
    private final List<String> subprotocols;

    private ChromeWebSocketRequest(Builder builder) {
        this.uri = builder.uri;
        Map<String, List<String>> copied = new LinkedHashMap<>();
        builder.headers.forEach((name, values) -> copied.put(name, List.copyOf(values)));
        this.headers = Map.copyOf(copied);
        this.connectTimeout = builder.connectTimeout;
        this.subprotocols = List.copyOf(builder.subprotocols);
    }

    public static Builder newBuilder(URI uri) {
        return new Builder(uri);
    }

    public URI uri() {
        return uri;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public List<String> subprotocols() {
        return subprotocols;
    }

    public static final class Builder {
        private final URI uri;
        private final Map<String, List<String>> headers = new LinkedHashMap<>();
        private Duration connectTimeout;
        private List<String> subprotocols = List.of();

        private Builder(URI uri) {
            this.uri = Objects.requireNonNull(uri, "uri");
            if (!uri.isAbsolute() || !("ws".equalsIgnoreCase(uri.getScheme())
                    || "wss".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("WebSocket URI must be an absolute ws or wss URI");
            }
        }

        public Builder header(String name, String value) {
            if (name == null || name.isBlank() || value == null
                    || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Invalid WebSocket header");
            }
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            if (Objects.requireNonNull(connectTimeout, "connectTimeout").isNegative()
                    || connectTimeout.isZero()) {
                throw new IllegalArgumentException("connectTimeout must be positive");
            }
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder subprotocols(String mostPreferred, String... lesserPreferred) {
            List<String> values = new ArrayList<>();
            values.add(Objects.requireNonNull(mostPreferred, "mostPreferred"));
            values.addAll(List.of(lesserPreferred));
            if (values.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("WebSocket subprotocols must not be blank");
            }
            this.subprotocols = values;
            return this;
        }

        public ChromeWebSocketRequest build() {
            return new ChromeWebSocketRequest(this);
        }
    }
}
