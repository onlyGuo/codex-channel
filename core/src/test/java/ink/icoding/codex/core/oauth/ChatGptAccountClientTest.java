package ink.icoding.codex.core.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ink.icoding.codex.http.ChromeProxy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ChatGptAccountClientTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void enrichesOnConstructionAndFetchesQuotaWindows() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/backend-api/accounts/check/v4-2023-04-27", exchange -> {
            assertEquals("Bearer access-token", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("account-1", exchange.getRequestHeaders().getFirst("ChatGPT-Account-Id"));
            writeJson(exchange, "{\"accounts\":{\"account-1\":{\"plan_type\":\"team\"}}}");
        });
        server.createContext("/backend-api/subscriptions", exchange -> {
            assertEquals("account_id=account-1", exchange.getRequestURI().getRawQuery());
            assertEquals("Bearer access-token", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("account-1", exchange.getRequestHeaders().getFirst("ChatGPT-Account-Id"));
            writeJson(exchange, "{\"id\":\"subscription-1\",\"plan_type\":\"plus\","
                    + "\"active_until\":\"2026-07-23T00:51:23Z\"}");
        });
        server.createContext("/backend-api/wham/usage", exchange -> {
            assertEquals("Bearer access-token", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("account-1", exchange.getRequestHeaders().getFirst("ChatGPT-Account-Id"));
            writeJson(exchange, "{\"rate_limit\":{\"primary_window\":{\"used_percent\":20,"
                    + "\"limit_window_seconds\":18000,\"reset_at\":1800000000},"
                    + "\"secondary_window\":{\"used_percent\":40,\"limit_window_seconds\":604800,"
                    + "\"reset_at\":1800604800}}}");
        });
        server.createContext("/backend-api/wham/rate-limit-reset-credits", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals("Bearer access-token", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("account-1", exchange.getRequestHeaders().getFirst("ChatGPT-Account-Id"));
            writeJson(exchange, "{\"credits\":[{\"id\":\"credit-1\",\"available_count\":1}],"
                    + "\"available_count\":1,\"total_earned_count\":1}");
        });
        server.createContext("/backend-api/wham/rate-limit-reset-credits/consume", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("Bearer access-token", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("account-1", exchange.getRequestHeaders().getFirst("ChatGPT-Account-Id"));
            JsonNode request = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
            assertEquals("credit-1", request.path("credit_id").asText());
            assertEquals("request-1", request.path("idempotency_key").asText());
            writeJson(exchange, "{\"outcome\":\"consumed\"}");
        });
        server.createContext("/backend-api/codex/models", exchange -> {
            assertEquals("client_version=0.144.2", exchange.getRequestURI().getRawQuery());
            assertEquals("Bearer access-token", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("account-1", exchange.getRequestHeaders().getFirst("ChatGPT-Account-Id"));
            writeJson(exchange, "{\"models\":[{\"slug\":\"gpt-5\"}]}");
        });
        server.createContext("/backend-api/codex/responses/compact", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("responses=experimental", exchange.getRequestHeaders().getFirst("OpenAI-Beta"));
            JsonNode request = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
            assertEquals("gpt-5", request.path("model").asText());
            writeJson(exchange, "{\"output\":[{\"type\":\"compaction\"}]}");
        });
        server.createContext("/backend-api/wham/profiles/me", exchange ->
                writeJson(exchange, "{\"id\":\"profile-1\"}"));
        server.createContext("/backend-api/wham/workspace-messages", exchange ->
                writeJson(exchange, "{\"messages\":[{\"id\":\"message-1\"}]}"));
        server.createContext("/backend-api/wham/config/bundle", exchange ->
                writeJson(exchange, "{\"features\":{\"responses_websocket\":true}}"));
        server.createContext("/backend-api/wham/accounts/send_add_credits_nudge_email", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("account-1", exchange.getRequestHeaders().getFirst("ChatGPT-Account-Id"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            try (ChatGptAccountClient client = client(server, token("access-token", 3600, 1_800_000_000L), account())) {

                OpenAiAccount enriched = client.account();
                OpenAiQuotaInfo quota = client.fetchQuotaInfo();
                OpenAiRateLimitResetCredits credits = client.fetchRateLimitResetCredits();
                JsonNode consumed = client.consumeRateLimitResetCredit("credit-1", "request-1");
                JsonNode models = client.fetchModels("0.144.2");
                JsonNode compacted = client.compactResponses(
                        new ObjectMapper().readTree("{\"model\":\"gpt-5\",\"input\":[]}"));
                JsonNode profile = client.fetchProfile();
                JsonNode messages = client.fetchWorkspaceMessages();
                JsonNode config = client.fetchConfigBundle();
                client.sendAddCreditsNudgeEmail();

                assertEquals("plus", enriched.planType());
                assertEquals(Instant.parse("2026-07-23T00:51:23Z"), enriched.subscriptionExpiresAt());
                assertEquals(20.0, quota.fiveHour().usedPercent());
                assertEquals(80.0, quota.fiveHour().remainingPercent());
                assertEquals(18_000L, quota.fiveHour().limitWindowSeconds());
                assertEquals(40.0, quota.weekly().usedPercent());
                assertEquals(604_800L, quota.weekly().limitWindowSeconds());
                assertEquals(1, credits.availableCount());
                assertEquals(1, credits.totalEarnedCount());
                assertEquals("credit-1", credits.credits().get(0).id());
                assertEquals(1, credits.credits().get(0).availableCount());
                assertEquals("consumed", consumed.path("outcome").asText());
                assertEquals("gpt-5", models.path("models").get(0).path("slug").asText());
                assertEquals("compaction", compacted.path("output").get(0).path("type").asText());
                assertEquals("profile-1", profile.path("id").asText());
                assertEquals("message-1", messages.path("messages").get(0).path("id").asText());
                assertTrue(config.path("features").path("responses_websocket").asBoolean());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsAnEmptyTypedResetCreditList() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/backend-api/accounts/check/v4-2023-04-27", exchange ->
                writeJson(exchange, "{\"accounts\":{\"account-1\":{}}}"));
        server.createContext("/backend-api/subscriptions", exchange -> writeJson(exchange, "{}"));
        server.createContext("/backend-api/wham/rate-limit-reset-credits", exchange ->
                writeJson(exchange, "{\"credits\":[],\"available_count\":0,\"total_earned_count\":0}"));
        server.start();
        try {
            try (ChatGptAccountClient client = client(
                    server, token("access-token", 3600, 1_800_000_000L), account())) {
                OpenAiRateLimitResetCredits credits = client.fetchRateLimitResetCredits();

                assertTrue(credits.credits().isEmpty());
                assertEquals(0, credits.availableCount());
                assertEquals(0, credits.totalEarnedCount());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void automaticallyAndManuallyRefreshesTheToken() throws Exception {
        AtomicInteger refreshCalls = new AtomicInteger();
        CountDownLatch automaticRefresh = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/oauth/token", exchange -> {
            String expectedRefreshToken = refreshCalls.get() == 0 ? "refresh-token" : "refreshed-refresh";
            assertEquals(Map.of(
                    "grant_type", "refresh_token",
                    "refresh_token", expectedRefreshToken,
                    "client_id", OpenAiAuthorizationUrlGenerator.CLIENT_ID,
                    "scope", "openid profile email"), formParameters(exchange));
            refreshCalls.incrementAndGet();
            automaticRefresh.countDown();
            writeJson(exchange, "{\"access_token\":\"refreshed-access\",\"refresh_token\":\"refreshed-refresh\","
                    + "\"expires_in\":3600,\"earliest_refresh_at\":1800000000,\"scope\":\"openid profile email\","
                    + "\"token_type\":\"Bearer\"}");
        });
        server.createContext("/api/accounts", exchange ->
                writeJson(exchange, "{\"accounts\":[{\"id\":\"account-1\"}]}"));
        server.createContext("/oauth/revoke", exchange -> {
            assertEquals("refreshed-refresh", formParameters(exchange).get("token"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/backend-api/accounts/check/v4-2023-04-27", exchange ->
                writeJson(exchange, "{\"accounts\":{\"account-1\":{}}}"));
        server.createContext("/backend-api/subscriptions", exchange -> {
            assertEquals("Bearer expired-access", exchange.getRequestHeaders().getFirst("Authorization"));
            writeJson(exchange, "{\"plan_type\":\"plus\"}");
        });
        server.start();
        try {
            try (ChatGptAccountClient client = client(server, token("expired-access", 1, 1_800_000_000L), account())) {

                assertTrue(automaticRefresh.await(5, TimeUnit.SECONDS));
                assertEquals("refreshed-access", client.tokenResponse().accessToken());
                client.refreshToken();
                JsonNode accounts = client.fetchAuthAccounts();
                client.revokeToken();

                assertEquals(2, refreshCalls.get());
                assertEquals("refreshed-refresh", client.tokenResponse().refreshToken());
                assertEquals("account-1", accounts.path("accounts").get(0).path("id").asText());
                assertThrows(ChatGptAccountException.class, client::refreshToken);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesAWeeklyPrimaryWindowAsWeekly() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/backend-api/accounts/check/v4-2023-04-27", exchange ->
                writeJson(exchange, "{\"accounts\":{\"account-1\":{}}}"));
        server.createContext("/backend-api/subscriptions", exchange -> writeJson(exchange, "{}"));
        server.createContext("/backend-api/wham/usage", exchange -> writeJson(exchange,
                "{\"rate_limit\":{\"primary_window\":{\"used_percent\":74,"
                        + "\"limit_window_seconds\":604800,\"reset_at\":1784593959}}}"));
        server.start();
        try {
            try (ChatGptAccountClient client = client(server, token("access-token", 3600, 1_800_000_000L), account())) {
                OpenAiQuotaInfo quota = client.fetchQuotaInfo();

                assertNull(quota.fiveHour());
                assertEquals(74.0, quota.weekly().usedPercent());
                assertEquals(604_800L, quota.weekly().limitWindowSeconds());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamsResponsesEventsToTheListener() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/backend-api/accounts/check/v4-2023-04-27", exchange ->
                writeJson(exchange, "{\"accounts\":{\"account-1\":{}}}"));
        server.createContext("/backend-api/subscriptions", exchange -> writeJson(exchange, "{}"));
        server.createContext("/backend-api/codex/responses", exchange -> {
            assertEquals("Bearer access-token", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("account-1", exchange.getRequestHeaders().getFirst("Chatgpt-Account-Id"));
            assertEquals("responses=experimental", exchange.getRequestHeaders().getFirst("Openai-Beta"));
            assertEquals("codex-tui", exchange.getRequestHeaders().getFirst("Originator"));
            assertEquals("codex-tui/test", exchange.getRequestHeaders().getFirst("User-Agent"));
            JsonNode request = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
            assertEquals("gpt-5", request.path("model").asText());
            assertEquals(false, request.path("store").asBoolean());
            assertEquals("input_text", request.path("input").get(0).path("content").get(0).path("type").asText());
            writeSse(exchange, "event: response.output_text.delta\nid: event-1\ndata: {\"delta\":\"Hello\"}\n\n"
                    + "data: [DONE]\n\n");
        });
        server.start();
        try {
            try (ChatGptAccountClient client = client(server, token("access-token", 3600, 1_800_000_000L), account())) {
                List<OpenAiResponsesEvent> events = new ArrayList<>();
                client.streamResponses(
                        new ObjectMapper().readTree("{\"model\":\"gpt-5\",\"store\":true,\"input\":[{\"role\":\"user\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}]}]}"),
                        CodexOriginator.CODEX_TUI,
                        "codex-tui/test",
                        events::add);

                assertEquals(2, events.size());
                assertEquals("response.output_text.delta", events.get(0).event());
                assertEquals("{\"delta\":\"Hello\"}", events.get(0).data());
                assertEquals("event-1", events.get(0).id());
                assertEquals("message", events.get(1).event());
                assertEquals("[DONE]", events.get(1).data());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void convertsChatCompletionsRequestsAndStreamingChunks() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/backend-api/accounts/check/v4-2023-04-27", exchange ->
                writeJson(exchange, "{\"accounts\":{\"account-1\":{}}}"));
        server.createContext("/backend-api/subscriptions", exchange -> writeJson(exchange, "{}"));
        server.createContext("/backend-api/codex/responses", exchange -> {
            JsonNode request = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
            assertEquals("gpt-5", request.path("model").asText());
            assertEquals("user", request.path("input").get(0).path("role").asText());
            assertEquals("input_text", request.path("input").get(0).path("content").get(0).path("type").asText());
            assertEquals("Hello", request.path("input").get(0).path("content").get(0).path("text").asText());
            assertEquals(200, request.path("max_output_tokens").asInt());
            assertEquals("weather", request.path("tools").get(0).path("name").asText());
            assertEquals("json_schema", request.path("text").path("format").path("type").asText());
            assertEquals(false, request.path("store").asBoolean());
            assertEquals(true, request.path("stream").asBoolean());
            writeSse(exchange,
                    "event: response.created\n"
                            + "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_123\","
                            + "\"created_at\":1800000000,\"model\":\"gpt-5\"}}\n\n"
                            + "event: response.output_item.added\n"
                            + "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"message\"}}\n\n"
                            + "event: response.output_text.delta\n"
                            + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}\n\n"
                            + "event: response.completed\n"
                            + "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_123\","
                            + "\"created_at\":1800000000,\"model\":\"gpt-5\",\"status\":\"completed\","
                            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":2,\"total_tokens\":12}}}\n\n");
        });
        server.start();
        try {
            try (ChatGptAccountClient client = client(
                    server, token("access-token", 3600, 1_800_000_000L), account())) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode chatRequest = mapper.readTree("""
                        {
                          "model":"gpt-5",
                          "messages":[{"role":"user","content":"Hello"}],
                          "max_completion_tokens":200,
                          "tools":[{"type":"function","function":{"name":"weather","parameters":{"type":"object"}}}],
                          "response_format":{"type":"json_schema","json_schema":{"name":"answer","schema":{"type":"object"}}},
                          "stream":true,
                          "stream_options":{"include_usage":true}
                        }
                        """);
                List<JsonNode> chunks = new ArrayList<>();
                AtomicBoolean completed = new AtomicBoolean();

                client.streamChatCompletions(chatRequest, new OpenAiChatCompletionsListener() {
                    @Override
                    public void onChunk(JsonNode chunk) {
                        chunks.add(chunk);
                    }

                    @Override
                    public void onComplete() {
                        completed.set(true);
                    }
                });

                assertEquals(4, chunks.size());
                assertEquals("chatcmpl-123", chunks.get(0).path("id").asText());
                assertEquals("chat.completion.chunk", chunks.get(0).path("object").asText());
                assertEquals("assistant", chunks.get(0).path("choices").get(0).path("delta").path("role").asText());
                assertEquals("Hello", chunks.get(1).path("choices").get(0).path("delta").path("content").asText());
                assertEquals("stop", chunks.get(2).path("choices").get(0).path("finish_reason").asText());
                assertTrue(chunks.get(3).path("choices").isEmpty());
                assertEquals(10, chunks.get(3).path("usage").path("prompt_tokens").asInt());
                assertEquals(2, chunks.get(3).path("usage").path("completion_tokens").asInt());
                assertTrue(completed.get());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void createsNonStreamingResponsesAndChatCompletions() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/backend-api/accounts/check/v4-2023-04-27", exchange ->
                writeJson(exchange, "{\"accounts\":{\"account-1\":{}}}"));
        server.createContext("/backend-api/subscriptions", exchange -> writeJson(exchange, "{}"));
        server.createContext("/backend-api/codex/responses", exchange -> {
            calls.incrementAndGet();
            JsonNode request = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
            assertEquals(false, request.path("stream").asBoolean());
            assertEquals(false, request.path("store").asBoolean());
            writeJson(exchange, "{\"id\":\"resp_456\",\"object\":\"response\",\"created_at\":1800000000,"
                    + "\"model\":\"gpt-5\",\"status\":\"completed\",\"output\":[{\"type\":\"message\","
                    + "\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello\"}]}],"
                    + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1,\"total_tokens\":6}}");
        });
        server.start();
        try {
            try (ChatGptAccountClient client = client(
                    server, token("access-token", 3600, 1_800_000_000L), account())) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode response = client.createResponse(
                        mapper.readTree("{\"model\":\"gpt-5\",\"input\":\"Hello\"}"));
                JsonNode completion = client.createChatCompletion(mapper.readTree(
                        "{\"model\":\"gpt-5\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}],"
                                + "\"stream\":false}"));

                assertEquals("resp_456", response.path("id").asText());
                assertEquals("chatcmpl-456", completion.path("id").asText());
                assertEquals("chat.completion", completion.path("object").asText());
                assertEquals("Hello", completion.path("choices").get(0).path("message").path("content").asText());
                assertEquals("stop", completion.path("choices").get(0).path("finish_reason").asText());
                assertEquals(5, completion.path("usage").path("prompt_tokens").asInt());
                assertEquals(2, calls.get());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void persistsEnrichedAccountAndRefreshedTokenFiles() throws Exception {
        Path directory = Files.createTempDirectory("chatgpt-account-client-");
        Path tokenFile = directory.resolve("opentoken.json");
        Path accountFile = directory.resolve("openaccount.json");
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/oauth/token", exchange -> writeJson(exchange,
                "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\","
                        + "\"expires_in\":3600,\"earliest_refresh_at\":1800000000}"));
        server.createContext("/backend-api/accounts/check/v4-2023-04-27", exchange ->
                writeJson(exchange, "{\"accounts\":{\"account-1\":{}}}"));
        server.createContext("/backend-api/subscriptions", exchange -> writeJson(exchange,
                "{\"plan_type\":\"plus\",\"active_until\":\"2026-07-23T00:51:23Z\"}"));
        addTrainingSettingsContext(server);
        server.start();
        try {
            String baseUrl = "http://localhost:" + server.getAddress().getPort();
            try (ChatGptAccountClient client = new ChatGptAccountClient(
                    token("old-access", 3600, 1_800_000_000L),
                    account(),
                    new JdkTestHttpTransport(),
                    URI.create(baseUrl + "/backend-api/accounts/check/v4-2023-04-27"),
                    URI.create(baseUrl + "/backend-api/subscriptions"),
                    URI.create(baseUrl + "/backend-api/wham/usage"),
                    URI.create(baseUrl + "/backend-api/codex/responses"),
                    URI.create(baseUrl + "/oauth/token"),
                    objectMapper,
                    CLOCK,
                    tokenFile.toFile(),
                    accountFile.toFile())) {
                client.refreshToken();
            }

            assertEquals("new-access", objectMapper.readTree(tokenFile.toFile()).path("access_token").asText());
            assertEquals("new-refresh", objectMapper.readTree(tokenFile.toFile()).path("refresh_token").asText());
            assertEquals("2026-07-23T00:51:23Z",
                    objectMapper.readTree(accountFile.toFile()).path("subscriptionExpiresAt").asText());
        } finally {
            server.stop(0);
            Files.deleteIfExists(tokenFile);
            Files.deleteIfExists(accountFile);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void configuresAnAuthenticatedHttpProxyPerClient() {
        ChromeProxy proxy = ChatGptAccountClient.toChromeProxy(
                new HttpProxyConfig("proxy.example", 8080, "proxy-user", "proxy-password"));

        assertEquals(URI.create("http://proxy.example:8080"), proxy.uri());
        assertEquals("proxy-user", proxy.username());
        assertEquals("proxy-password", proxy.password());
    }

    @Test
    void buildsTheCodexResponsesWebSocketUri() {
        assertEquals(
                URI.create("wss://chatgpt.com/backend-api/codex/responses?feature=on"),
                ResponsesWebSocketStream.webSocketUri(
                        URI.create("https://chatgpt.com/backend-api/codex/responses?feature=on")));
        assertEquals(
                URI.create("ws://localhost:8080/responses"),
                ResponsesWebSocketStream.webSocketUri(URI.create("http://localhost:8080/responses")));
    }

    @Test
    void convertsLegacyChatFunctionMessagesAndRejectsLossyFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode request = mapper.readTree("""
                {
                  "model":"gpt-5",
                  "messages":[
                    {"role":"user","content":"Weather?"},
                    {"role":"assistant","content":null,"function_call":{"name":"weather","arguments":"{}"}},
                    {"role":"function","name":"weather","content":"sunny"}
                  ],
                  "functions":[{"name":"weather","parameters":{"type":"object"}}],
                  "function_call":{"name":"weather"},
                  "stream":true
                }
                """);

        JsonNode converted = ChatCompletionsToResponsesAdapter.toResponsesRequest(request, mapper);

        assertEquals("weather", converted.path("tools").get(0).path("name").asText());
        assertEquals("weather", converted.path("tool_choice").path("name").asText());
        assertEquals("function_call", converted.path("input").get(1).path("type").asText());
        assertEquals("call_legacy_0", converted.path("input").get(1).path("call_id").asText());
        assertEquals("function_call_output", converted.path("input").get(2).path("type").asText());
        assertEquals("call_legacy_0", converted.path("input").get(2).path("call_id").asText());

        ChatGptAccountException exception = assertThrows(ChatGptAccountException.class,
                () -> ChatCompletionsToResponsesAdapter.toResponsesRequest(
                        mapper.readTree("{\"model\":\"gpt-5\",\"messages\":[{\"role\":\"user\","
                                + "\"content\":\"Hello\"}],\"stream\":true,\"stop\":\"END\"}"), mapper));
        assertTrue(exception.getMessage().contains("cannot be losslessly mapped"));
    }

    private static ChatGptAccountClient client(
            HttpServer server, OpenAiTokenResponse tokenResponse, OpenAiAccount account) {
        addTrainingSettingsContext(server);
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        return new ChatGptAccountClient(
                tokenResponse,
                account,
                new JdkTestHttpTransport(),
                URI.create(baseUrl + "/backend-api/accounts/check/v4-2023-04-27"),
                URI.create(baseUrl + "/backend-api/subscriptions"),
                URI.create(baseUrl + "/backend-api/wham/usage"),
                URI.create(baseUrl + "/backend-api/codex/responses"),
                URI.create(baseUrl + "/oauth/token"),
                new ObjectMapper(),
                CLOCK);
    }

    private static void addTrainingSettingsContext(HttpServer server) {
        server.createContext("/backend-api/settings/account_user_setting", exchange -> {
            assertEquals("PATCH", exchange.getRequestMethod());
            assertEquals("feature=training_allowed&value=false", exchange.getRequestURI().getRawQuery());
            assertTrue(exchange.getRequestHeaders().getFirst("Authorization").startsWith("Bearer "));
            assertEquals("https://chatgpt.com", exchange.getRequestHeaders().getFirst("Origin"));
            assertEquals("https://chatgpt.com/", exchange.getRequestHeaders().getFirst("Referer"));
            assertEquals("application/json", exchange.getRequestHeaders().getFirst("Accept"));
            assertEquals(0, exchange.getRequestBody().readAllBytes().length);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
    }

    private static OpenAiTokenResponse token(String accessToken, long expiresIn, long earliestRefreshAt) {
        return new OpenAiTokenResponse(
                accessToken, "refresh-token", "id-token", expiresIn, earliestRefreshAt, "openid", "Bearer");
    }

    private static OpenAiAccount account() {
        return new OpenAiAccount("user@example.com", "account-1", "user-1", "free", "org-1", null);
    }

    private static Map<String, String> formParameters(HttpExchange exchange) throws java.io.IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return Arrays.stream(body.split("&"))
                .map(parameter -> parameter.split("=", 2))
                .collect(Collectors.toMap(
                        parameter -> URLDecoder.decode(parameter[0], StandardCharsets.UTF_8),
                        parameter -> URLDecoder.decode(parameter[1], StandardCharsets.UTF_8)));
    }

    private static void writeJson(HttpExchange exchange, String json) throws java.io.IOException {
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static void writeSse(HttpExchange exchange, String body) throws java.io.IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
