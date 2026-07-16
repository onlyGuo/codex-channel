# codex-channel

`codex-channel` 是一个 Java 17 Maven 类库，用于完成 Codex/ChatGPT OAuth 登录、账号信息解析、Token 生命周期管理、配额查询，以及通过 ChatGPT Codex 后端调用 Responses 和 Chat Completions 风格接口。

项目当前包含四个模块：

- `core`：可被其他 Maven 项目引用的类库。
- `http`：基于 `curl-impersonate` 的 Chrome TLS HTTP/SSE 客户端，并提供 WebSocket 兼容传输。
- `cli`：多账号文件存储、账号调度、熔断器和 OpenAI 兼容 HTTP 服务。
- `example`：使用本地 JSON 凭证进行真实调用的示例程序。

> 本项目调用了 Codex 使用的 ChatGPT 后端接口。这些接口不等同于公开的 OpenAI Platform API，可能随 Codex 客户端版本发生变化。

## 环境要求

- JDK 17 或更高版本
- 首次运行可访问 GitHub Releases（macOS/Linux 自动安装 `curl-impersonate`）
- Maven Wrapper，仓库已包含 `mvnw`
- 可正常访问 `auth.openai.com` 和 `chatgpt.com`
- 一个允许使用 Codex 的 ChatGPT/OpenAI 账号

构建并运行全部测试：

```bash
./mvnw clean test
```

安装到本地 Maven 仓库：

```bash
./mvnw clean install
```

其他 Maven 项目可以在本地安装后引用：

```xml
<dependency>
    <groupId>ink.icoding.codex</groupId>
    <artifactId>core</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

## Chrome TLS HTTP 客户端

`http` 模块把平台原生 `curl-impersonate` 包装成 Java 17 客户端。HTTP 与 SSE 请求均由同一个 curl transport 发出，使用 `--impersonate` 提供的 Chrome TLS、HTTP/2 和请求头指纹。

引入模块：

```xml
<dependency>
    <groupId>ink.icoding.codex</groupId>
    <artifactId>http</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### 零配置安装与平台解析

