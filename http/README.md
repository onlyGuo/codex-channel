# codex-channel HTTP

[English](README.md) | [简体中文](README.zh-CN.md)

[`http`](../README.md) is a Java 17 HTTP client library backed by `curl-impersonate`. Use it for HTTP requests and Server-Sent Events that need Chrome TLS behavior, or for the accompanying WebSocket compatibility API.

## Install

```xml
<dependency>
    <groupId>ink.icoding.codex</groupId>
    <artifactId>http</artifactId>
    <version>VERSION</version>
</dependency>
```

Replace `VERSION` with a released version. For a local checkout, run `./mvnw install` and use the version declared in the root `pom.xml`.

## Quick start

```java
import ink.icoding.codex.http.ChromeBodyHandlers;
import ink.icoding.codex.http.ChromeHttpClient;
import ink.icoding.codex.http.ChromeHttpRequest;
import ink.icoding.codex.http.ChromeHttpResponse;

try (ChromeHttpClient client = ChromeHttpClient.newBuilder().build()) {
    ChromeHttpRequest request = ChromeHttpRequest
            .newBuilder("https://example.com/api/items")
            .header("Accept", "application/json")
            .GET()
            .build();

    ChromeHttpResponse<String> response = client.send(request, ChromeBodyHandlers.ofString());
    System.out.println(response.statusCode());
    System.out.println(response.body());
}
```

`ChromeHttpRequest` supports arbitrary methods, headers, string/byte-array/file bodies, request timeouts, redirects, proxies, and cookies. Standard body handlers include `ofString()`, `ofByteArray()`, `ofJson()`, `ofFile()` and `discarding()`.

## SSE

```java
import ink.icoding.codex.http.SseCall;
import ink.icoding.codex.http.SseListener;

ChromeHttpRequest request = ChromeHttpRequest.newBuilder("https://example.com/events")
        .header("Accept", "text/event-stream")
        .GET()
        .build();

SseCall call = client.openSse(request, new SseListener() {
    @Override
    public void onEvent(ink.icoding.codex.http.SseEvent event) {
        System.out.println(event.event() + ": " + event.data());
    }
});

call.completion().join();
```

Call `close()` to stop an active stream.

## WebSocket

```java
import ink.icoding.codex.http.ChromeWebSocketRequest;
import java.net.URI;

ChromeWebSocketRequest request = ChromeWebSocketRequest
        .newBuilder(URI.create("wss://example.com/socket"))
        .header("Authorization", "Bearer token")
        .subprotocols("json")
        .build();

client.openWebSocket(request, listener);
```

The WebSocket API is available through the JDK compatibility transport. It supports messages, ping/pong, close, headers, subprotocols, timeouts, and proxies. Its TLS handshake does not impersonate Chrome; use `WebSocketTlsPolicy.REQUIRE_CHROME_IMPERSONATION` when that fallback must be rejected.

## Runtime setup

On macOS and Linux, the client installs the pinned `curl-impersonate` binary automatically when it is first needed. To use an existing installation or control provisioning, use one of these settings:

| Setting | Purpose |
| --- | --- |
| `-Dcodex.http.curl-impersonate=/path/to/binary` | Select an executable explicitly |
| `CURL_IMPERSONATE_BIN` | Select an executable through the environment |
| `CURL_IMPERSONATE_HOME` | Search an installation directory |
| `-Dcodex.http.auto-download=false` | Disable automatic download |
| `CURL_IMPERSONATE_CACHE_DIR` | Change the binary cache directory |

Windows users must provide a compatible `curl-impersonate` CLI through the JVM property or `CURL_IMPERSONATE_BIN`.

## Related modules

- Use [`core`](../core/README.md) for OAuth and Codex account operations.
- Use [`cli`](../cli/README.md) for local account management and the HTTP service.
