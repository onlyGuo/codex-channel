# codex-channel Core

[English](README.md) | [简体中文](README.zh-CN.md)

[`core`](../README.md) provides Java APIs for the Codex/ChatGPT OAuth flow, local credential files, account data, quota/model queries, and Responses or Chat Completions requests.

## Install

```xml
<dependency>
    <groupId>ink.icoding.codex</groupId>
    <artifactId>core</artifactId>
    <version>VERSION</version>
</dependency>
```

Replace `VERSION` with a released version. The Chrome TLS HTTP transport is included transitively.

## Quick start

Use credential files created by the [CLI](../cli/README.md), or persist an `OpenAiTokenResponse` and `OpenAiAccount` from your own OAuth flow.

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

The client refreshes credentials as needed and persists refreshed values to the supplied files.

## OAuth

```java
import ink.icoding.codex.core.oauth.AuthorizationSession;
import ink.icoding.codex.core.oauth.OpenAiAuthorizationUrlGenerator;
import ink.icoding.codex.core.oauth.OpenAiTokenClient;
import ink.icoding.codex.core.oauth.OpenAiTokenResponse;

AuthorizationSession session = new OpenAiAuthorizationUrlGenerator().create();
System.out.println(session.authorizationUrl());

// Receive the authorization code from your redirect handler.
String authorizationCode = "...";
try (OpenAiTokenClient tokens = new OpenAiTokenClient()) {
    OpenAiTokenResponse token = tokens.exchangeAuthorizationCode(authorizationCode, session);
}
```

For terminal login, including SSH environments where a local callback cannot be reached, prefer `codex-channel auth login`: it accepts the complete pasted redirect URL.

## Requests

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

Available account operations include:

- `fetchQuotaInfo()` and `fetchRateLimitResetCredits()`
- `fetchModels()`, `fetchUsageJson()`, and `fetchProfile()`
- `createResponse()` and `createChatCompletion()`
- `streamResponses()` and `streamChatCompletions()`
- `refreshToken()`, `setTrainingAllowed()` and `revokeToken()`

## Streaming

```java
client.streamResponses(request, event -> {
    System.out.println(event.event());
    System.out.println(event.data());
});
```

Use the listener APIs to process streaming Responses or Chat Completions output. WebSocket streaming is also exposed where the upstream operation supports it; see the [HTTP module](../http/README.md#websocket) for the TLS compatibility policy.

## Related modules

- [`http`](../http/README.md) exposes the underlying transport directly.
- [`cli`](../cli/README.md) adds local storage, scheduling, circuit breaking, and an OpenAI-compatible service.
