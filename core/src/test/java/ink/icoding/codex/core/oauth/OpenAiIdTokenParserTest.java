package ink.icoding.codex.core.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class OpenAiIdTokenParserTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void mapsClaimsAndPrefersTheDefaultOrganization() {
        OpenAiIdTokenParser parser = new OpenAiIdTokenParser(new ObjectMapper(), CLOCK);
        String payload = """
                {"email":"user@example.com","exp":1784073601,
                "https://api.openai.com/auth":{"chatgpt_account_id":"account-1",
                "chatgpt_user_id":"user-1","chatgpt_plan_type":"plus",
                "organizations":[{"id":"org-first","is_default":false},{"id":"org-default","is_default":true}]}}""";

        OpenAiAccount account = parser.parse(jwt(payload));

        assertEquals("user@example.com", account.email());
        assertEquals("account-1", account.chatgptAccountId());
        assertEquals("user-1", account.chatgptUserId());
        assertEquals("plus", account.planType());
        assertEquals("org-default", account.organizationId());
    }

    @Test
    void fallsBackToTheFirstOrganizationWhenNoDefaultExists() {
        OpenAiIdTokenParser parser = new OpenAiIdTokenParser(new ObjectMapper(), CLOCK);
        String payload = """
                {"email":"user@example.com","exp":1784073601,
                "https://api.openai.com/auth":{"organizations":[{"id":"org-first"},{"id":"org-second"}]}}""";

        assertEquals("org-first", parser.parse(jwt(payload)).organizationId());
    }

    @Test
    void rejectsMalformedAndExpiredTokens() {
        OpenAiIdTokenParser parser = new OpenAiIdTokenParser(new ObjectMapper(), CLOCK);

        assertThrows(OpenAiIdTokenException.class, () -> parser.parse("one.two"));
        assertThrows(OpenAiIdTokenException.class, () -> parser.parse(jwt("{\"exp\":0}")));
    }

    private static String jwt(String payload) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + ".signature";
    }
}
