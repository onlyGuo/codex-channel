package ink.icoding.codex.core.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class OpenAiTokenClientTest {

    @Test
    void postsTheRequiredFormAndParsesTokens() throws Exception {
        AtomicReference<CapturedRequest> requestReference = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/oauth/token", exchange -> {
            requestReference.set(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    formParameters(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))));
            byte[] response = ("{\"access_token\":\"access\",\"token_type\":\"Bearer\",\"expires_in\":3600,"
                    + "\"scope\":\"openid profile\",\"id_token\":\"id\",\"earliest_refresh_at\":123,"
                    + "\"refresh_token\":\"refresh\",\"oai_is\":\"session\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://localhost:" + server.getAddress().getPort() + "/oauth/token");
            OpenAiTokenClient client = new OpenAiTokenClient(new JdkTestHttpTransport(), endpoint, new ObjectMapper());

            OpenAiTokenResponse tokens = client.exchangeAuthorizationCode(
                    "authorization code", "https://client.example/callback?flow=oauth", "pkce verifier");

            assertEquals("access", tokens.accessToken());
            assertEquals("refresh", tokens.refreshToken());
            assertEquals("id", tokens.idToken());
            assertEquals(3600, tokens.expiresIn());
            CapturedRequest request = requestReference.get();
            assertEquals("POST", request.method());
            assertEquals("application/x-www-form-urlencoded", request.contentType());
            assertEquals(Map.of(
                    "grant_type", "authorization_code",
                    "client_id", OpenAiAuthorizationUrlGenerator.CLIENT_ID,
                    "code", "authorization code",
                    "redirect_uri", "https://client.example/callback?flow=oauth",
                    "code_verifier", "pkce verifier"), request.form());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsAnUnsuccessfulTokenResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/oauth/token", exchange -> {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://localhost:" + server.getAddress().getPort() + "/oauth/token");
            OpenAiTokenClient client = new OpenAiTokenClient(new JdkTestHttpTransport(), endpoint, new ObjectMapper());

            OpenAiTokenExchangeException exception = assertThrows(OpenAiTokenExchangeException.class,
                    () -> client.exchangeAuthorizationCode("code", "https://client.example/callback", "verifier"));

            assertEquals(400, exception.statusCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listsAccountsAndRevokesTokens() throws Exception {
        AtomicReference<Map<String, String>> revokeForm = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/accounts", exchange -> {
            assertEquals("Bearer access-token", exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"accounts\":[{\"id\":\"account-1\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/oauth/revoke", exchange -> {
            revokeForm.set(formParameters(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://localhost:" + server.getAddress().getPort() + "/oauth/token");
            OpenAiTokenClient client = new OpenAiTokenClient(new JdkTestHttpTransport(), endpoint, new ObjectMapper());

            JsonNode accounts = client.fetchAccounts("access-token");
            client.revokeRefreshToken("refresh-token");

            assertEquals("account-1", accounts.path("accounts").get(0).path("id").asText());
            assertEquals(Map.of(
                    "token", "refresh-token",
                    "token_type_hint", "refresh_token",
                    "client_id", OpenAiAuthorizationUrlGenerator.CLIENT_ID), revokeForm.get());
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, String> formParameters(String body) {
        return Arrays.stream(body.split("&"))
                .map(parameter -> parameter.split("=", 2))
                .collect(Collectors.toMap(
                        parameter -> URLDecoder.decode(parameter[0], StandardCharsets.UTF_8),
                        parameter -> URLDecoder.decode(parameter[1], StandardCharsets.UTF_8)));
    }

    private record CapturedRequest(String method, String contentType, Map<String, String> form) {
    }
}
