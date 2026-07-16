package ink.icoding.codex.core.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class OpenAiAuthorizationUrlGeneratorTest {

    @Test
    void createsTheExpectedAuthorizationRequest() throws Exception {
        Instant createdAt = Instant.parse("2026-07-14T00:00:00Z");
        OpenAiAuthorizationUrlGenerator generator = new OpenAiAuthorizationUrlGenerator(
                new IncrementingSecureRandom(), Clock.fixed(createdAt, ZoneOffset.UTC), "https://proxy.example/auth");

        AuthorizationSession session = generator.create("https://client.example/callback?source=example");
        Map<String, String> query = queryParameters(session.authorizationUrl());

        assertEquals(64, session.sessionId().length());
        assertTrue(session.sessionId().matches("[0-9a-f]{64}"));
        assertEquals(64, session.state().length());
        assertTrue(session.state().matches("[0-9a-f]{64}"));
        assertEquals(128, session.codeVerifier().length());
        assertTrue(session.codeVerifier().matches("[0-9a-f]{128}"));
        assertEquals(OpenAiAuthorizationUrlGenerator.AUTHORIZATION_ENDPOINT,
                new URI(session.authorizationUrl()).getScheme() + "://"
                        + new URI(session.authorizationUrl()).getAuthority() + new URI(session.authorizationUrl()).getPath());
        assertEquals("code", query.get("response_type"));
        assertEquals(OpenAiAuthorizationUrlGenerator.CLIENT_ID, query.get("client_id"));
        assertEquals("https://client.example/callback?source=example", query.get("redirect_uri"));
        assertEquals(OpenAiAuthorizationUrlGenerator.SCOPE, query.get("scope"));
        assertEquals(session.state(), query.get("state"));
        assertEquals(expectedChallenge(session.codeVerifier()), query.get("code_challenge"));
        assertEquals("S256", query.get("code_challenge_method"));
        assertEquals("true", query.get("id_token_add_organizations"));
        assertEquals("true", query.get("codex_cli_simplified_flow"));
        assertEquals("https://proxy.example/auth", session.proxyUrl());
        assertEquals(createdAt, session.createdAt());
        assertEquals(session.sessionId(), session.toMap().get("session_id"));
        assertEquals(session.authorizationUrl(), session.toMap().get("authorization_url"));
    }

    @Test
    void usesTheDefaultRedirectUriForMissingValues() {
        OpenAiAuthorizationUrlGenerator generator = new OpenAiAuthorizationUrlGenerator();

        AuthorizationSession session = generator.create("  ");

        assertEquals(OpenAiAuthorizationUrlGenerator.DEFAULT_REDIRECT_URI, session.redirectUri());
        assertFalse(session.state().equals(generator.create().state()));
    }

    private static Map<String, String> queryParameters(String authorizationUrl) throws Exception {
        return java.util.Arrays.stream(new URI(authorizationUrl).getRawQuery().split("&"))
                .map(part -> part.split("=", 2))
                .collect(Collectors.toMap(
                        part -> URLDecoder.decode(part[0], StandardCharsets.UTF_8),
                        part -> URLDecoder.decode(part[1], StandardCharsets.UTF_8)));
    }

    private static String expectedChallenge(String codeVerifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static final class IncrementingSecureRandom extends SecureRandom {
        private int next;

        @Override
        public void nextBytes(byte[] bytes) {
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) next++;
            }
        }
    }
}
