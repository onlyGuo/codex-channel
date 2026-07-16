# codex-channel Core

[English](README.md) | [简体中文](README.zh-CN.md)

[`core`](../README.zh-CN.md) 提供 Codex/ChatGPT OAuth、本地凭据文件、账号数据、额度/模型查询，以及 Responses 和 Chat Completions 请求的 Java API。

## 安装

```xml
<dependency>
    <groupId>ink.icoding.codex</groupId>
    <artifactId>core</artifactId>
    <version>VERSION</version>
</dependency>
```

将 `VERSION` 替换为已发行版本。Chrome TLS HTTP 传输层会被自动传递引入。

## 快速开始

使用 [CLI](../cli/README.zh-CN.md) 创建的凭据文件，或保存自行完成 OAuth 流程所获得的 `OpenAiTokenResponse` 和 `OpenAiAccount`。

```java
import ink.icoding.codex.core.oauth.ChatGptAccountClient;
import java.io.File;

try (ChatGptAccountClient client = new ChatGptAccountClient(
        new File("token.json"),
        new File("account.json"))) {
    System.out.println(client.account());
    System.out.println(client.fetchQuotaInfo());
    System.out.println(client.fetchModels());
}
```

客户端会按需刷新凭据，并将刷新后的值保存到所提供的文件中。

## OAuth

```java
import ink.icoding.codex.core.oauth.AuthorizationSession;
import ink.icoding.codex.core.oauth.OpenAiAuthorizationUrlGenerator;
import ink.icoding.codex.core.oauth.OpenAiTokenClient;
import ink.icoding.codex.core.oauth.OpenAiTokenResponse;

AuthorizationSession session = new OpenAiAuthorizationUrlGenerator().create();
System.out.println(session.authorizationUrl());

// 从重定向处理程序获得授权码。
String authorizationCode = "...";
try (OpenAiTokenClient tokens = new OpenAiTokenClient()) {
    OpenAiTokenResponse token = tokens.exchangeAuthorizationCode(authorizationCode, session);
}
```

终端登录（包括本地回调不可访问的 SSH 环境）建议使用 `codex-channel auth login`：它支持粘贴完整的重定向 URL。

## 请求

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

ObjectMapper mapper = new ObjectMapper();
JsonNode request = mapper.readTree("""
        {"model":"gpt-5.2-codex","input":"Summarize this change."}
        """);

JsonNode response = client.createResponse(request);
System.out.println(response);
```

可用的账号操作包括：

- `fetchQuotaInfo()`、`fetchRateLimitResetCredits()`
- `fetchModels()`、`fetchUsageJson()`、`fetchProfile()`
- `createResponse()`、`createChatCompletion()`
- `streamResponses()`、`streamChatCompletions()`
- `refreshToken()`、`setTrainingAllowed()`、`revokeToken()`

## 流式响应

```java
client.streamResponses(request, event -> {
    System.out.println(event.event());
    System.out.println(event.data());
});
```

通过监听器 API 处理流式 Responses 或 Chat Completions 输出。上游操作支持时也可使用 WebSocket 流；TLS 兼容策略见 [HTTP 模块](../http/README.zh-CN.md#websocket)。

## 相关模块

- [`http`](../http/README.zh-CN.md) 直接提供底层传输能力。
- [`cli`](../cli/README.zh-CN.md) 提供本地存储、调度、熔断和 OpenAI 兼容服务。
