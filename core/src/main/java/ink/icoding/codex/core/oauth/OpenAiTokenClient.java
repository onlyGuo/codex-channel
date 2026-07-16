package ink.icoding.codex.core.oauth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ink.icoding.codex.http.ChromeBodyHandlers;
import ink.icoding.codex.http.ChromeHttpClient;
import ink.icoding.codex.http.ChromeHttpRequest;
import ink.icoding.codex.http.ChromeHttpResponse;
import ink.icoding.codex.http.ChromeHttpTransport;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Exchanges an OpenAI authorization code for OAuth tokens. */
public final class OpenAiTokenClient implements AutoCloseable {

    public static final URI TOKEN_ENDPOINT = URI.create("https://auth.openai.com/oauth/token");
    public static final URI REVOKE_ENDPOINT = URI.create("https://auth.openai.com/oauth/revoke");
    public static final URI ACCOUNTS_ENDPOINT = URI.create("https://auth.openai.com/api/accounts");

    private final ChromeHttpTransport httpClient;
    private final URI tokenEndpoint;
    private final ObjectMapper objectMapper;
    private final boolean ownsTransport;

    /** Creates a client that sends requests to the OpenAI token endpoint. */
    public OpenAiTokenClient() {
        this(ChromeHttpClient.newBuilder().cookiesEnabled(false).build(), TOKEN_ENDPOINT, new ObjectMapper(), true);
    }

    /** Creates a token client over a caller-managed Chrome HTTP transport. */
    public OpenAiTokenClient(ChromeHttpTransport httpClient) {
        this(httpClient, TOKEN_ENDPOINT, new ObjectMapper(), false);
    }

    OpenAiTokenClient(ChromeHttpTransport httpClient, URI tokenEndpoint, ObjectMapper objectMapper) {
        this(httpClient, tokenEndpoint, objectMapper, false);
    }

    private OpenAiTokenClient(
            ChromeHttpTransport httpClient, URI tokenEndpoint, ObjectMapper objectMapper, boolean ownsTransport) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.tokenEndpoint = Objects.requireNonNull(tokenEndpoint, "tokenEndpoint");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.ownsTransport = ownsTransport;
    }

    /**
     * Exchanges an authorization code using the redirect URI and verifier retained in a session.
     */
    public OpenAiTokenResponse exchangeAuthorizationCode(
            String authorizationCode, AuthorizationSession session) {
        Objects.requireNonNull(session, "session");
        return exchangeAuthorizationCode(authorizationCode, session.redirectUri(), session.codeVerifier());
    }

    /** Lists the OpenAI accounts available to the supplied OAuth access token. */
    public JsonNode fetchAccounts(String accessToken) {
        ChromeHttpRequest request = ChromeHttpRequest.newBuilder(relatedEndpoint("/api/accounts", ACCOUNTS_ENDPOINT))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + requireText(accessToken, "accessToken"))
                .GET()
                .build();
        try {
            ChromeHttpResponse<String> response = httpClient.send(request, ChromeBodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OpenAiTokenExchangeException(
                        "OpenAI accounts endpoint returned HTTP " + response.statusCode(),
                        response.statusCode(), null);
            }
            return objectMapper.readTree(response.body());
        } catch (JsonProcessingException exception) {
            throw new OpenAiTokenExchangeException("Could not parse the OpenAI accounts response", -1, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OpenAiTokenExchangeException("Interrupted while fetching OpenAI accounts", -1, exception);
        } catch (IOException exception) {
            throw new OpenAiTokenExchangeException("Could not fetch OpenAI accounts", -1, exception);
        }
    }

    /** Revokes a refresh token. */
    public void revokeRefreshToken(String refreshToken) {
        revokeToken(refreshToken, "refresh_token");
    }

    /** Revokes an OAuth token using the supplied token type hint. */
    public void revokeToken(String token, String tokenTypeHint) {
        String form = "token=" + formValue(requireText(token, "token"))
                + "&token_type_hint=" + formValue(requireText(tokenTypeHint, "tokenTypeHint"))
                + "&client_id=" + formValue(OpenAiAuthorizationUrlGenerator.CLIENT_ID);
        ChromeHttpRequest request = ChromeHttpRequest.newBuilder(relatedEndpoint("/oauth/revoke", REVOKE_ENDPOINT))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(form)
                .build();
        try {
            ChromeHttpResponse<Void> response = httpClient.send(request, ChromeBodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OpenAiTokenExchangeException(
                        "OpenAI revoke endpoint returned HTTP " + response.statusCode(),
                        response.statusCode(), null);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OpenAiTokenExchangeException("Interrupted while revoking the OpenAI token", -1, exception);
        } catch (IOException exception) {
            throw new OpenAiTokenExchangeException("Could not revoke the OpenAI token", -1, exception);
        }
    }

    private URI relatedEndpoint(String path, URI defaultEndpoint) {
        if (tokenEndpoint.getScheme() != null && tokenEndpoint.getAuthority() != null) {
            return URI.create(tokenEndpoint.getScheme() + "://" + tokenEndpoint.getAuthority() + path);
        }
        return defaultEndpoint;
    }

    /**
     * Exchanges an authorization code for access, refresh, and ID tokens.
     * The redirect URI must exactly match the value used to create the authorization URL.
     */
    public OpenAiTokenResponse exchangeAuthorizationCode(
            String authorizationCode, String redirectUri, String codeVerifier) {
        String form = "grant_type=authorization_code"
                + "&client_id=" + formValue(OpenAiAuthorizationUrlGenerator.CLIENT_ID)
                + "&code=" + formValue(requireText(authorizationCode, "authorizationCode"))
                + "&redirect_uri=" + formValue(effectiveRedirectUri(redirectUri))
                + "&code_verifier=" + formValue(requireText(codeVerifier, "codeVerifier"));
        ChromeHttpRequest request = ChromeHttpRequest.newBuilder(tokenEndpoint)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(form)
                .build();

        try {
            ChromeHttpResponse<String> response = httpClient.send(request, ChromeBodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OpenAiTokenExchangeException(
                        "OpenAI token endpoint returned HTTP " + response.statusCode(),
                        response.statusCode(), null);
            }
            try {
                return objectMapper.readValue(response.body(), OpenAiTokenResponse.class);
            } catch (JsonProcessingException exception) {
                throw new OpenAiTokenExchangeException(
                        "Could not parse the OpenAI token response", response.statusCode(), exception);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OpenAiTokenExchangeException("Interrupted while exchanging OpenAI authorization code", -1, exception);
        } catch (IOException exception) {
            throw new OpenAiTokenExchangeException("Could not exchange OpenAI authorization code", -1, exception);
        }
    }

    private static String effectiveRedirectUri(String redirectUri) {
        return redirectUri == null || redirectUri.isBlank()
                ? OpenAiAuthorizationUrlGenerator.DEFAULT_REDIRECT_URI
                : redirectUri;
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

    @Override
    public void close() {
        if (ownsTransport) {
            httpClient.close();
        }
    }
}
