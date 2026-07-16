package ink.icoding.codex.core.oauth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ink.icoding.codex.http.ChromeBodyHandlers;
import ink.icoding.codex.http.ChromeHttpClient;
import ink.icoding.codex.http.ChromeHttpRequest;
import ink.icoding.codex.http.ChromeHttpResponse;
import ink.icoding.codex.http.ChromeHttpTransport;
import ink.icoding.codex.http.ChromeProxy;
import ink.icoding.codex.http.SseEvent;
import ink.icoding.codex.http.SseListener;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Objects;

/**
 * Stateful client for the ChatGPT account, subscription, and Codex quota APIs.
 * The constructor immediately enriches the supplied account with subscription data.
 */
public final class ChatGptAccountClient implements AutoCloseable {

    public static final URI ACCOUNT_CHECK_ENDPOINT =
            URI.create("https://chatgpt.com/backend-api/accounts/check/v4-2023-04-27");
    public static final URI SUBSCRIPTIONS_ENDPOINT =
            URI.create("https://chatgpt.com/backend-api/subscriptions");
    public static final URI USAGE_ENDPOINT =
            URI.create("https://chatgpt.com/backend-api/wham/usage");
    public static final URI RATE_LIMIT_RESET_CREDITS_ENDPOINT =
            URI.create("https://chatgpt.com/backend-api/wham/rate-limit-reset-credits");
    public static final URI RATE_LIMIT_RESET_CREDITS_CONSUME_ENDPOINT =
            URI.create("https://chatgpt.com/backend-api/wham/rate-limit-reset-credits/consume");
    public static final URI RESPONSES_ENDPOINT =
            URI.create("https://chatgpt.com/backend-api/codex/responses");
    public static final URI MODELS_ENDPOINT =
            URI.create("https://chatgpt.com/backend-api/codex/models");
    public static final URI RESPONSES_COMPACT_ENDPOINT =
            URI.create("https://chatgpt.com/backend-api/codex/responses/compact");
    public static final URI PROFILE_ENDPOINT =
            URI.create("https://chatgpt.com/backend-api/wham/profiles/me");
    public static final URI WORKSPACE_MESSAGES_ENDPOINT =
            URI.create("https://chatgpt.com/backend-api/wham/workspace-messages");
    public static final URI CONFIG_BUNDLE_ENDPOINT =
            URI.create("https://chatgpt.com/backend-api/wham/config/bundle");
    public static final URI ADD_CREDITS_NUDGE_ENDPOINT =
            URI.create("https://chatgpt.com/backend-api/wham/accounts/send_add_credits_nudge_email");
    public static final URI ACCOUNT_USER_SETTING_ENDPOINT =
            URI.create("https://chatgpt.com/backend-api/settings/account_user_setting");
    public static final String DEFAULT_CODEX_USER_AGENT =
            "codex_cli_rs/0.0.1 (" + System.getProperty("os.name") + "; " + System.getProperty("os.arch") + ")";

    private static final long REFRESH_SKEW_SECONDS = 10 * 60;
    private static final long REFRESH_RETRY_SECONDS = 60;
    private static final long FIVE_HOUR_WINDOW_SECONDS = 5 * 60 * 60;
    private static final long WEEKLY_WINDOW_SECONDS = 7 * 24 * 60 * 60;

    private final ChromeHttpTransport httpClient;
    private final URI accountCheckEndpoint;
    private final URI accountUserSettingEndpoint;
    private final URI subscriptionsEndpoint;
    private final URI usageEndpoint;
    private final URI rateLimitResetCreditsEndpoint;
    private final URI rateLimitResetCreditsConsumeEndpoint;
    private final URI responsesEndpoint;
    private final URI tokenEndpoint;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ScheduledExecutorService refreshScheduler;
    private final File tokenFile;
    private final File accountFile;

    private OpenAiTokenResponse tokenResponse;
    private OpenAiAccount account;
    private Instant tokenReceivedAt;
    private ScheduledFuture<?> scheduledRefresh;
    private boolean closed;
    private boolean refreshDisabled;

    /**
     * Creates a client and immediately enriches {@code account} from the ChatGPT backend.
     */
    public ChatGptAccountClient(OpenAiTokenResponse tokenResponse, OpenAiAccount account) {
        this(tokenResponse, account, (HttpProxyConfig) null);
    }

    /**
     * Creates a client that routes all requests through the supplied HTTP proxy.
     */
    public ChatGptAccountClient(
            OpenAiTokenResponse tokenResponse, OpenAiAccount account, HttpProxyConfig proxyConfig) {
        this(
                tokenResponse,
                account,
                createHttpTransport(proxyConfig),
                ACCOUNT_CHECK_ENDPOINT,
                SUBSCRIPTIONS_ENDPOINT,
                USAGE_ENDPOINT,
                RESPONSES_ENDPOINT,
                OpenAiTokenClient.TOKEN_ENDPOINT,
                newObjectMapper(),
                Clock.systemUTC());
    }

    /** Creates a client over a caller-supplied Chrome HTTP transport. Ownership is transferred. */
    public ChatGptAccountClient(
            OpenAiTokenResponse tokenResponse, OpenAiAccount account, ChromeHttpTransport httpClient) {
        this(
                tokenResponse,
                account,
                httpClient,
                ACCOUNT_CHECK_ENDPOINT,
                SUBSCRIPTIONS_ENDPOINT,
                USAGE_ENDPOINT,
                RESPONSES_ENDPOINT,
                OpenAiTokenClient.TOKEN_ENDPOINT,
                newObjectMapper(),
                Clock.systemUTC());
    }

