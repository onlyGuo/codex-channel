package ink.icoding.codex.core.oauth;

import ink.icoding.codex.http.ChromeBodyHandler;
import ink.icoding.codex.http.ChromeHttpCapabilities;
import ink.icoding.codex.http.ChromeHttpRequest;
import ink.icoding.codex.http.ChromeHttpResponse;
import ink.icoding.codex.http.ChromeHttpTransport;
import ink.icoding.codex.http.ChromeWebSocket;
import ink.icoding.codex.http.ChromeWebSocketListener;
import ink.icoding.codex.http.ChromeWebSocketRequest;
import ink.icoding.codex.http.SseEvent;
import ink.icoding.codex.http.SseListener;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Local-server test adapter. Production code never uses this JDK transport. */
final class JdkTestHttpTransport implements ChromeHttpTransport {

    private final HttpClient client = HttpClient.newHttpClient();

    @Override
    public <T> ChromeHttpResponse<T> send(ChromeHttpRequest request, ChromeBodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        byte[] requestBody = null;
        if (request.body().isPresent()) {
            try (InputStream input = request.body().orElseThrow().openStream()) {
                requestBody = input.readAllBytes();
            }
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri());
        request.headers().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("Content-Length")) {
                values.forEach(value -> builder.header(name, value));
            }
        });
        if (request.timeout() != null) {
            builder.timeout(request.timeout());
        }
        boolean explicitlyEmpty = request.headers().entrySet().stream()
                .anyMatch(entry -> entry.getKey().equalsIgnoreCase("Content-Length")
                        && entry.getValue().contains("0"));
        builder.method(request.method(), requestBody == null
                ? (explicitlyEmpty
                        ? HttpRequest.BodyPublishers.ofByteArray(new byte[0])
                        : HttpRequest.BodyPublishers.noBody())
                : HttpRequest.BodyPublishers.ofByteArray(requestBody));
        HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        ChromeHttpResponse.Metadata metadata = new ChromeHttpResponse.Metadata(
                response.statusCode(), response.uri(), response.headers().map());
        Path bodyFile = Files.createTempFile("core-http-test-", ".body");
        try {
            Files.write(bodyFile, response.body());
            return new ChromeHttpResponse<>(response.statusCode(), response.uri(), response.headers().map(),
                    bodyHandler.apply(bodyFile, metadata));
        } finally {
            Files.deleteIfExists(bodyFile);
        }
    }

    @Override
    public void streamSse(ChromeHttpRequest request, SseListener listener)
            throws IOException, InterruptedException {
        ChromeHttpResponse<String> response = send(request, (path, metadata) -> Files.readString(path));
        listener.onOpen(new ChromeHttpResponse.Metadata(
                response.statusCode(), response.uri(), response.headers()));
        parseSse(response.body(), listener);
        listener.onClosed();
    }

    @Override
    public CompletableFuture<ChromeWebSocket> openWebSocket(
            ChromeWebSocketRequest request, ChromeWebSocketListener listener) {
        WebSocket.Builder builder = client.newWebSocketBuilder();
        if (request.connectTimeout() != null) {
            builder.connectTimeout(request.connectTimeout());
        }
        request.headers().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        if (!request.subprotocols().isEmpty()) {
            builder.subprotocols(request.subprotocols().get(0),
                    request.subprotocols().subList(1, request.subprotocols().size()).toArray(String[]::new));
        }
        java.util.concurrent.atomic.AtomicReference<TestWebSocket> session =
                new java.util.concurrent.atomic.AtomicReference<>();
        WebSocket.Listener adapter = new WebSocket.Listener() {
            private TestWebSocket session(WebSocket webSocket) {
                TestWebSocket value = session.get();
                if (value == null) {
                    session.compareAndSet(null, new TestWebSocket(webSocket));
                }
                return session.get();
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
        return builder.buildAsync(request.uri(), adapter)
                .thenApply(webSocket -> session.updateAndGet(value ->
                        value == null ? new TestWebSocket(webSocket) : value));
    }

    @Override
    public ChromeHttpCapabilities capabilities() {
        return new ChromeHttpCapabilities(false, false, false, true);
    }

    private static void parseSse(String body, SseListener listener) {
        String event = null;
        String id = null;
        Duration retry = null;
        StringBuilder data = new StringBuilder();
        for (String line : body.replace("\r\n", "\n").split("\n", -1)) {
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    data.setLength(data.length() - 1);
                    listener.onEvent(new SseEvent(
                            event == null || event.isEmpty() ? "message" : event, data.toString(), id, retry));
                    data.setLength(0);
                    event = null;
                }
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            switch (field) {
                case "event" -> event = value;
                case "data" -> data.append(value).append('\n');
                case "id" -> id = value;
                case "retry" -> {
                    try {
                        retry = Duration.ofMillis(Long.parseLong(value));
                    } catch (NumberFormatException ignored) {
                        // Invalid retry fields are ignored in the test adapter.
                    }
                }
                default -> {
                }
            }
        }
    }

    private record TestWebSocket(WebSocket delegate) implements ChromeWebSocket {
        @Override public void request(long count) { delegate.request(count); }
        @Override public CompletableFuture<ChromeWebSocket> sendText(CharSequence data, boolean last) {
            return delegate.sendText(data, last).thenApply(ignored -> this);
        }
        @Override public CompletableFuture<ChromeWebSocket> sendBinary(ByteBuffer data, boolean last) {
            return delegate.sendBinary(data, last).thenApply(ignored -> this);
        }
        @Override public CompletableFuture<ChromeWebSocket> sendPing(ByteBuffer data) {
            return delegate.sendPing(data).thenApply(ignored -> this);
        }
        @Override public CompletableFuture<ChromeWebSocket> sendPong(ByteBuffer data) {
            return delegate.sendPong(data).thenApply(ignored -> this);
        }
        @Override public CompletableFuture<ChromeWebSocket> sendClose(int status, String reason) {
            return delegate.sendClose(status, reason).thenApply(ignored -> this);
        }
        @Override public void abort() { delegate.abort(); }
    }
}
