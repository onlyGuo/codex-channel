package ink.icoding.codex.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ink.icoding.codex.core.oauth.ChatGptAccountException;
import ink.icoding.codex.core.oauth.OpenAiChatCompletionsListener;
import ink.icoding.codex.core.oauth.OpenAiResponsesEvent;
import ink.icoding.codex.core.oauth.OpenAiResponsesListener;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class ApiServer implements AutoCloseable {

    private static final int MAX_REQUEST_BYTES = 16 * 1024 * 1024;
    private final AccountStore store;
    private final ObjectMapper mapper;
    private final AccountPool pool;
    private final String host;
    private final int port;
    private final String apiKey;
    private final String adminToken;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private HttpServer server;

    ApiServer(AccountStore store, String host, int port, String apiKey, String adminToken) {
        this.store = store;
        this.mapper = store.mapper();
        this.pool = new AccountPool(store);
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.adminToken = Objects.requireNonNull(adminToken, "adminToken");
    }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "codex-channel-api");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/health", this::health);
        server.createContext("/v1/models", this::models);
        server.createContext("/v1/responses", this::responses);
        server.createContext("/v1/chat/completions", this::chatCompletions);
        server.createContext("/_admin/status", this::adminStatus);
        server.createContext("/_admin/stop", this::adminStop);
        server.start();
        int effectivePort = server.getAddress().getPort();
        store.writeServiceState(new ServiceState(
                ProcessHandle.current().pid(), host, effectivePort, adminToken, Instant.now()));
    }

    void await() throws InterruptedException {
        stopped.await();
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, Map.of("status", "ok", "account_count", pool.status().size()));
    }

    private void models(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        if (!authorize(exchange)) return;
        try {
            AccountPool.ScheduledResult<JsonNode> result = pool.execute(client -> client.fetchModels());
            exchange.getResponseHeaders().set("X-Codex-Account", result.accountAlias());
            sendJson(exchange, 200, result.value());
        } catch (RuntimeException exception) {
            sendError(exchange, exception);
        }
    }

    private void responses(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        if (!authorize(exchange)) return;
        JsonNode request;
        try {
            request = readJson(exchange);
        } catch (RuntimeException exception) {
            sendError(exchange, exception);
            return;
        }
        if (request.path("stream").asBoolean(false)) {
            streamResponses(exchange, request);
            return;
        }
        try {
            AccountPool.ScheduledResult<JsonNode> result = pool.execute(client -> client.createResponse(request));
            exchange.getResponseHeaders().set("X-Codex-Account", result.accountAlias());
            sendJson(exchange, 200, result.value());
        } catch (RuntimeException exception) {
            sendError(exchange, exception);
        }
    }

    private void chatCompletions(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        if (!authorize(exchange)) return;
        JsonNode request;
        try {
            request = readJson(exchange);
        } catch (RuntimeException exception) {
            sendError(exchange, exception);
            return;
        }
        if (request.path("stream").asBoolean(false)) {
            streamChatCompletions(exchange, request);
            return;
        }
        try {
            AccountPool.ScheduledResult<JsonNode> result = pool.execute(client -> client.createChatCompletion(request));
            exchange.getResponseHeaders().set("X-Codex-Account", result.accountAlias());
            sendJson(exchange, 200, result.value());
        } catch (RuntimeException exception) {
            sendError(exchange, exception);
        }
    }

    private void streamResponses(HttpExchange exchange, JsonNode request) throws IOException {
        AccountPool.AccountLease lease;
        try {
            lease = pool.acquire();
        } catch (RuntimeException exception) {
            sendError(exchange, exception);
            return;
        }
        SseWriter writer = new SseWriter(exchange, lease.alias());
        try {
            lease.client().streamResponses(request, new OpenAiResponsesListener() {
                @Override public void onOpen() { writer.open(); }
                @Override public void onEvent(OpenAiResponsesEvent event) {
                    writer.event(event.event(), event.data());
                }
                @Override public void onComplete() { writer.close(); }
                @Override public void onError(Throwable error) { writer.error(error); }
            });
            lease.success();
        } catch (RuntimeException exception) {
            lease.failure(exception);
            if (!writer.opened()) {
                sendError(exchange, exception);
            } else {
                writer.error(exception);
            }
        } finally {
            writer.close();
        }
    }

    private void streamChatCompletions(HttpExchange exchange, JsonNode request) throws IOException {
        AccountPool.AccountLease lease;
        try {
            lease = pool.acquire();
        } catch (RuntimeException exception) {
            sendError(exchange, exception);
            return;
        }
        SseWriter writer = new SseWriter(exchange, lease.alias());
        try {
            lease.client().streamChatCompletions(request, new OpenAiChatCompletionsListener() {
                @Override public void onOpen() { writer.open(); }
                @Override public void onChunk(JsonNode chunk) { writer.data(chunk.toString()); }
                @Override public void onComplete() { writer.data("[DONE]"); writer.close(); }
                @Override public void onError(Throwable error) { writer.error(error); }
            });
            lease.success();
        } catch (RuntimeException exception) {
            lease.failure(exception);
            if (!writer.opened()) {
                sendError(exchange, exception);
            } else {
                writer.error(exception);
            }
        } finally {
            writer.close();
        }
    }

    private void adminStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange); return; }
        if (!adminAuthorized(exchange)) {
            sendJson(exchange, 401, errorBody("Invalid admin token", "authentication_error"));
            return;
        }
        sendJson(exchange, 200, Map.of(
                "pid", ProcessHandle.current().pid(),
                "host", host,
                "port", server.getAddress().getPort(),
                "accounts", pool.status()));
    }

    private void adminStop(HttpExchange exchange) throws IOException {
        if (!adminAuthorized(exchange)) {
            sendJson(exchange, 401, errorBody("Invalid admin token", "authentication_error"));
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, Map.of("stopping", true));
        CompletableFutureCompat.runAsync(this::close);
    }

    private JsonNode readJson(HttpExchange exchange) {
        try {
            long declared = parseLong(exchange.getRequestHeaders().getFirst("Content-Length"), -1);
            if (declared > MAX_REQUEST_BYTES) {
                throw new CliException("Request body exceeds " + MAX_REQUEST_BYTES + " bytes");
            }
            byte[] bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) {
                throw new CliException("Request body exceeds " + MAX_REQUEST_BYTES + " bytes");
            }
            JsonNode value = mapper.readTree(bytes);
            if (value == null || !value.isObject()) {
                throw new CliException("Request body must be a JSON object");
            }
            return value;
        } catch (IOException exception) {
            throw new CliException("Could not read request body", exception);
        }
    }

    private boolean authorize(HttpExchange exchange) throws IOException {
        if (apiKey.isBlank()) {
            return true;
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        boolean accepted = authorization != null && authorization.startsWith("Bearer ")
                && secureEquals(apiKey, authorization.substring("Bearer ".length()));
        if (!accepted) {
            sendJson(exchange, 401, errorBody("Invalid API key", "authentication_error"));
        }
        return accepted;
    }

    private boolean adminAuthorized(HttpExchange exchange) {
        return secureEquals(adminToken, exchange.getRequestHeaders().getFirst("X-Admin-Token"));
    }

    private void sendError(HttpExchange exchange, Throwable error) throws IOException {
        if (exchange.getResponseCode() != -1) {
            exchange.close();
            return;
        }
        int status = 500;
        if (error instanceof ChatGptAccountException accountException) {
            int upstream = accountException.statusCode();
            status = upstream >= 400 && upstream <= 599 ? upstream : 502;
        } else if (error instanceof CliException) {
            String message = error.getMessage();
            status = message != null && (message.startsWith("No account") || message.startsWith("All enabled"))
                    ? 503 : 400;
        }
        sendJson(exchange, status, errorBody(
                error.getMessage() == null ? "Request failed" : error.getMessage(), "upstream_error"));
    }

    private Map<String, Object> errorBody(String message, String type) {
        return Map.of("error", Map.of("message", message, "type", type, "code", type));
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = mapper.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void methodNotAllowed(HttpExchange exchange) throws IOException {
        byte[] body = "Method Not Allowed".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(405, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static boolean secureEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static long parseLong(String value, long fallback) {
        try { return value == null ? fallback : Long.parseLong(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (server != null) server.stop(1);
            pool.close();
            try { store.clearServiceState(); } catch (RuntimeException ignored) { }
            stopped.countDown();
        }
    }

    private final class SseWriter {
        private final HttpExchange exchange;
        private final String alias;
        private OutputStream output;
        private boolean opened;
        private boolean finished;

        private SseWriter(HttpExchange exchange, String alias) {
            this.exchange = exchange;
            this.alias = alias;
        }

        synchronized boolean opened() { return opened; }

        synchronized void open() {
            if (opened) return;
            try {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.getResponseHeaders().set("X-Codex-Account", alias);
                exchange.sendResponseHeaders(200, 0);
                output = exchange.getResponseBody();
                opened = true;
            } catch (IOException exception) {
                throw new CliException("Could not open SSE response", exception);
            }
        }

        synchronized void event(String event, String data) {
            open();
            write("event: " + event + "\n" + sseData(data) + "\n");
        }

        synchronized void data(String data) {
            open();
            write(sseData(data) + "\n");
        }

        synchronized void error(Throwable error) {
            if (finished) return;
            open();
            try {
                ObjectNode value = mapper.createObjectNode();
                value.put("type", "error");
                value.put("message", error.getMessage() == null ? "Stream failed" : error.getMessage());
                event("error", value.toString());
            } catch (RuntimeException ignored) {
                close();
            }
        }

        synchronized void close() {
            if (finished) return;
            finished = true;
            try { if (output != null) output.close(); } catch (IOException ignored) { }
            exchange.close();
        }

        private void write(String value) {
            if (finished) return;
            try {
                output.write(value.getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (IOException exception) {
                throw new CliException("Could not write SSE response", exception);
            }
        }

        private String sseData(String data) {
            StringBuilder value = new StringBuilder();
            for (String line : data.split("\n", -1)) value.append("data: ").append(line).append('\n');
            return value.toString();
        }
    }

    private static final class CompletableFutureCompat {
        static void runAsync(Runnable runnable) {
            Thread thread = new Thread(runnable, "codex-channel-stop");
            thread.setDaemon(true);
            thread.start();
        }
    }
}