macOS 与 Linux 的 x86_64、arm64 无需手动安装。Client 找不到本地命令时，会自动下载官方 [`lexiforest/curl-impersonate` `v1.5.6`](https://github.com/lexiforest/curl-impersonate/releases/tag/v1.5.6) CLI 资产，校验固定 SHA-256，安全解压并缓存。安装过程使用跨进程文件锁，后续启动不会重复下载。

默认缓存目录：

```text
~/.cache/codex-channel/curl-impersonate/v1.5.6/
```

Client 按以下顺序解析可执行文件：

1. JVM 参数 `-Dcodex.http.curl-impersonate=/absolute/path/to/curl-impersonate-chrome`
2. 环境变量 `CURL_IMPERSONATE_BIN`
3. `CURL_IMPERSONATE_HOME` 及其 `bin` 子目录
4. `PATH` 中的 `curl-impersonate-chrome`、`curl_chrome` 和常见 Chrome 版本命令
5. 已校验的托管缓存
6. 下载并安装固定版本的官方资产

也可以直接传入路径：

```java
try (ChromeHttpClient client = ChromeHttpClient.newBuilder()
        .executable(Path.of("/opt/curl-impersonate/curl-impersonate-chrome"))
        .profile(ChromeProfile.CHROME_136)
        .build()) {
    // requests
}
```

可以用 `-Dcodex.http.cache-dir=/path` 或 `CURL_IMPERSONATE_CACHE_DIR` 修改缓存目录；用 `-Dcodex.http.auto-download=false` 或 `CURL_IMPERSONATE_AUTO_DOWNLOAD=false` 禁止联网安装。Linux 默认在 Alpine 选择 musl、其他发行版选择 GNU libc，也可以用 `-Dcodex.http.libc=musl` 明确指定。

官方 `v1.5.6` 暂未发布 Windows CLI 资产，只发布了 Windows `libcurl`。因此 Windows 仍需通过 `codex.http.curl-impersonate` 或 `CURL_IMPERSONATE_BIN` 指向兼容的 CLI；模块不会回退到普通 curl 或伪装成已启用 Chrome TLS。

不同发行版选择 profile 的方式不同。默认 `AUTO` 会识别 `curl_chrome*` wrapper；也可以用 `.profileSelection(CurlProfileSelection.ARGUMENT)`、`.ENVIRONMENT` 或 `.WRAPPER` 明确指定 `--impersonate`、`CURL_IMPERSONATE` 环境变量或 wrapper 模式。

### HTTP 请求

```java
try (ChromeHttpClient client = ChromeHttpClient.newBuilder()
        .profile(ChromeProfile.CHROME)
        .connectTimeout(Duration.ofSeconds(10))
        .requestTimeout(Duration.ofSeconds(60))
        .maxResponseBytes(32 * 1024 * 1024)
        .build()) {
    ChromeHttpRequest request = ChromeHttpRequest.newBuilder("https://example.com/api/items")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST("{\"name\":\"demo\"}")
            .build();

    ChromeHttpResponse<String> response = client.send(request);
    System.out.println(response.statusCode());
    System.out.println(response.body());
}
```

`ChromeHttpRequest` 支持任意 HTTP method、重复请求头、无请求体，以及字符串、`byte[]`、`Path` 请求体。标准响应 handler 包括：

- `ChromeBodyHandlers.ofString()`
- `ChromeBodyHandlers.ofByteArray()`
- `ChromeBodyHandlers.ofJson(MyType.class)`
- `ChromeBodyHandlers.ofFile(destination)`
- `ChromeBodyHandlers.discarding()`

`sendAsync` 提供异步调用。Client 还支持自动重定向、持久 Cookie jar、自定义 CA、TLS 校验开关、默认请求头，以及 HTTP/HTTPS/SOCKS4/SOCKS5 代理：

```java
ChromeProxy proxy = new ChromeProxy(
        URI.create("socks5h://127.0.0.1:1080"),
        "username",
        "password");
```

### SSE

```java
ChromeHttpRequest request = ChromeHttpRequest.newBuilder("https://example.com/events")
        .header("Accept", "text/event-stream")
        .GET()
        .build();

SseCall call = client.openSse(request, new SseListener() {
    @Override
    public void onEvent(SseEvent event) {
        System.out.println(event.event() + ": " + event.data());
    }
});

call.completion().join();
```

`SseCall.close()` 会立即终止对应 curl 进程。解析器支持 BOM、注释、多行 `data`、`event`、`id`、`retry` 和 EOF 最后事件派发。

### WebSocket

`curl-impersonate` 命令行接口不能承载 WebSocket frame，因此模块的 WebSocket API 使用 JDK WebSocket 作为明确的兼容传输。它支持文本/二进制消息、ping/pong、close、子协议、自定义请求头、连接超时和 HTTP 代理，但 **WebSocket 握手不具备 Chrome TLS 指纹**。

```java
ChromeWebSocketRequest request = ChromeWebSocketRequest
        .newBuilder(URI.create("wss://example.com/socket"))
        .header("Authorization", "Bearer token")
        .subprotocols("json")
        .build();

CompletableFuture<ChromeWebSocket> socket = client.openWebSocket(request, listener);
```

如果业务不允许 WebSocket TLS 回退，启用严格策略：

```java
ChromeHttpClient client = ChromeHttpClient.newBuilder()
        .webSocketTlsPolicy(WebSocketTlsPolicy.REQUIRE_CHROME_IMPERSONATION)
        .build();
```

严格策略会拒绝打开 WebSocket。可通过 `client.capabilities()` 在运行时检查各协议是否使用 Chrome TLS，避免把 JDK TLS 误判为 impersonation。

## CLI 与 HTTP 服务

构建可执行 JAR：

```bash
./mvnw -pl cli -am package
java -jar cli/target/cli-0.0.1-SNAPSHOT.jar --help
```

下文用 `codex-channel` 代表 `java -jar cli/target/cli-0.0.1-SNAPSHOT.jar`。默认数据目录是 `~/.codex-channel`，也可以在所有命令前增加 `--home /path/to/state`。增加 `--json` 可获得机器可读输出。

### 登录与多账号

```bash
codex-channel auth login --alias work
codex-channel auth login --alias personal
codex-channel account list
codex-channel account use work
```

`auth login` 不会自动打开浏览器，只会打印授权地址，并同时等待 `localhost:1455` 回调和终端输入。通过 SSH 登录、浏览器不在运行 CLI 的机器上，或者端口无法回连时，直接把浏览器最终跳转到的完整地址粘贴到终端即可。`--manual` 可完全禁用本地监听。

也可以导入已有凭证：

```bash
codex-channel auth import token.json account.json --alias imported
```

账号凭证按账号隔离存储：

```text
~/.codex-channel/
├── config.json
├── accounts/<alias>/
│   ├── token.json
│   ├── account.json
│   ├── metadata.json
│   └── cookies.txt
├── service.json
└── logs/server.log
```

目录权限设置为 `0700`，Token、账号信息和服务管理令牌设置为 `0600`（支持 POSIX 权限的平台）。

### 账号管理

```bash
codex-channel account show work
codex-channel account enable work
codex-channel account disable personal
codex-channel account weight work 3
codex-channel account schedule --failure-threshold 3 --open-seconds 60

codex-channel account refresh --account work
codex-channel account quota --account work
codex-channel account models --account work
codex-channel account profile --account work
codex-channel account usage --account work
codex-channel account training disable --account work
codex-channel account reset-credits --account work
codex-channel account remove work
```

服务使用加权轮询：权重为 `3` 的账号获得的首选调度次数约为权重 `1` 账号的三倍。请求遇到连接错误、`401`、`403`、`408`、`429` 或 `5xx` 时会切换到其他可用账号；连续失败达到阈值后熔断，等待窗口结束只允许一个半开探测请求。成功后恢复闭合状态。`server status` 会显示每个账号的熔断状态、连续失败数和恢复时间。

### 启停 HTTP 服务

```bash
codex-channel server start
codex-channel server status
codex-channel server logs --lines 100
codex-channel server stop
```

默认监听 `127.0.0.1:8787`。对外监听时必须设置 API Key：

```bash
codex-channel server start --host 0.0.0.0 --port 8787 --api-key change-me
```

`--foreground` 可让服务留在当前终端。后台服务通过本地 `service.json` 中的随机管理令牌执行状态查询和停止，API Key 通过子进程环境传递，不写入命令行参数。服务提供：

- `GET /health`
- `GET /v1/models`
- `POST /v1/responses`，支持 `stream: true` SSE
- `POST /v1/chat/completions`，支持 `stream: true` SSE

设置 API Key 后，请求需携带 `Authorization: Bearer <api-key>`。响应头 `X-Codex-Account` 表示实际承载请求的账号。

```bash
curl http://127.0.0.1:8787/v1/responses \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer change-me' \
  -d '{"model":"gpt-5.2-codex","input":"hello"}'
```

检查或预装 Chrome TLS transport：

```bash
codex-channel transport doctor
codex-channel transport install
```

### 原生二进制

安装带有 `native-image` 的 GraalVM JDK 后，可以为当前操作系统和 CPU 构建原生可执行文件：

```bash
./mvnw -pl cli -am -Pnative package -DskipTests
./cli/target/codex-channel --help
```

Native Image 不能跨平台编译，同一条命令需要分别在 macOS/Linux 的 x86_64、arm64 构建。原生 CLI 会在 `server start` 时重新执行自身，不依赖目标机器安装 Java。`curl-impersonate` 仍按平台作为外部原生 transport 首次自动安装，也可以通过 `CURL_IMPERSONATE_BIN` 指定随发行包提供的二进制。

推送语义化版本 tag 后，GitHub Actions 会自动构建并发布 Linux x86_64/arm64、macOS x86_64/arm64 和 Windows x86_64 原生包，同时上传对应的 SHA-256 文件：

```bash
git tag v1.0.0
git push origin v1.0.0
```

工作流会把 tag 中的版本写入 `codex-channel --version`。macOS/Linux 会在首次使用外部 HTTP transport 时自动安装对应的 `curl-impersonate`；Windows 目前仍需通过 `CURL_IMPERSONATE_BIN` 指向兼容的 Windows CLI。

## OAuth 授权流程

### 1. 生成授权链接

```java
OpenAiAuthorizationUrlGenerator generator = new OpenAiAuthorizationUrlGenerator();
AuthorizationSession session = generator.create();

System.out.println(session.authorizationUrl());
System.out.println(session.sessionId());
System.out.println(session.state());
System.out.println(session.codeVerifier());
```

默认回调地址为：

```text
http://localhost:1455/auth/callback
```

也可以指定回调地址：

```java
AuthorizationSession session = generator.create("https://client.example/auth/callback");
```

每个授权会话会生成：

- 32 字节随机 `state`，编码为 64 位十六进制字符串。
- 64 字节随机 `code_verifier`，编码为 128 位十六进制字符串。
- `BASE64URL_NO_PADDING(SHA256(code_verifier))` 格式的 PKCE challenge。
- 仅供本项目关联回调和 verifier 使用的 `session_id`。

`session_id` 不会发送给 OpenAI。收到授权回调后，应先校验回调中的 `state`，再使用对应会话保存的 `code_verifier` 换取 Token。

### 2. 使用 authorization code 换取 Token

```java
try (OpenAiTokenClient tokenClient = new OpenAiTokenClient()) {
    OpenAiTokenResponse token = tokenClient.exchangeAuthorizationCode(
            authorizationCode,
            session);
}
```

也可以直接传入回调地址和 PKCE verifier：

```java
OpenAiTokenResponse token = tokenClient.exchangeAuthorizationCode(
        authorizationCode,
        redirectUri,
        codeVerifier);
```

Token 响应包括：

- `access_token`
- `refresh_token`
- `id_token`
- `expires_in`
- `earliest_refresh_at`
- `scope`
- `token_type`

### 3. 本地解析 ID Token

```java
OpenAiIdTokenParser parser = new OpenAiIdTokenParser();
OpenAiAccount account = parser.parse(token);
```

解析器会：

1. 检查 JWT 是否正好包含 `header.payload.signature` 三段。
2. Base64URL 解码 payload。
3. 反序列化 JSON。
4. 检查 `exp` 是否过期。
5. 映射邮箱、ChatGPT 账号、用户、套餐和组织字段。
6. 优先选择 `is_default=true` 的组织，否则使用第一个组织。

当前解析器只做 payload 解码和过期检查，不校验 JWT 签名。不要把未受信任来源提供的 ID Token 当作已验证身份凭证。

## example 数据目录

真实测试文件统一放在：

```text
example/data/
├── opentoken.json
├── openaccount.json
├── request.json
└── chat-request.json
```

整个 `example/data/` 已加入 `.gitignore`，不会提交到 Git。

最小 Token 文件结构：

```json
{
  "access_token": "...",
  "refresh_token": "...",
  "id_token": "...",
  "expires_in": 3600,
  "earliest_refresh_at": 0,
  "scope": "openid profile email offline_access",
  "token_type": "Bearer"
}
```

最小账号文件结构：

```json
{
  "email": "user@example.com",
  "chatgptAccountId": "account-...",
  "chatgptUserId": "user-...",
  "planType": "plus",
  "organizationId": "org-...",
  "subscriptionExpiresAt": null
}
```

`AuthorizationExample` 可以从仓库根目录或 `example` 模块目录运行，都会自动定位 `data` 目录。

## 创建账号 Client

使用对象创建：

```java
try (ChatGptAccountClient client = new ChatGptAccountClient(token, account)) {
    System.out.println(client.account());
}
```

使用 JSON 文件创建：

```java
try (ChatGptAccountClient client = new ChatGptAccountClient(
        new File("example/data/opentoken.json"),
        new File("example/data/openaccount.json"))) {
    System.out.println(client.account());
}
```

文件构造器具有以下行为：

- 构造后的第一次后端请求会设置 `training_allowed=false`。
- 实例化时自动查询账号和订阅信息。
- 自动刷新 Token 后原子更新 `opentoken.json`。
- 账号补充完成后原子更新 `openaccount.json`。
- 在 access token 到期前 10 分钟主动刷新。
- 刷新失败后等待 60 秒重试。
- `close()` 会停止后台刷新线程。

建议始终使用 try-with-resources 管理 Client。

Client 默认在账号 enrich 和 Token 刷新调度之前调用：

```text
PATCH /backend-api/settings/account_user_setting?feature=training_allowed&value=false
```

如果需要手动修改该设置：

```java
client.setTrainingAllowed(false);
client.setTrainingAllowed(true);
```

构造阶段设置失败会抛出 `ChatGptAccountException`，Client 不会在未确认设置成功时继续初始化。

## HTTP 代理

无认证 HTTP 代理：

```java
HttpProxyConfig proxy = new HttpProxyConfig("127.0.0.1", 8080);

try (ChatGptAccountClient client = new ChatGptAccountClient(token, account, proxy)) {
    // ...
}
```

带用户名和密码的 HTTP 代理：

```java
HttpProxyConfig proxy = new HttpProxyConfig(
        "127.0.0.1",
        8080,
        "username",
        "password");
```

文件构造器也支持第三个代理参数。`core` 的全部 OAuth、账号、Codex JSON、SSE 和 WebSocket 请求统一经过 `http` 模块。`HttpProxyConfig` 保持 HTTP 代理兼容；需要 SOCKS5 或自定义 Chrome profile 时，直接构造 transport：

```java
ChromeHttpClient transport = ChromeHttpClient.newBuilder()
        .profile(ChromeProfile.CHROME_136)
        .proxy(new ChromeProxy(URI.create("socks5h://127.0.0.1:1080")))
        .build();

try (ChatGptAccountClient client = new ChatGptAccountClient(token, account, transport)) {
    // Client owns and closes transport.
}
```

## Token 和账号生命周期

手动刷新 Token：

```java
OpenAiTokenResponse refreshed = client.refreshToken();
```

读取当前 Token 和账号：

```java
OpenAiTokenResponse currentToken = client.tokenResponse();
OpenAiAccount currentAccount = client.account();
```

重新获取订阅信息：

```java
OpenAiAccount refreshedAccount = client.enrichAccount();
```

查询当前 access token 可访问的账号身份：

```java
JsonNode accounts = client.fetchAuthAccounts();
```

撤销 refresh token：

```java
client.revokeToken();
```

撤销后，该 Client 的自动刷新和手动刷新都会被禁用。该操作适合放在程序退出或账号解绑流程的最后一步。

也可以显式撤销指定 token：

```java
client.revokeToken(tokenValue, "access_token");
```

## 账号、订阅和配额

```java
OpenAiAccount account = client.account();
OpenAiQuotaInfo quota = client.fetchQuotaInfo();
OpenAiRateLimitResetCredits credits = client.fetchRateLimitResetCredits();
```

`OpenAiQuotaInfo` 分别提供五小时和周窗口。某个窗口未由服务端返回时，对应字段为 `null`。

获取完整 usage JSON：

```java
JsonNode usage = client.fetchUsageJson();
```

重置额度接口：

```java
OpenAiRateLimitResetCredits credits = client.fetchRateLimitResetCredits();

if (!credits.credits().isEmpty()) {
    String creditId = credits.credits().get(0).id();
    JsonNode result = client.consumeRateLimitResetCredit(creditId);
}
```

重试同一次额度消费时，应复用 idempotency key：

```java
JsonNode result = client.consumeRateLimitResetCredit(creditId, idempotencyKey);
```

其他 Codex 账号接口：

```java
JsonNode models = client.fetchModels("0.144.2");
JsonNode profile = client.fetchProfile();
JsonNode messages = client.fetchWorkspaceMessages();
JsonNode config = client.fetchConfigBundle();
```

向账号所有者发送增加额度提醒：

```java
client.sendAddCreditsNudgeEmail();
```

## Responses API 风格调用

### 请求格式

`request.json` 示例：

```json
{
  "model": "gpt-5",
  "input": [
    {
      "role": "user",
      "content": [
        {
          "type": "input_text",
          "text": "你好，你是谁？"
        }
      ]
    }
  ]
}
```

Client 会在内部请求副本中强制写入：

```json
{
  "store": false,
  "stream": true
}
```

或在非流式调用中写入 `stream=false`。调用方传入的 `JsonNode` 不会被修改。

### 非流式 Responses

```java
JsonNode response = client.createResponse(requestBody);
```

返回值保持 Responses response JSON 结构。

### HTTP SSE Responses

```java
client.streamResponses(requestBody, new OpenAiResponsesListener() {
    @Override
    public void onEvent(OpenAiResponsesEvent event) {
        System.out.println(event.event());
        System.out.println(event.data());
    }

    @Override
    public void onError(Throwable error) {
        error.printStackTrace();
    }
});
```

`OpenAiResponsesEvent.data()` 是服务端发送的原始 Responses 事件 JSON。

### WebSocket Responses

```java
client.streamResponsesWebSocket(requestBody, event -> {
    System.out.println(event.event() + ": " + event.data());
});
```

WebSocket 使用 Codex 的 `response.create` 帧协议。连接会持续读取，直到收到 `response.completed`、`response.incomplete`、`response.failed` 或 `error`。

### 服务端上下文压缩

```java
JsonNode compacted = client.compactResponses(compactRequestBody);
```

该接口对应 Codex 的 Responses 服务端压缩能力，适合长会话历史。

## Chat Completions API 风格调用

`chat-request.json` 示例：

```json
{
  "model": "gpt-5",
  "messages": [
    {
      "role": "user",
      "content": "你好，你是谁？"
    }
  ],
  "stream": true,
  "stream_options": {
    "include_usage": true
  }
}
```

### 非流式 Chat Completions

```java
JsonNode completion = client.createChatCompletion(chatRequest);
```

返回结构为标准 `chat.completion` JSON，包括：

- `id`
- `object`
- `created`
- `model`
- `choices`
- `usage`

### HTTP 流式 Chat Completions

```java
client.streamChatCompletions(chatRequest, new OpenAiChatCompletionsListener() {
    @Override
    public void onChunk(JsonNode chunk) {
        System.out.println(chunk);
    }

    @Override
    public void onComplete() {
        System.out.println("[DONE]");
    }
});
```

每个 chunk 都采用 `chat.completion.chunk` JSON 结构。

### WebSocket Chat Completions

```java
client.streamChatCompletionsWebSocket(chatRequest, System.out::println);
```

底层仍使用 Responses WebSocket，Client 会自动进行请求和输出转换。

### Chat Completions 转换范围

当前支持：

- `system`、`developer`、`user`、`assistant`、`tool` 和旧版 `function` 消息。
- 字符串内容、文本 content part 和图片 URL。
- `tools`、`tool_choice`。
- 旧版 `functions`、`function_call`。
- 函数调用参数增量和工具结果。
- `max_tokens`、`max_completion_tokens` 到 `max_output_tokens`。
- `response_format` 的 `text`、`json_object` 和 `json_schema`。
- `stream_options.include_usage`。
- 文本、refusal、函数调用、finish reason 和 usage 输出转换。

以下参数没有可保证无损的 Responses 等价语义，因此会明确抛出异常：

- `n` 不等于 `1`
- `stop`
- `logprobs`
- `top_logprobs`
- `logit_bias`
- `seed`

这样可以避免请求成功但实际行为与 Chat Completions 参数不一致。

## 异常处理

账号、配额和推理请求失败时抛出：

```text
ChatGptAccountException
```

OAuth code exchange、账号列表和 token revoke 失败时抛出：

```text
OpenAiTokenExchangeException
```

两种异常都通过 `statusCode()` 暴露 HTTP 状态码。没有收到 HTTP 响应时状态码为 `-1`。

流式监听器还可以通过 `onError(Throwable)` 接收同一个异常。

## Cloudflare 403

如果响应是包含以下文本的 HTML，而不是 JSON：

```text
Enable JavaScript and cookies to continue
```

说明请求在到达业务接口前被 Cloudflare Managed Challenge 拦截。`curl-impersonate` 可以模拟 Chrome TLS/HTTP 指纹，但不会执行浏览器 JavaScript challenge，也不会自动共享 ChatGPT 或 Codex 进程中的现有 Cookie。仅更换 Token 或重复请求通常不能解决此问题。

应优先检查：

- 当前出口 IP、VPN 或代理信誉。
- 请求频率。
- 是否错误增加了不属于该端点的请求头。
- ChatGPT 网页或 Codex 客户端本身是否也能在同一网络访问。

## 安全注意事项

- `access_token`、`refresh_token` 和 `id_token` 都属于敏感凭证。
- 不要把 `example/data/`、日志、异常响应或 Token 输出提交到 Git。
- 不要在共享机器上保存长期 refresh token。
- `revokeToken()` 应只在确认不再使用该账号时调用。
- Client 调用的是 ChatGPT Codex 后端，而不是稳定承诺的公开 Platform API。
- ChatGPT 后端和 Cloudflare 策略可能随时间变化，生产使用前应固定版本并进行集成测试。

## 项目结构

```text
codex-channel/
├── core/
│   └── src/main/java/ink/icoding/codex/core/oauth/
├── example/
│   ├── data/                 # 本地凭证和请求，已忽略
│   └── src/main/java/ink/icoding/codex/example/
├── pom.xml
└── README.md
```

主要入口类：

- `OpenAiAuthorizationUrlGenerator`
- `OpenAiTokenClient`
- `OpenAiIdTokenParser`
- `ChatGptAccountClient`
- `OpenAiResponsesListener`
- `OpenAiChatCompletionsListener`

## 测试

运行全部模块测试：

```bash
./mvnw clean test
```

只测试 core：

```bash
./mvnw -pl core -am test
```

测试使用本地 HTTP Server，不依赖真实账号。WebSocket 测试覆盖协议 URI 构造；真实 ChatGPT WebSocket 握手需要在本地凭证环境中通过 `AuthorizationExample` 验证。
