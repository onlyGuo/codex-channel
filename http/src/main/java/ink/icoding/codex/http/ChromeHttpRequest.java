package ink.icoding.codex.http;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable request consumed by {@link ChromeHttpClient}. */
public final class ChromeHttpRequest {

    private final URI uri;
    private final String method;
    private final Map<String, List<String>> headers;
    private final ChromeRequestBody body;
    private final Duration timeout;
    private final Boolean followRedirects;

    private ChromeHttpRequest(Builder builder) {
        this.uri = builder.uri;
        this.method = builder.method;
        Map<String, List<String>> copied = new LinkedHashMap<>();
        builder.headers.forEach((name, values) -> copied.put(name, List.copyOf(values)));
        this.headers = Collections.unmodifiableMap(copied);
        this.body = builder.body;
        this.timeout = builder.timeout;
        this.followRedirects = builder.followRedirects;
    }

    public static Builder newBuilder(URI uri) {
        return new Builder(uri);
    }

    public static Builder newBuilder(String uri) {
        return new Builder(URI.create(uri));
    }

    public URI uri() {
        return uri;
    }

    public String method() {
        return method;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public Duration timeout() {
        return timeout;
    }

    public Boolean followRedirects() {
        return followRedirects;
    }

    public Optional<ChromeRequestBody> body() {
        return Optional.ofNullable(body);
    }

    private record ByteArrayBody(byte[] value) implements ChromeRequestBody {
        @Override
        public InputStream openStream() {
            return new ByteArrayInputStream(value);
        }

        @Override
        public long contentLength() {
            return value.length;
        }

        @Override
        public Path materialize(Path directory) throws java.io.IOException {
            Path path = directory.resolve("request-body.bin");
            Files.write(path, value);
            return path;
        }
    }

    private record FileBody(Path value) implements ChromeRequestBody {
        @Override
        public InputStream openStream() throws java.io.IOException {
            return Files.newInputStream(value);
        }

        @Override
        public long contentLength() throws java.io.IOException {
            return Files.size(value);
        }

        @Override
        public Path materialize(Path directory) {
            return value;
        }
    }

    public static final class Builder {
        private final URI uri;
        private String method = "GET";
        private final Map<String, List<String>> headers = new LinkedHashMap<>();
        private ChromeRequestBody body;
        private Duration timeout;
        private Boolean followRedirects;

        private Builder(URI uri) {
            this.uri = validateUri(uri);
        }

        public Builder GET() {
            return method("GET");
        }

        public Builder DELETE() {
            return method("DELETE");
        }

        public Builder POST(String body) {
            return method("POST", body, StandardCharsets.UTF_8);
        }

        public Builder PUT(String body) {
            return method("PUT", body, StandardCharsets.UTF_8);
        }

        public Builder PATCH(String body) {
            return method("PATCH", body, StandardCharsets.UTF_8);
        }

        public Builder method(String method) {
            this.method = validateMethod(method);
            this.body = null;
            return this;
        }

        /**
         * Selects a method with an explicitly framed zero-length body. Some CDNs distinguish this
         * from a method-only request that omits {@code Content-Length} entirely.
         */
        public Builder methodWithEmptyBody(String method) {
            method(method);
            return setHeader("Content-Length", "0");
        }

        public Builder method(String method, String body) {
            return method(method, body, StandardCharsets.UTF_8);
        }

        public Builder method(String method, String body, Charset charset) {
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(charset, "charset");
            return method(method, body.getBytes(charset));
        }

        public Builder method(String method, byte[] body) {
            this.method = validateMethod(method);
            this.body = new ByteArrayBody(Arrays.copyOf(Objects.requireNonNull(body, "body"), body.length));
            return this;
        }

        public Builder method(String method, Path bodyFile) {
            this.method = validateMethod(method);
            Path path = Objects.requireNonNull(bodyFile, "bodyFile").toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("Request body file does not exist: " + path);
            }
            this.body = new FileBody(path);
            return this;
        }

        public Builder header(String name, String value) {
            validateHeader(name, value);
            String existing = findHeader(name);
            headers.computeIfAbsent(existing == null ? name : existing, ignored -> new ArrayList<>()).add(value);
            return this;
        }

        public Builder setHeader(String name, String value) {
            validateHeader(name, value);
            String existing = findHeader(name);
            if (existing != null) {
                headers.remove(existing);
            }
            headers.put(name, new ArrayList<>(List.of(value)));
            return this;
        }

        public Builder timeout(Duration timeout) {
            if (Objects.requireNonNull(timeout, "timeout").isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        public Builder followRedirects(boolean followRedirects) {
            this.followRedirects = followRedirects;
            return this;
        }

        public ChromeHttpRequest build() {
            return new ChromeHttpRequest(this);
        }

        private String findHeader(String name) {
            return headers.keySet().stream()
                    .filter(candidate -> candidate.equalsIgnoreCase(name))
                    .findFirst().orElse(null);
        }
    }

    private static URI validateUri(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (!uri.isAbsolute() || uri.getHost() == null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Request URI must be an absolute http or https URI");
        }
        return uri;
    }

    private static String validateMethod(String method) {
        String value = Objects.requireNonNull(method, "method").trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z][A-Z0-9!#$%&'*+.^_`|~-]*")) {
            throw new IllegalArgumentException("Invalid HTTP method: " + method);
        }
        return value;
    }

    private static void validateHeader(String name, String value) {
        if (name == null || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
            throw new IllegalArgumentException("Invalid HTTP header name: " + name);
        }
        if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("HTTP header values must not contain CR or LF");
        }
    }
}
