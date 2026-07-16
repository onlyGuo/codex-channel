package ink.icoding.codex.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.icoding.codex.core.oauth.AuthorizationSession;
import ink.icoding.codex.core.oauth.OpenAiAuthorizationUrlGenerator;
import org.junit.jupiter.api.Test;

class AuthorizationExampleTest {

    @Test
    void usesCoreFromAnotherModule() {
        AuthorizationSession session = new OpenAiAuthorizationUrlGenerator("https://proxy.example/auth")
                .create("https://client.example/callback");

        assertEquals("https://client.example/callback", session.redirectUri());
        assertEquals("https://proxy.example/auth", session.proxyUrl());
        assertTrue(session.authorizationUrl().startsWith(OpenAiAuthorizationUrlGenerator.AUTHORIZATION_ENDPOINT));
    }
}