    /**
     * Loads token and account data from JSON files. Refreshed tokens and enriched account data
     * are automatically written back to these same files.
     */
    public ChatGptAccountClient(File tokenFile, File accountFile) throws IOException {
        this(tokenFile, accountFile, (HttpProxyConfig) null);
    }

    /**
     * Loads JSON state and routes all requests through the supplied HTTP proxy.
     */
    public ChatGptAccountClient(File tokenFile, File accountFile, HttpProxyConfig proxyConfig) throws IOException {
        this(
                readJson(tokenFile, OpenAiTokenResponse.class),
                readJson(accountFile, OpenAiAccount.class),
                createHttpTransport(proxyConfig),
                ACCOUNT_CHECK_ENDPOINT,
                SUBSCRIPTIONS_ENDPOINT,
                USAGE_ENDPOINT,
                RESPONSES_ENDPOINT,
                OpenAiTokenClient.TOKEN_ENDPOINT,
                newObjectMapper(),
                Clock.systemUTC(),
                requireFile(tokenFile, "tokenFile"),
                requireFile(accountFile, "accountFile"));
    }

    /** Loads JSON state and transfers ownership of a caller-supplied Chrome HTTP transport. */
    public ChatGptAccountClient(
            File tokenFile, File accountFile, ChromeHttpTransport httpClient) throws IOException {
        this(
                readJson(tokenFile, OpenAiTokenResponse.class),
                readJson(accountFile, OpenAiAccount.class),
                httpClient,
                ACCOUNT_CHECK_ENDPOINT,
                SUBSCRIPTIONS_ENDPOINT,
                USAGE_ENDPOINT,
                RESPONSES_ENDPOINT,
                OpenAiTokenClient.TOKEN_ENDPOINT,
                newObjectMapper(),
                Clock.systemUTC(),
                requireFile(tokenFile, "tokenFile"),
                requireFile(accountFile, "accountFile"));
    }

    ChatGptAccountClient(
            OpenAiTokenResponse tokenResponse,
            OpenAiAccount account,
            ChromeHttpTransport httpClient,
            URI accountCheckEndpoint,
            URI subscriptionsEndpoint,
            URI usageEndpoint,
            URI responsesEndpoint,
            URI tokenEndpoint,
            ObjectMapper objectMapper,
            Clock clock) {
        this(
                tokenResponse,
                account,
                httpClient,
                accountCheckEndpoint,
                subscriptionsEndpoint,
                usageEndpoint,
                responsesEndpoint,
                tokenEndpoint,
                objectMapper,
                clock,
                null,
                null);
    }

