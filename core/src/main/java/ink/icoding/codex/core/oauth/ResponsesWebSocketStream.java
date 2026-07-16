package ink.icoding.codex.core.oauth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ink.icoding.codex.http.ChromeHttpTransport;
import ink.icoding.codex.http.ChromeWebSocket;
import ink.icoding.codex.http.ChromeWebSocketListener;
import ink.icoding.codex.http.ChromeWebSocketRequest;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Blocking facade around the Responses WebSocket wire protocol used by Codex. */
final class ResponsesWebSocketStream {

    private ResponsesWebSocketStream() {
    }

    static void stream(
            ChromeHttpTransport httpClient,
            ObjectMapper objectMapper,
            URI responsesEndpoint,
            JsonNode wireRequest,
            String accessToken,
            String accountId,
            CodexOriginator originator,
            String userAgent,
            OpenAiResponsesListener listener) {
        CompletableFuture<Void> terminal = new CompletableFuture<>();
        AtomicBoolean opened = new AtomicBoolean();
        ChromeWebSocketListener webSocketListener = new ChromeWebSocketListener() {
            private final StringBuilder text = new StringBuilder();

            @Override
            public void onOpen(ChromeWebSocket webSocket) {
                opened.set(true);
                listener.onOpen();
                webSocket.request(1);
            }

            @Override
            public void onText(ChromeWebSocket webSocket, CharSequence data, boolean last) {
                text.append(data);
                if (last) {
                    String payload = text.toString();
                    text.setLength(0);
                    try {
                        JsonNode json = objectMapper.readTree(payload);
                        String type = json.path("type").asText("message");
                        listener.onEvent(new OpenAiResponsesEvent(type, payload, null, null));
                        if (isTerminal(type)) {
                            listener.onComplete();
                            terminal.complete(null);
                            webSocket.sendClose(ChromeWebSocket.NORMAL_CLOSURE, "complete");
                        }
                    } catch (JsonProcessingException exception) {
                        fail(listener, terminal, "Could not parse a Responses WebSocket event", exception);
                        webSocket.abort();
                    } catch (RuntimeException exception) {
                        fail(listener, terminal, "Responses WebSocket listener failed", exception);
                        webSocket.abort();
                    }
                }
                webSocket.request(1);
            }

            @Override
            public void onClose(ChromeWebSocket webSocket, int statusCode, String reason) {
                if (!terminal.isDone()) {
                    fail(listener, terminal,
                            "Responses WebSocket closed before a terminal event: " + statusCode + " " + reason,
                            null);
                }
            }

            @Override
            public void onError(ChromeWebSocket webSocket, Throwable error) {
                fail(listener, terminal, "Responses WebSocket failed", error);
            }
        };

        ChromeWebSocket webSocket;
        try {
            ChromeWebSocketRequest request = ChromeWebSocketRequest.newBuilder(webSocketUri(responsesEndpoint))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("ChatGPT-Account-Id", accountId)
                    .header("OpenAI-Beta", "responses_websockets=2026-02-06")
                    .header("originator", originator.headerValue())
                    .header("User-Agent", userAgent)
                    .build();
            webSocket = httpClient.openWebSocket(request, webSocketListener)
                    .join();
            webSocket.sendText(objectMapper.writeValueAsString(wireRequest), true).join();
            terminal.join();
        } catch (JsonProcessingException exception) {
            ChatGptAccountException wrapped = new ChatGptAccountException(
                    "Could not serialize the Responses WebSocket request", -1, exception);
            if (!terminal.isDone()) {
                listener.onError(wrapped);
            }
            throw wrapped;
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof ChatGptAccountException accountException) {
                throw accountException;
            }
            ChatGptAccountException wrapped = new ChatGptAccountException(
                    opened.get() ? "Responses WebSocket stream failed" : "Could not open Responses WebSocket",
                    -1, cause);
            if (!terminal.isDone()) {
                listener.onError(wrapped);
            }
            throw wrapped;
        }
    }

    static URI webSocketUri(URI endpoint) {
        String scheme = switch (endpoint.getScheme()) {
            case "https" -> "wss";
            case "http" -> "ws";
            case "wss", "ws" -> endpoint.getScheme();
            default -> throw new ChatGptAccountException(
                    "Responses endpoint does not use HTTP or WebSocket: " + endpoint, -1, null);
        };
        return URI.create(scheme + "://" + endpoint.getRawAuthority()
                + endpoint.getRawPath()
                + (endpoint.getRawQuery() == null ? "" : "?" + endpoint.getRawQuery()));
    }

    private static boolean isTerminal(String type) {
        return "response.completed".equals(type)
                || "response.incomplete".equals(type)
                || "response.failed".equals(type)
                || "error".equals(type);
    }

    private static void fail(
            OpenAiResponsesListener listener,
            CompletableFuture<Void> terminal,
            String message,
            Throwable cause) {
        if (terminal.isDone()) {
            return;
        }
        ChatGptAccountException exception = new ChatGptAccountException(message, -1, cause);
        listener.onError(exception);
        terminal.completeExceptionally(exception);
    }
}
