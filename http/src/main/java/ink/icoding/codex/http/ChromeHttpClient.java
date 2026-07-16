package ink.icoding.codex.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * HTTP client backed by the curl-impersonate command line executable.
 * HTTP requests and SSE use curl's Chrome TLS/HTTP2 fingerprint.
 */
public final class ChromeHttpClient implements ChromeHttpTransport {

    private static final int MAX_DIAGNOSTIC_BYTES = 32 * 1024;

    private final Path executable;
    private final ChromeProfile profile;
    private final CurlProfileSelection profileSelection;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final boolean followRedirects;
    private final ChromeProxy proxy;
    private final Path cookieJar;
    private final boolean ownedCookieJar;
    private final boolean insecure;
    private final Path caCertificate;
    private final long maxResponseBytes;
    private final Map<String, List<String>> defaultHeaders;
    private final ExecutorService executor;
    private final boolean ownedExecutor;
    private final WebSocketTlsPolicy webSocketTlsPolicy;
    private final ReentrantLock cookieLock = new ReentrantLock();
    private final java.util.Set<SseCall> activeSseCalls = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private ChromeHttpClient(Builder builder) {
        this.executable = builder.executable == null
                ? CurlImpersonateResolver.requireExecutable() : validateExecutable(builder.executable);
        this.profile = builder.profile;
        this.profileSelection = builder.profileSelection;
        this.connectTimeout = builder.connectTimeout;
        this.requestTimeout = builder.requestTimeout;
        this.followRedirects = builder.followRedirects;
        this.proxy = builder.proxy;
        this.insecure = builder.insecure;
        this.caCertificate = builder.caCertificate;
        this.maxResponseBytes = builder.maxResponseBytes;
        this.defaultHeaders = immutableHeaders(builder.defaultHeaders);
        this.executor = builder.executor == null
                ? Executors.newCachedThreadPool(runnable -> {
                    Thread thread = new Thread(runnable, "chrome-http-client");
                    thread.setDaemon(true);
                    return thread;
                }) : builder.executor;
        this.ownedExecutor = builder.executor == null;
        this.webSocketTlsPolicy = builder.webSocketTlsPolicy;
        try {
            if (!builder.cookiesEnabled) {
                this.cookieJar = null;
                this.ownedCookieJar = false;
            } else if (builder.cookieJar != null) {
                this.cookieJar = builder.cookieJar.toAbsolutePath().normalize();
                Path parent = this.cookieJar.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                if (!Files.exists(this.cookieJar)) {
                    Files.createFile(this.cookieJar);
                }
                this.ownedCookieJar = false;
            } else {
                this.cookieJar = Files.createTempFile("codex-chrome-cookies-", ".txt");
                this.ownedCookieJar = true;
            }
        } catch (IOException exception) {
            if (ownedExecutor) {
                executor.shutdownNow();
            }
            throw new IllegalStateException("Could not initialize curl cookie storage", exception);
        }
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static ChromeHttpClient create() {
        return newBuilder().build();
    }

    public ChromeProfile profile() {
        return profile;
    }

    public Path executable() {
        return executable;
    }

    public ChromeHttpCapabilities capabilities() {
        return new ChromeHttpCapabilities(true, true, false,
                webSocketTlsPolicy == WebSocketTlsPolicy.ALLOW_JDK_FALLBACK);
    }

    public <T> ChromeHttpResponse<T> send(ChromeHttpRequest request, ChromeBodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        ensureOpen();
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(bodyHandler, "bodyHandler");
        if (cookieJar != null) {
            cookieLock.lockInterruptibly();
        }
        Path directory = null;
        try {
            directory = Files.createTempDirectory("codex-chrome-http-");
            Path headers = directory.resolve("response-headers.txt");
            Path body = directory.resolve("response-body.bin");
            String marker = "__CODEX_HTTP_" + UUID.randomUUID() + "__";
            List<String> command = commandFor(request, directory);
            command.add("--dump-header");
            command.add(headers.toString());
            command.add("--output");
            command.add(body.toString());
            command.add("--write-out");
            command.add("\n" + marker + "%{url_effective}");

            Process process = processBuilder(command).redirectErrorStream(true).start();
            byte[] diagnostics = readLimited(process.getInputStream(), MAX_DIAGNOSTIC_BYTES);
            int exitCode = process.waitFor();
            String output = new String(diagnostics, StandardCharsets.UTF_8);
            if (exitCode != 0) {
                throw new ChromeHttpException(curlFailure(exitCode, output), exitCode);
            }
            URI effectiveUri = parseEffectiveUri(output, marker, request.uri());
            ChromeHttpResponse.Metadata metadata = CurlResponseParser.parse(headers, effectiveUri);
            long size = Files.size(body);
            if (size > maxResponseBytes) {
                throw new ChromeHttpException(
                        "Response body exceeded limit of " + maxResponseBytes + " bytes (received " + size + ")", -1);
            }
            T converted = bodyHandler.apply(body, metadata);
            return new ChromeHttpResponse<>(
                    metadata.statusCode(), metadata.uri(), metadata.headers(), converted);
        } finally {
            deleteRecursively(directory);
            if (cookieJar != null) {
                cookieLock.unlock();
            }
        }
    }

    public ChromeHttpResponse<String> send(ChromeHttpRequest request)
            throws IOException, InterruptedException {
        return send(request, ChromeBodyHandlers.ofString());
    }

    public <T> CompletableFuture<ChromeHttpResponse<T>> sendAsync(
            ChromeHttpRequest request, ChromeBodyHandler<T> bodyHandler) {
        ensureOpen();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return send(request, bodyHandler);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CompletionException(exception);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    /** Starts an asynchronous SSE exchange using curl-impersonate and returns its cancellation handle. */
    public SseCall openSse(ChromeHttpRequest request, SseListener listener) {
        ensureOpen();
        return new SseCall(this, request, listener);
    }

    /** Waits on the calling thread while SSE callbacks are delivered by the client executor. */
    public void streamSse(ChromeHttpRequest request, SseListener listener)
            throws IOException, InterruptedException {
        openSse(request, listener).await();
    }

    /**
     * Opens a WebSocket using the JDK transport. The curl-impersonate CLI cannot carry WebSocket
     * frames, so this method is rejected when strict Chrome TLS impersonation is required.
     */
    public CompletableFuture<ChromeWebSocket> openWebSocket(
            ChromeWebSocketRequest request, ChromeWebSocketListener listener) {
        ensureOpen();
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(listener, "listener");
        if (webSocketTlsPolicy == WebSocketTlsPolicy.REQUIRE_CHROME_IMPERSONATION) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException(
                    "curl-impersonate CLI does not expose a WebSocket frame transport; "
                            + "use ALLOW_JDK_FALLBACK or provide a native libcurl WebSocket transport"));
        }
        HttpClient.Builder clientBuilder = HttpClient.newBuilder().executor(executor);
        if (proxy != null) {
            String scheme = proxy.uri().getScheme().toLowerCase(java.util.Locale.ROOT);
            if (scheme.startsWith("socks")) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException(
                        "JDK WebSocket fallback does not support SOCKS proxies"));
            }
            int port = proxy.uri().getPort() >= 0 ? proxy.uri().getPort()
                    : ("https".equals(scheme) ? 443 : 80);
            clientBuilder.proxy(ProxySelector.of(new InetSocketAddress(proxy.uri().getHost(), port)));
            if (proxy.hasCredentials()) {
                clientBuilder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(proxy.username(), proxy.password().toCharArray());
                    }
                });
            }
        }
        WebSocket.Builder webSocketBuilder = clientBuilder.build().newWebSocketBuilder();
        Duration timeout = request.connectTimeout() == null ? connectTimeout : request.connectTimeout();
        webSocketBuilder.connectTimeout(timeout);
        request.headers().forEach((name, values) -> values.forEach(value -> webSocketBuilder.header(name, value)));
        if (!request.subprotocols().isEmpty()) {
            webSocketBuilder.subprotocols(
                    request.subprotocols().get(0),
                    request.subprotocols().subList(1, request.subprotocols().size()).toArray(String[]::new));
        }
        java.util.concurrent.atomic.AtomicReference<JdkChromeWebSocket> session =
                new java.util.concurrent.atomic.AtomicReference<>();
        WebSocket.Listener adapter = new WebSocket.Listener() {
            private JdkChromeWebSocket session(WebSocket webSocket) {
                JdkChromeWebSocket current = session.get();
                if (current != null) {
                    return current;
                }
                JdkChromeWebSocket created = new JdkChromeWebSocket(webSocket);
                return session.compareAndSet(null, created) ? created : session.get();
            }

            @Override
            public void onOpen(WebSocket webSocket) {
                listener.onOpen(session(webSocket));
            }

            @Override
            public java.util.concurrent.CompletionStage<?> onText(
                    WebSocket webSocket, CharSequence data, boolean last) {
                listener.onText(session(webSocket), data, last);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public java.util.concurrent.CompletionStage<?> onBinary(
                    WebSocket webSocket, ByteBuffer data, boolean last) {
                listener.onBinary(session(webSocket), data, last);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public java.util.concurrent.CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
                listener.onPing(session(webSocket), message);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public java.util.concurrent.CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
                listener.onPong(session(webSocket), message);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public java.util.concurrent.CompletionStage<?> onClose(
                    WebSocket webSocket, int statusCode, String reason) {
                listener.onClose(session(webSocket), statusCode, reason);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                listener.onError(session(webSocket), error);
            }
        };
        return webSocketBuilder.buildAsync(request.uri(), adapter)
                .thenApply(webSocket -> session.updateAndGet(current ->
                        current == null ? new JdkChromeWebSocket(webSocket) : current));
    }

    List<String> commandFor(ChromeHttpRequest request, Path directory) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("--silent");
        command.add("--show-error");
        command.add("--compressed");
        if (usesProfileArgument()) {
            command.add("--impersonate");
            command.add(profile.name());
        }
        command.add("--request");
        command.add(request.method());
        command.add("--url");
        command.add(request.uri().toASCIIString());
        command.add("--connect-timeout");
        command.add(seconds(connectTimeout));
        Duration timeout = request.timeout() == null ? requestTimeout : request.timeout();
        command.add("--max-time");
        command.add(seconds(timeout));
        command.add("--max-filesize");
        command.add(Long.toString(maxResponseBytes));
        boolean redirects = request.followRedirects() == null ? followRedirects : request.followRedirects();
        command.add(redirects ? "--location" : "--no-location");
        if (proxy != null) {
            command.add("--proxy");
            command.add(proxy.uri().toASCIIString());
            if (proxy.hasCredentials()) {
                command.add("--proxy-user");
                command.add(proxy.username() + ":" + proxy.password());
            }
        }
        if (cookieJar != null) {
            command.add("--cookie");
            command.add(cookieJar.toString());
            command.add("--cookie-jar");
            command.add(cookieJar.toString());
        }
        if (insecure) {
            command.add("--insecure");
        }
        if (caCertificate != null) {
            command.add("--cacert");
            command.add(caCertificate.toString());
        }
        mergedHeaders(request).forEach((name, values) -> values.forEach(value -> {
            command.add("--header");
            command.add(name + ": " + value);
        }));
        if (request.body().isPresent()) {
            Path body = request.body().orElseThrow().materialize(directory);
            command.add("--data-binary");
            command.add("@" + body);
        }
        return command;
    }

    ExecutorService executor() {
        return executor;
    }

    ReentrantLock cookieLock() {
        return cookieLock;
    }

    Path cookieJar() {
        return cookieJar;
    }

    long maxResponseBytes() {
        return maxResponseBytes;
    }

    ProcessBuilder processBuilder(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (profileSelection == CurlProfileSelection.ENVIRONMENT) {
            builder.environment().put("CURL_IMPERSONATE", profile.name());
            builder.environment().put("CURL_IMPERSONATE_HEADERS", "yes");
        }
        return builder;
    }

    synchronized void register(SseCall call) {
        ensureOpen();
        activeSseCalls.add(call);
    }

    void unregister(SseCall call) {
        activeSseCalls.remove(call);
    }

    void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("ChromeHttpClient is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        List.copyOf(activeSseCalls).forEach(SseCall::close);
        if (ownedExecutor) {
            executor.shutdownNow();
        }
        if (ownedCookieJar) {
            try {
                Files.deleteIfExists(cookieJar);
            } catch (IOException ignored) {
                // A best-effort cleanup; cookie files live in the system temporary directory.
            }
        }
    }

    private Map<String, List<String>> mergedHeaders(ChromeHttpRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>(defaultHeaders);
        request.headers().forEach((name, values) -> {
            headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
            headers.put(name, values);
        });
        return headers;
    }

    private static Path validateExecutable(Path executable) {
        Path path = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
            throw new IllegalArgumentException("curl-impersonate executable is not executable: " + path);
        }
        return path;
    }

    private boolean usesProfileArgument() {
        return switch (profileSelection) {
            case ARGUMENT -> true;
            case ENVIRONMENT, WRAPPER -> false;
            case AUTO -> !executable.getFileName().toString().matches("(?i)curl_chrome(?:[0-9]+)?(?:\\.exe)?");
        };
    }

    private record JdkChromeWebSocket(WebSocket delegate) implements ChromeWebSocket {
        @Override
        public void request(long messageCount) {
            delegate.request(messageCount);
        }

        @Override
        public CompletableFuture<ChromeWebSocket> sendText(CharSequence data, boolean last) {
            return delegate.sendText(data, last).thenApply(ignored -> this);
        }

        @Override
        public CompletableFuture<ChromeWebSocket> sendBinary(ByteBuffer data, boolean last) {
            return delegate.sendBinary(data, last).thenApply(ignored -> this);
        }

        @Override
        public CompletableFuture<ChromeWebSocket> sendPing(ByteBuffer message) {
            return delegate.sendPing(message).thenApply(ignored -> this);
        }

        @Override
        public CompletableFuture<ChromeWebSocket> sendPong(ByteBuffer message) {
            return delegate.sendPong(message).thenApply(ignored -> this);
        }

        @Override
        public CompletableFuture<ChromeWebSocket> sendClose(int statusCode, String reason) {
            return delegate.sendClose(statusCode, reason).thenApply(ignored -> this);
        }

        @Override
        public void abort() {
            delegate.abort();
        }
    }

    private static Map<String, List<String>> immutableHeaders(Map<String, List<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((name, values) -> result.put(name, List.copyOf(values)));
        return Map.copyOf(result);
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            int remaining = limit - output.size();
            if (remaining > 0) {
                output.write(buffer, 0, Math.min(read, remaining));
            }
        }
        return output.toByteArray();
    }

    private static URI parseEffectiveUri(String output, String marker, URI fallback) {
        int markerIndex = output.lastIndexOf(marker);
        if (markerIndex < 0) {
            return fallback;
        }
        String value = output.substring(markerIndex + marker.length()).strip();
        try {
            return value.isEmpty() ? fallback : URI.create(value);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static String curlFailure(int exitCode, String diagnostic) {
        String message = diagnostic.strip();
        return "curl-impersonate exited with code " + exitCode + (message.isEmpty() ? "" : ": " + message);
    }

    private static String seconds(Duration duration) {
        long millis = Math.max(1, duration.toMillis());
        return String.format(java.util.Locale.ROOT, "%.3f", millis / 1000.0);
    }

    static void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary transport files are cleaned up on a best-effort basis.
                }
            });
        } catch (IOException ignored) {
            // Temporary transport files are cleaned up on a best-effort basis.
        }
    }

    public static final class Builder {
        private Path executable;
        private ChromeProfile profile = ChromeProfile.CHROME;
        private CurlProfileSelection profileSelection = CurlProfileSelection.AUTO;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(60);
        private boolean followRedirects = true;
        private ChromeProxy proxy;
        private Path cookieJar;
        private boolean cookiesEnabled = true;
        private boolean insecure;
        private Path caCertificate;
        private long maxResponseBytes = 64L * 1024 * 1024;
        private final Map<String, List<String>> defaultHeaders = new LinkedHashMap<>();
        private ExecutorService executor;
        private WebSocketTlsPolicy webSocketTlsPolicy = WebSocketTlsPolicy.ALLOW_JDK_FALLBACK;

        private Builder() {
        }

        public Builder executable(Path executable) {
            this.executable = executable;
            return this;
        }

        public Builder profile(ChromeProfile profile) {
            this.profile = Objects.requireNonNull(profile, "profile");
            return this;
        }

        public Builder profileSelection(CurlProfileSelection profileSelection) {
            this.profileSelection = Objects.requireNonNull(profileSelection, "profileSelection");
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = positive(connectTimeout, "connectTimeout");
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = positive(requestTimeout, "requestTimeout");
            return this;
        }

        public Builder followRedirects(boolean followRedirects) {
            this.followRedirects = followRedirects;
            return this;
        }

        public Builder proxy(ChromeProxy proxy) {
            this.proxy = proxy;
            return this;
        }

        public Builder cookieJar(Path cookieJar) {
            this.cookieJar = Objects.requireNonNull(cookieJar, "cookieJar");
            this.cookiesEnabled = true;
            return this;
        }

        public Builder cookiesEnabled(boolean cookiesEnabled) {
            this.cookiesEnabled = cookiesEnabled;
            return this;
        }

        public Builder insecure(boolean insecure) {
            this.insecure = insecure;
            return this;
        }

        public Builder caCertificate(Path caCertificate) {
            Path path = Objects.requireNonNull(caCertificate, "caCertificate").toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("CA certificate does not exist: " + path);
            }
            this.caCertificate = path;
            return this;
        }

        public Builder maxResponseBytes(long maxResponseBytes) {
            if (maxResponseBytes < 1) {
                throw new IllegalArgumentException("maxResponseBytes must be positive");
            }
            this.maxResponseBytes = maxResponseBytes;
            return this;
        }

        public Builder defaultHeader(String name, String value) {
            ChromeHttpRequest.newBuilder("https://localhost").header(name, value);
            defaultHeaders.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            return this;
        }

        public Builder executor(ExecutorService executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        public Builder webSocketTlsPolicy(WebSocketTlsPolicy policy) {
            this.webSocketTlsPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        public ChromeHttpClient build() {
            if (!cookiesEnabled && cookieJar != null) {
                throw new IllegalStateException("cookieJar and cookiesEnabled(false) cannot be combined");
            }
            return new ChromeHttpClient(this);
        }

        private static Duration positive(Duration duration, String name) {
            if (Objects.requireNonNull(duration, name).isZero() || duration.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return duration;
        }
    }
}
