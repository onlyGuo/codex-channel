package ink.icoding.codex.core.oauth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;

/** Decodes OpenAI ID token claims locally without making an HTTP request. */
public final class OpenAiIdTokenParser {

    private static final String AUTH_CLAIM = "https://api.openai.com/auth";

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OpenAiIdTokenParser() {
        this(new ObjectMapper(), Clock.systemUTC());
    }

    OpenAiIdTokenParser(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Decodes the ID token contained in a token endpoint response. */
    public OpenAiAccount parse(OpenAiTokenResponse tokenResponse) {
        Objects.requireNonNull(tokenResponse, "tokenResponse");
        return parse(tokenResponse.idToken());
    }

    /**
     * Decodes a three-part JWT, verifies that its {@code exp} claim is still in the future,
     * and maps the OpenAI account claims.
     *
     * <p>This method does not verify the JWT signature. Use it only with a token obtained
     * directly from the OpenAI token endpoint over HTTPS.</p>
     */
    public OpenAiAccount parse(String idToken) {
        String[] parts = requireJwtParts(idToken);
        JsonNode payload = decodePayload(parts[1]);
        verifyExpiration(payload);

        JsonNode auth = payload.path(AUTH_CLAIM);
        if (!auth.isObject()) {
            auth = objectMapper.createObjectNode();
        }
        return new OpenAiAccount(
                text(payload, "email"),
                text(auth, "chatgpt_account_id"),
                text(auth, "chatgpt_user_id"),
                text(auth, "chatgpt_plan_type"),
                selectOrganizationId(auth.path("organizations")),
                null);
    }

    private static String[] requireJwtParts(String idToken) {
        if (idToken == null || idToken.isBlank()) {
            throw new OpenAiIdTokenException("ID token must not be blank");
        }
        String[] parts = idToken.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new OpenAiIdTokenException("ID token must contain exactly three non-empty segments");
        }
        return parts;
    }

    private JsonNode decodePayload(String payloadPart) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(payloadPart);
            JsonNode payload = objectMapper.readTree(new String(decoded, StandardCharsets.UTF_8));
            if (payload == null || !payload.isObject()) {
                throw new OpenAiIdTokenException("ID token payload must be a JSON object");
            }
            return payload;
        } catch (IllegalArgumentException exception) {
            throw new OpenAiIdTokenException("ID token payload is not valid Base64URL", exception);
        } catch (JsonProcessingException exception) {
            throw new OpenAiIdTokenException("ID token payload is not valid JSON", exception);
        }
    }

    private void verifyExpiration(JsonNode payload) {
        JsonNode expiration = payload.get("exp");
        if (expiration == null || !expiration.canConvertToLong()) {
            throw new OpenAiIdTokenException("ID token payload must contain a numeric exp claim");
        }
        if (expiration.longValue() <= clock.instant().getEpochSecond()) {
            throw new OpenAiIdTokenException("ID token has expired");
        }
    }

    private static String selectOrganizationId(JsonNode organizations) {
        if (!organizations.isArray()) {
            return null;
        }
        String firstOrganizationId = null;
        for (JsonNode organization : organizations) {
            if (!organization.isObject()) {
                continue;
            }
            String organizationId = text(organization, "id");
            if (firstOrganizationId == null) {
                firstOrganizationId = organizationId;
            }
            JsonNode isDefault = organization.get("is_default");
            if (isDefault != null && isDefault.isBoolean() && isDefault.booleanValue()) {
                return organizationId;
            }
        }
        return firstOrganizationId;
    }

    private static String text(JsonNode object, String fieldName) {
        JsonNode field = object.get(fieldName);
        return field != null && field.isTextual() ? field.textValue() : null;
    }
}
