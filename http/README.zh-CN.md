# codex-channel HTTP

[English](README.md) | [简体中文](README.zh-CN.md)

[`http`](../README.zh-CN.md) 是基于 `curl-impersonate` 的 Java 17 HTTP 客户端库。它适用于需要 Chrome TLS 行为的 HTTP 请求与 Server-Sent Events，也提供 WebSocket 兼容 API。

## 安装

```xml
<dependency>
    <groupId>ink.icoding.codex</groupId>
    <artifactId>http</artifactId>
    <version>VERSION</version>
</dependency>
```

将 `VERSION` 替换为已发行版本。本地开发可运行 `./mvnw install`，并使用当前项目版本。

## 快速开始

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

`ChromeHttpRequest` 支持任意方法、请求头、字符串/字节数组/文件请求体、超时、重定向、代理和 Cookie。常用响应处理器包括 `ofString()`、`ofByteArray()`、`ofJson()`、`ofFile()` 与 `discarding()`。

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

调用 `close()` 可停止活动流。

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

WebSocket API 经由 JDK 兼容传输实现，支持消息、ping/pong、关闭、请求头、子协议、超时和代理。其 TLS 握手不模拟 Chrome；需要拒绝此回退时，请使用 `WebSocketTlsPolicy.REQUIRE_CHROME_IMPERSONATION`。

## 运行时配置

在 macOS 和 Linux 上，客户端在首次需要时自动安装固定版本的 `curl-impersonate` 二进制。若使用已有安装或需要控制准备方式，可使用下列设置：

| 设置 | 用途 |
| --- | --- |
| `-Dcodex.http.curl-impersonate=/path/to/binary` | 显式指定可执行文件 |
| `CURL_IMPERSONATE_BIN` | 通过环境变量指定可执行文件 |
| `CURL_IMPERSONATE_HOME` | 搜索安装目录 |
| `-Dcodex.http.auto-download=false` | 禁用自动下载 |
| `CURL_IMPERSONATE_CACHE_DIR` | 修改二进制缓存目录 |

Windows 用户必须通过 JVM 属性或 `CURL_IMPERSONATE_BIN` 提供兼容的 `curl-impersonate` CLI。

## 相关模块

- 使用 [`core`](../core/README.zh-CN.md) 完成 OAuth 与 Codex 账号操作。
- 使用 [`cli`](../cli/README.zh-CN.md) 管理本地账号并运行 HTTP 服务。