    ChatGptAccountClient(
            OpenAiTokenResponse tokenResponse,
            OpenAiAccount account,
            ChromeHttpTransport httpClient,
            URI accountCheckEndpoint,
            URI subscriptionsEndpoint,
            URI usageEndpoint,
            URI responsesEndpoint,
            URI tokenEndpoint,
            ObjectMapper objectMapper,
            Clock clock,
            File tokenFile,
            File accountFile) {
        this.tokenResponse = Objects.requireNonNull(tokenResponse, "tokenResponse");
        this.account = Objects.requireNonNull(account, "account");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.accountCheckEndpoint = Objects.requireNonNull(accountCheckEndpoint, "accountCheckEndpoint");
        this.accountUserSettingEndpoint = relatedAccountEndpoint(
                accountCheckEndpoint, "/settings/account_user_setting", ACCOUNT_USER_SETTING_ENDPOINT);
        this.subscriptionsEndpoint = Objects.requireNonNull(subscriptionsEndpoint, "subscriptionsEndpoint");
        this.usageEndpoint = Objects.requireNonNull(usageEndpoint, "usageEndpoint");
        this.rateLimitResetCreditsEndpoint = relatedUsageEndpoint(
                usageEndpoint, "/rate-limit-reset-credits", RATE_LIMIT_RESET_CREDITS_ENDPOINT);
        this.rateLimitResetCreditsConsumeEndpoint = relatedUsageEndpoint(
                usageEndpoint, "/rate-limit-reset-credits/consume", RATE_LIMIT_RESET_CREDITS_CONSUME_ENDPOINT);
        this.responsesEndpoint = Objects.requireNonNull(responsesEndpoint, "responsesEndpoint");
        this.tokenEndpoint = Objects.requireNonNull(tokenEndpoint, "tokenEndpoint");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tokenFile = tokenFile;
        this.accountFile = accountFile;
        this.refreshScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "openai-token-refresh");
            thread.setDaemon(true);
            return thread;
        });
        this.tokenReceivedAt = clock.instant();
        try {
            setTrainingAllowed(false);
            enrichAccount();
            scheduleNextRefresh();
        } catch (RuntimeException exception) {
            refreshScheduler.shutdownNow();
            httpClient.close();
            throw exception;
        }
    }

    /** Returns the account enriched during construction or the latest explicit refresh. */
    public synchronized OpenAiAccount account() {
        return account;
    }

    /** Returns the latest token response, including a token obtained through refresh. */
    public synchronized OpenAiTokenResponse tokenResponse() {
        return tokenResponse;
    }

    /**
     * Manually exchanges the stored refresh token for a new access token and updates this client.
     */
    public synchronized OpenAiTokenResponse refreshToken() {
        if (refreshDisabled) {
            throw new ChatGptAccountException("Token refresh is disabled because the refresh token was revoked", -1, null);
        }
        OpenAiTokenResponse refreshed = refreshTokenInternal();
        scheduleNextRefresh();
        return refreshed;
    }

    /** Lists the OpenAI account identities available to the current access token. */
    public synchronized JsonNode fetchAuthAccounts() {
        return new OpenAiTokenClient(httpClient, tokenEndpoint, objectMapper).fetchAccounts(accessToken());
    }

    /** Updates the ChatGPT account training setting. Constructors disable training before enrichment. */
    public synchronized void setTrainingAllowed(boolean allowed) {
        URI endpoint = URI.create(accountUserSettingEndpoint
                + "?feature=training_allowed&value=" + allowed);
        ChromeHttpRequest request = ChromeHttpRequest.newBuilder(endpoint)
                .header("Authorization", "Bearer " + accessToken())
                .header("Origin", "https://chatgpt.com")
                .header("Referer", "https://chatgpt.com/")
                .header("Accept", "application/json")
                .methodWithEmptyBody("PATCH")
                .build();
        try {
            ChromeHttpResponse<String> response = httpClient.send(request, ChromeBodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ChatGptAccountException(
                        "ChatGPT training setting returned HTTP " + response.statusCode()
                                + (response.body().isBlank() ? "" : ":\n" + response.body()),
                        response.statusCode(), null);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ChatGptAccountException("Interrupted while updating the ChatGPT training setting", -1, exception);
        } catch (IOException exception) {
            throw new ChatGptAccountException("Could not update the ChatGPT training setting", -1, exception);
        }
    }

    /** Revokes the stored refresh token and stops automatic token refresh. */
    public synchronized void revokeToken() {
        revokeToken(requireText(tokenResponse.refreshToken(), "tokenResponse.refreshToken"), "refresh_token");
        refreshDisabled = true;
        cancelScheduledRefresh();
    }

    /** Revokes a specific OAuth token using an explicit token type hint. */
    public synchronized void revokeToken(String token, String tokenTypeHint) {
        new OpenAiTokenClient(httpClient, tokenEndpoint, objectMapper).revokeToken(token, tokenTypeHint);
    }

    private OpenAiTokenResponse refreshTokenInternal() {
        String refreshToken = requireText(tokenResponse.refreshToken(), "tokenResponse.refreshToken");
        String form = "grant_type=refresh_token"
                + "&refresh_token=" + formValue(refreshToken)
                + "&client_id=" + formValue(OpenAiAuthorizationUrlGenerator.CLIENT_ID)
                + "&scope=" + formValue("openid profile email");
        ChromeHttpRequest request = ChromeHttpRequest.newBuilder(tokenEndpoint)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(form)
                .build();
        try {
            ChromeHttpResponse<String> response = httpClient.send(request, ChromeBodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ChatGptAccountException(
                        "OpenAI token endpoint returned HTTP " + response.statusCode(), response.statusCode(), null);
            }
            OpenAiTokenResponse refreshed = parseTokenResponse(response.body(), response.statusCode());
            OpenAiTokenResponse updatedToken = mergeTokenResponse(tokenResponse, refreshed);
            persistToken(updatedToken);
            tokenResponse = updatedToken;
            tokenReceivedAt = clock.instant();
            return tokenResponse;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ChatGptAccountException("Interrupted while refreshing the OpenAI token", -1, exception);
        } catch (IOException exception) {
            throw new ChatGptAccountException("Could not refresh the OpenAI token", -1, exception);
        }
    }

    /**
     * Fetches the account and subscription metadata again. The subscription plan takes priority
     * over the plan type carried by the ID token.
     */
    public synchronized OpenAiAccount enrichAccount() {
        String accountId = requireText(account.chatgptAccountId(), "account.chatgptAccountId");
        String accessToken = accessToken();
        JsonNode accountCheck = getJson(accountCheckEndpoint, accessToken, true);
        JsonNode subscription = getJson(subscriptionUri(accountId), accessToken, true);

        String effectivePlanType = firstPresent(
                planType(subscription, accountId),
                planType(accountCheck, accountId),
                account.planType());
        OpenAiAccount enrichedAccount = new OpenAiAccount(
                account.email(),
                account.chatgptAccountId(),
                account.chatgptUserId(),
                effectivePlanType,
                account.organizationId(),
                expiration(subscription));
        try {
            persistAccount(enrichedAccount);
        } catch (IOException exception) {
            throw new ChatGptAccountException("Could not persist the OpenAI account JSON", -1, exception);
        }
        account = enrichedAccount;
        return enrichedAccount;
    }

    /**
     * Fetches Codex five-hour and weekly rate-limit windows using the stored access token.
     */
    public synchronized OpenAiQuotaInfo fetchQuotaInfo() {
        JsonNode response = fetchUsageJson();
        JsonNode rateLimit = response.path("rate_limit");
        if (!rateLimit.isObject()) {
            throw new ChatGptAccountException("ChatGPT usage response does not contain rate_limit", 200, null);
        }
        OpenAiQuotaWindow primaryWindow = quotaWindow(rateLimit.path("primary_window"));
        OpenAiQuotaWindow secondaryWindow = quotaWindow(rateLimit.path("secondary_window"));
        return new OpenAiQuotaInfo(
                findWindow(FIVE_HOUR_WINDOW_SECONDS, primaryWindow, secondaryWindow),
                findWindow(WEEKLY_WINDOW_SECONDS, primaryWindow, secondaryWindow));
    }

    /** Returns the complete Codex usage payload without discarding server-defined fields. */
    public synchronized JsonNode fetchUsageJson() {
        return getJson(usageEndpoint, accessToken(), true);
    }

    /** Returns the Codex profile associated with the current account. */
    public synchronized JsonNode fetchProfile() {
        return getJson(relatedUsageEndpoint(usageEndpoint, "/profiles/me", PROFILE_ENDPOINT), accessToken(), true);
    }

    /** Returns workspace messages visible to the current Codex account. */
    public synchronized JsonNode fetchWorkspaceMessages() {
        return getJson(
                relatedUsageEndpoint(usageEndpoint, "/workspace-messages", WORKSPACE_MESSAGES_ENDPOINT),
                accessToken(), true);
    }

    /** Returns the remote Codex configuration bundle for the current account. */
    public synchronized JsonNode fetchConfigBundle() {
        return getJson(
                relatedUsageEndpoint(usageEndpoint, "/config/bundle", CONFIG_BUNDLE_ENDPOINT),
                accessToken(), true);
    }

    /** Requests the account-owner notification used when additional Codex credits are needed. */
    public synchronized void sendAddCreditsNudgeEmail() {
        postWithoutResponse(
                relatedUsageEndpoint(
                        usageEndpoint, "/accounts/send_add_credits_nudge_email", ADD_CREDITS_NUDGE_ENDPOINT),
                objectMapper.createObjectNode(), accessToken(), true);
    }

    /** Returns the available Codex rate-limit reset credits and account-level credit counters. */
    public synchronized OpenAiRateLimitResetCredits fetchRateLimitResetCredits() {
        JsonNode response = fetchRateLimitResetCreditsJson();
        JsonNode creditsNode = response.path("credits");
        if (!creditsNode.isArray()) {
            throw new ChatGptAccountException(
                    "ChatGPT reset-credit response does not contain a credits array", 200, null);
        }

        List<OpenAiRateLimitResetCredit> credits = new ArrayList<>();
        for (JsonNode credit : creditsNode) {
            String id = credit.path("id").asText(null);
            if (id == null || id.isBlank()) {
                throw new ChatGptAccountException(
                        "ChatGPT reset-credit response contains a credit without an id", 200, null);
            }
            credits.add(new OpenAiRateLimitResetCredit(id, credit.path("available_count").asInt(0)));
        }
        return new OpenAiRateLimitResetCredits(
                credits,
                response.path("available_count").asInt(0),
                response.path("total_earned_count").asInt(0));
    }

    /** Returns the unmodified server response for callers that need fields not represented by the model. */
    public synchronized JsonNode fetchRateLimitResetCreditsJson() {
        return getJson(rateLimitResetCreditsEndpoint, accessToken(), true);
    }

    /** Consumes a reset credit with a generated idempotency key. */
    public synchronized JsonNode consumeRateLimitResetCredit(String creditId) {
        return consumeRateLimitResetCredit(creditId, UUID.randomUUID().toString());
    }

    /**
     * Consumes a reset credit. Reuse {@code idempotencyKey} when retrying the same operation.
     */
    public synchronized JsonNode consumeRateLimitResetCredit(String creditId, String idempotencyKey) {
        ObjectNode requestBody = objectMapper.createObjectNode()
                .put("credit_id", requireText(creditId, "creditId"))
                .put("idempotency_key", requireText(idempotencyKey, "idempotencyKey"));
        return postJson(rateLimitResetCreditsConsumeEndpoint, requestBody, accessToken(), true);
    }

    /** Returns the remote Codex model catalog visible to the authenticated account. */
    public synchronized JsonNode fetchModels() {
        return getJson(modelsEndpoint(null), accessToken(), true);
    }

    /** Returns the remote Codex model catalog for a specific Codex client version. */
    public synchronized JsonNode fetchModels(String clientVersion) {
        return getJson(modelsEndpoint(requireText(clientVersion, "clientVersion")), accessToken(), true);
    }

    /** Uses the Codex server-side Responses compaction endpoint for a long input history. */
    public synchronized JsonNode compactResponses(JsonNode requestBody) {
        requireObjectRequest(requestBody, "Responses compact");
        return postCodexJson(responsesCompactEndpoint(), requestBody, CodexOriginator.CODEX_CLI_RS,
                DEFAULT_CODEX_USER_AGENT);
    }

    /** Creates a non-streaming OpenAI Responses response through the ChatGPT Codex backend. */
    public synchronized JsonNode createResponse(JsonNode requestBody) {
        ObjectNode backendRequest = responsesBackendRequest(requestBody, false);
        return postCodexJson(responsesEndpoint, backendRequest, CodexOriginator.CODEX_CLI_RS,
                DEFAULT_CODEX_USER_AGENT);
    }

    /** Streams Responses events with the default {@code codex_cli_rs} originator. */
    public void streamResponses(JsonNode requestBody, OpenAiResponsesListener listener) {
        streamResponses(requestBody, CodexOriginator.CODEX_CLI_RS, DEFAULT_CODEX_USER_AGENT, listener);
    }

    /** Streams Responses events with the specified Codex originator. */
    public void streamResponses(
            JsonNode requestBody, CodexOriginator originator, OpenAiResponsesListener listener) {
        streamResponses(requestBody, originator, DEFAULT_CODEX_USER_AGENT, listener);
    }

    /**
     * Sends a Responses request and synchronously dispatches every Server-Sent Event to
     * {@code listener}. The Chrome transport derives the required Host header from the endpoint
     * URI and dispatches parsed SSE events through the shared HTTP module.
     */
    public void streamResponses(
            JsonNode requestBody,
            CodexOriginator originator,
            String userAgent,
            OpenAiResponsesListener listener) {
        Objects.requireNonNull(requestBody, "requestBody");
        Objects.requireNonNull(originator, "originator");
        Objects.requireNonNull(listener, "listener");
        if (!requestBody.isObject()) {
            ChatGptAccountException exception = new ChatGptAccountException(
                    "Responses request body must be a JSON object", -1, null);
            listener.onError(exception);
            throw exception;
        }

        ObjectNode backendRequest = responsesBackendRequest(requestBody, true);
        String serializedRequest;
        try {
            serializedRequest = objectMapper.writeValueAsString(backendRequest);
        } catch (JsonProcessingException exception) {
            ChatGptAccountException wrapped = new ChatGptAccountException(
                    "Could not serialize the Responses request", -1, exception);
            listener.onError(wrapped);
            throw wrapped;
        }

        ChromeHttpRequest request = ChromeHttpRequest.newBuilder(responsesEndpoint)
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + accessToken())
                .header("chatgpt-account-id", requireText(account.chatgptAccountId(), "account.chatgptAccountId"))
                .header("OpenAI-Beta", "responses=experimental")
                .header("originator", originator.headerValue())
                .header("User-Agent", requireText(userAgent, "userAgent"))
                .header("Content-Type", "application/json")
                .POST(serializedRequest)
                .build();

        try {
            httpClient.streamSse(request, new SseListener() {
                @Override
                public void onOpen(ChromeHttpResponse.Metadata response) {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new ChatGptAccountException(
                                "ChatGPT Responses endpoint returned HTTP " + response.statusCode(),
                                response.statusCode(), null);
                    }
                    listener.onOpen();
                }

                @Override
                public void onEvent(SseEvent event) {
                    listener.onEvent(new OpenAiResponsesEvent(
                            event.event(), event.data(), event.id(),
                            event.retry() == null ? null : event.retry().toMillis()));
                }

                @Override
                public void onClosed() {
                    listener.onComplete();
                }

                @Override
                public void onError(Throwable error) {
                    listener.onError(error instanceof ChatGptAccountException accountException
                            ? accountException
                            : new ChatGptAccountException("Could not stream ChatGPT Responses", -1, error));
                }
            });
        } catch (ChatGptAccountException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            notifyAndThrow(listener, new ChatGptAccountException(
                    "Interrupted while streaming ChatGPT Responses", -1, exception));
        } catch (IOException exception) {
            notifyAndThrow(listener, new ChatGptAccountException(
                    "Could not stream ChatGPT Responses", -1, exception));
        }
    }

    /** Streams official Responses events over the Codex Responses WebSocket transport. */
    public void streamResponsesWebSocket(JsonNode requestBody, OpenAiResponsesListener listener) {
        streamResponsesWebSocket(
                requestBody, CodexOriginator.CODEX_CLI_RS, DEFAULT_CODEX_USER_AGENT, listener);
    }

    /** Streams official Responses events over WebSocket with explicit Codex client headers. */
    public void streamResponsesWebSocket(
            JsonNode requestBody,
            CodexOriginator originator,
            String userAgent,
            OpenAiResponsesListener listener) {
        Objects.requireNonNull(listener, "listener");
        ObjectNode wireRequest = responsesBackendRequest(requestBody, true);
        wireRequest.put("type", "response.create");
        ResponsesWebSocketStream.stream(
                httpClient,
                objectMapper,
                responsesEndpoint,
                wireRequest,
                accessToken(),
                requireText(account.chatgptAccountId(), "account.chatgptAccountId"),
                Objects.requireNonNull(originator, "originator"),
                requireText(userAgent, "userAgent"),
                listener);
    }

    /**
     * Streams an OpenAI Chat Completions request through the ChatGPT Codex Responses backend.
     * The listener receives public {@code chat.completion.chunk} JSON objects.
     *
     * <p>Fields without a lossless Responses equivalent are rejected instead of being dropped.</p>
     */
    public void streamChatCompletions(JsonNode requestBody, OpenAiChatCompletionsListener listener) {
        Objects.requireNonNull(listener, "listener");
        ObjectNode responsesRequest;
        try {
            responsesRequest = ChatCompletionsToResponsesAdapter.toResponsesRequest(requestBody, objectMapper);
        } catch (ChatGptAccountException exception) {
            listener.onError(exception);
            throw exception;
        }
        streamResponses(
                responsesRequest,
                new ChatCompletionsStreamAdapter(
                        listener,
                        requestBody.path("model").asText(null),
                        ChatCompletionsToResponsesAdapter.includesUsage(requestBody),
                        objectMapper,
                        clock));
    }

    /** Streams Chat Completions chunks through the Codex Responses WebSocket transport. */
    public void streamChatCompletionsWebSocket(
            JsonNode requestBody, OpenAiChatCompletionsListener listener) {
        Objects.requireNonNull(listener, "listener");
        ObjectNode responsesRequest;
        try {
            responsesRequest = ChatCompletionsToResponsesAdapter.toResponsesRequest(requestBody, objectMapper);
        } catch (ChatGptAccountException exception) {
            listener.onError(exception);
            throw exception;
        }
        streamResponsesWebSocket(
                responsesRequest,
                new ChatCompletionsStreamAdapter(
                        listener,
                        requestBody.path("model").asText(null),
                        ChatCompletionsToResponsesAdapter.includesUsage(requestBody),
                        objectMapper,
                        clock));
    }

    /** Creates a non-streaming OpenAI Chat Completions response through Responses conversion. */
    public synchronized JsonNode createChatCompletion(JsonNode requestBody) {
        ObjectNode responsesRequest = ChatCompletionsToResponsesAdapter.toResponsesRequest(
                requestBody, objectMapper, false);
        JsonNode response = createResponse(responsesRequest);
        return ChatCompletionsResponseAdapter.toChatCompletion(
                response, requestBody.path("model").asText(null), objectMapper, clock);
    }

    private String accessToken() {
        return requireText(tokenResponse.accessToken(), "tokenResponse.accessToken");
    }

    private ObjectNode responsesBackendRequest(JsonNode requestBody, boolean stream) {
        ObjectNode backendRequest = requireObjectRequest(requestBody, "Responses").deepCopy();
        backendRequest.put("store", false);
        backendRequest.put("stream", stream);
        normalizeLegacyInputTextTypes(backendRequest);
        return backendRequest;
    }

    private static ObjectNode requireObjectRequest(JsonNode requestBody, String operation) {
        if (!(requestBody instanceof ObjectNode object)) {
            throw new ChatGptAccountException(operation + " request body must be a JSON object", -1, null);
        }
        return object;
    }

    private static void normalizeLegacyInputTextTypes(ObjectNode requestBody) {
        for (JsonNode message : requestBody.path("input")) {
            JsonNode content = message.path("content");
            for (JsonNode contentPart : content) {
                if (contentPart instanceof ObjectNode contentObject
                        && "text".equals(contentObject.path("type").asText())) {
                    contentObject.put("type", "input_text");
                }
            }
        }
    }

    private static void notifyAndThrow(OpenAiResponsesListener listener, ChatGptAccountException exception) {
        listener.onError(exception);
        throw exception;
    }

    private synchronized void scheduleNextRefresh() {
        if (closed || refreshDisabled) {
            return;
        }
        cancelScheduledRefresh();
        Duration delay = Duration.between(clock.instant(), nextRefreshAt());
        scheduledRefresh = refreshScheduler.schedule(
                this::runScheduledRefresh, Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
    }

    private synchronized void scheduleRefreshRetry() {
        if (!closed && !refreshDisabled) {
            cancelScheduledRefresh();
            scheduledRefresh = refreshScheduler.schedule(
                    this::runScheduledRefresh, REFRESH_RETRY_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void runScheduledRefresh() {
        try {
            synchronized (this) {
                if (closed || refreshDisabled) {
                    return;
                }
                refreshTokenInternal();
                scheduleNextRefresh();
            }
        } catch (ChatGptAccountException exception) {
            scheduleRefreshRetry();
        }
    }

    private Instant nextRefreshAt() {
        if (tokenResponse.expiresIn() <= 0) {
            return clock.instant();
        }
        return tokenReceivedAt.plusSeconds(tokenResponse.expiresIn()).minusSeconds(REFRESH_SKEW_SECONDS);
    }

    private void cancelScheduledRefresh() {
        if (scheduledRefresh != null) {
            scheduledRefresh.cancel(false);
            scheduledRefresh = null;
        }
    }

    private OpenAiTokenResponse parseTokenResponse(String body, int statusCode) {
        try {
            return objectMapper.readValue(body, OpenAiTokenResponse.class);
        } catch (JsonProcessingException exception) {
            throw new ChatGptAccountException("Could not parse the OpenAI token response", statusCode, exception);
        }
    }

    private static OpenAiTokenResponse mergeTokenResponse(
            OpenAiTokenResponse previous, OpenAiTokenResponse refreshed) {
        return new OpenAiTokenResponse(
                firstPresent(refreshed.accessToken(), previous.accessToken()),
                firstPresent(refreshed.refreshToken(), previous.refreshToken()),
                firstPresent(refreshed.idToken(), previous.idToken()),
                refreshed.expiresIn() > 0 ? refreshed.expiresIn() : previous.expiresIn(),
                refreshed.earliestRefreshAt() > 0 ? refreshed.earliestRefreshAt() : previous.earliestRefreshAt(),
                firstPresent(refreshed.scope(), previous.scope()),
                firstPresent(refreshed.tokenType(), previous.tokenType()));
    }

    private JsonNode getJson(URI uri, String accessToken, boolean includeAccountHeader) {
        ChromeHttpRequest.Builder request = ChromeHttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .GET();
        if (includeAccountHeader) {
            request.header("ChatGPT-Account-Id", requireText(account.chatgptAccountId(), "account.chatgptAccountId"));
        }
        try {
            ChromeHttpResponse<String> response = httpClient.send(request.build(), ChromeBodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ChatGptAccountException(
                        "ChatGPT backend returned HTTP " + response.statusCode() + ":\n" + response.body(), response.statusCode(), null);
            }
            try {
                JsonNode body = objectMapper.readTree(response.body());
                if (body == null || !body.isObject()) {
                    throw new ChatGptAccountException(
                            "ChatGPT backend response must be a JSON object", response.statusCode(), null);
                }
                return body;
            } catch (JsonProcessingException exception) {
                throw new ChatGptAccountException(
                        "Could not parse the ChatGPT backend response", response.statusCode(), exception);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ChatGptAccountException("Interrupted while calling the ChatGPT backend", -1, exception);
        } catch (IOException exception) {
            throw new ChatGptAccountException("Could not call the ChatGPT backend", -1, exception);
        }
    }

    private JsonNode postJson(URI uri, JsonNode requestBody, String accessToken, boolean includeAccountHeader) {
        String serializedRequest;
        try {
            serializedRequest = objectMapper.writeValueAsString(requestBody);
        } catch (JsonProcessingException exception) {
            throw new ChatGptAccountException("Could not serialize the ChatGPT backend request", -1, exception);
        }
        ChromeHttpRequest.Builder request = ChromeHttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(serializedRequest);
        if (includeAccountHeader) {
            request.header("ChatGPT-Account-Id", requireText(account.chatgptAccountId(), "account.chatgptAccountId"));
        }
        try {
            ChromeHttpResponse<String> response = httpClient.send(request.build(), ChromeBodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ChatGptAccountException(
                        "ChatGPT backend returned HTTP " + response.statusCode(), response.statusCode(), null);
            }
            try {
                JsonNode body = objectMapper.readTree(response.body());
                if (body == null || !body.isObject()) {
                    throw new ChatGptAccountException(
                            "ChatGPT backend response must be a JSON object", response.statusCode(), null);
                }
                return body;
            } catch (JsonProcessingException exception) {
                throw new ChatGptAccountException(
                        "Could not parse the ChatGPT backend response", response.statusCode(), exception);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ChatGptAccountException("Interrupted while calling the ChatGPT backend", -1, exception);
        } catch (IOException exception) {
            throw new ChatGptAccountException("Could not call the ChatGPT backend", -1, exception);
        }
    }

    private void postWithoutResponse(
            URI uri, JsonNode requestBody, String accessToken, boolean includeAccountHeader) {
        String serializedRequest;
        try {
            serializedRequest = objectMapper.writeValueAsString(requestBody);
        } catch (JsonProcessingException exception) {
            throw new ChatGptAccountException("Could not serialize the ChatGPT backend request", -1, exception);
        }
        ChromeHttpRequest.Builder request = ChromeHttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(serializedRequest);
        if (includeAccountHeader) {
            request.header("ChatGPT-Account-Id", requireText(account.chatgptAccountId(), "account.chatgptAccountId"));
        }
        try {
            ChromeHttpResponse<Void> response = httpClient.send(request.build(), ChromeBodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ChatGptAccountException(
                        "ChatGPT backend returned HTTP " + response.statusCode(), response.statusCode(), null);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ChatGptAccountException("Interrupted while calling the ChatGPT backend", -1, exception);
        } catch (IOException exception) {
            throw new ChatGptAccountException("Could not call the ChatGPT backend", -1, exception);
        }
    }

    private JsonNode postCodexJson(
            URI uri, JsonNode requestBody, CodexOriginator originator, String userAgent) {
        String serializedRequest;
        try {
            serializedRequest = objectMapper.writeValueAsString(requestBody);
        } catch (JsonProcessingException exception) {
            throw new ChatGptAccountException("Could not serialize the Codex request", -1, exception);
        }
        ChromeHttpRequest request = ChromeHttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken())
                .header("ChatGPT-Account-Id", requireText(account.chatgptAccountId(), "account.chatgptAccountId"))
                .header("OpenAI-Beta", "responses=experimental")
                .header("originator", originator.headerValue())
                .header("User-Agent", requireText(userAgent, "userAgent"))
                .header("Content-Type", "application/json")
                .POST(serializedRequest)
                .build();
        try {
            ChromeHttpResponse<String> response = httpClient.send(request, ChromeBodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ChatGptAccountException(
                        "Codex backend returned HTTP " + response.statusCode()
                                + (response.body().isBlank() ? "" : ": " + response.body()),
                        response.statusCode(), null);
            }
            try {
                JsonNode body = objectMapper.readTree(response.body());
                if (body == null || !body.isObject()) {
                    throw new ChatGptAccountException(
                            "Codex backend response must be a JSON object", response.statusCode(), null);
                }
                return body;
            } catch (JsonProcessingException exception) {
                throw new ChatGptAccountException(
                        "Could not parse the Codex backend response", response.statusCode(), exception);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ChatGptAccountException("Interrupted while calling the Codex backend", -1, exception);
        } catch (IOException exception) {
            throw new ChatGptAccountException("Could not call the Codex backend", -1, exception);
        }
    }

    private URI modelsEndpoint(String clientVersion) {
        URI endpoint = relatedResponsesEndpoint(responsesEndpoint, "/models", MODELS_ENDPOINT);
        if (clientVersion == null) {
            return endpoint;
        }
        String separator = endpoint.getQuery() == null ? "?" : "&";
        return URI.create(endpoint + separator + "client_version=" + formValue(clientVersion));
    }

    private URI responsesCompactEndpoint() {
        String value = responsesEndpoint.toString();
        if (value.endsWith("/responses")) {
            return URI.create(value + "/compact");
        }
        return RESPONSES_COMPACT_ENDPOINT;
    }

    private static URI relatedResponsesEndpoint(URI endpoint, String replacementPath, URI defaultEndpoint) {
        String value = endpoint.toString();
        int responsesSuffix = value.lastIndexOf("/responses");
        if (responsesSuffix >= 0) {
            return URI.create(value.substring(0, responsesSuffix) + replacementPath);
        }
        return defaultEndpoint;
    }

    private static URI relatedUsageEndpoint(URI usageEndpoint, String replacementPath, URI defaultEndpoint) {
        String value = usageEndpoint.toString();
        int usageSuffix = value.lastIndexOf("/usage");
        if (usageSuffix >= 0) {
            return URI.create(value.substring(0, usageSuffix) + replacementPath);
        }
        return defaultEndpoint;
    }

    private static URI relatedAccountEndpoint(URI accountEndpoint, String replacementPath, URI defaultEndpoint) {
        String value = accountEndpoint.toString();
        int backendApi = value.indexOf("/backend-api/");
        if (backendApi >= 0) {
            return URI.create(value.substring(0, backendApi + "/backend-api".length()) + replacementPath);
        }
        return defaultEndpoint;
    }

    private URI subscriptionUri(String accountId) {
        String separator = subscriptionsEndpoint.getQuery() == null ? "?" : "&";
        return URI.create(subscriptionsEndpoint + separator + "account_id="
                + formValue(accountId));
    }

    private static String planType(JsonNode response, String accountId) {
        JsonNode account = response.path("accounts").path(accountId);
        return firstPresent(
                text(response, "plan_type"),
                text(account, "plan_type"),
                text(account.path("account"), "plan_type"),
                text(response.path("subscription"), "plan_type"),
                text(response.path("subscription").path("plan"), "id"),
                text(response.path("plan"), "id"));
    }

    private static Instant expiration(JsonNode response) {
        return firstInstant(
                response.get("active_until"),
                response.path("subscription").get("active_until"),
                response.get("access_until"),
                response.path("subscription").get("access_until"),
                response.get("current_period_end"),
                response.path("subscription").get("current_period_end"),
                response.get("expires_at"),
                response.path("subscription").get("expires_at"));
    }

    private static OpenAiQuotaWindow quotaWindow(JsonNode window) {
        if (!window.isObject()) {
            return null;
        }
        Double usedPercent = decimal(window.get("used_percent"));
        return new OpenAiQuotaWindow(
                usedPercent,
                usedPercent == null ? null : Math.max(0, 100 - usedPercent),
                wholeNumber(window.get("limit_window_seconds")),
                toInstant(window.get("reset_at")));
    }

    private static OpenAiQuotaWindow findWindow(long expectedSeconds, OpenAiQuotaWindow... windows) {
        for (OpenAiQuotaWindow window : windows) {
            if (window != null && Long.valueOf(expectedSeconds).equals(window.limitWindowSeconds())) {
                return window;
            }
        }
        return null;
    }

    private static Double decimal(JsonNode value) {
        if (value != null && value.isNumber()) {
            return value.doubleValue();
        }
        if (value != null && value.isTextual()) {
            try {
                return Double.parseDouble(value.textValue());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Long wholeNumber(JsonNode value) {
        if (value != null && value.canConvertToLong()) {
            return value.longValue();
        }
        return null;
    }

    private static Instant firstInstant(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            Instant value = toInstant(candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Instant toInstant(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            long epoch = value.longValue();
            return epoch > 100_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
        }
        if (value.isTextual()) {
            String text = value.textValue();
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException ignored) {
                try {
                    long epoch = Long.parseLong(text);
                    return epoch > 100_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
                } catch (NumberFormatException ignoredAgain) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field != null && field.isTextual() && !field.textValue().isBlank() ? field.textValue() : null;
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String formValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void persistToken(OpenAiTokenResponse updatedToken) throws IOException {
        writeJson(tokenFile, updatedToken);
    }

    private void persistAccount(OpenAiAccount enrichedAccount) throws IOException {
        writeJson(accountFile, enrichedAccount);
    }

    private void writeJson(File file, Object value) throws IOException {
        if (file == null) {
            return;
        }
        Path target = file.toPath().toAbsolutePath();
        Path directory = target.getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "openai-client-", ".json");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static <T> T readJson(File file, Class<T> type) throws IOException {
        return newObjectMapper().readValue(requireFile(file, "file"), type);
    }

    private static File requireFile(File file, String name) {
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException(name + " must refer to an existing file");
        }
        return file;
    }

    private static ObjectMapper newObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    static ChromeHttpTransport createHttpTransport(HttpProxyConfig proxyConfig) {
        ChromeHttpClient.Builder builder = ChromeHttpClient.newBuilder();
        if (proxyConfig != null) {
            builder.proxy(toChromeProxy(proxyConfig));
        }
        return builder.build();
    }

    static ChromeProxy toChromeProxy(HttpProxyConfig proxyConfig) {
        Objects.requireNonNull(proxyConfig, "proxyConfig");
        String host = proxyConfig.host();
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        URI proxyUri = URI.create("http://" + host + ":" + proxyConfig.port());
        return proxyConfig.hasCredentials()
                ? new ChromeProxy(proxyUri, proxyConfig.username(), proxyConfig.password())
                : new ChromeProxy(proxyUri);
    }

    /** Stops the background token-refresh task. */
    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            cancelScheduledRefresh();
            refreshScheduler.shutdownNow();
            httpClient.close();
        }
    }
}
