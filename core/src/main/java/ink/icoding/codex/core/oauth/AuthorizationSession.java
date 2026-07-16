package ink.icoding.codex.core.oauth;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Values created for one OpenAI authorization request.
 *
 * <p>The {@code sessionId} is local-only. Keep this object or persist the
 * verifier against that ID until the authorization callback is handled.</p>
 */
public record AuthorizationSession(
        String sessionId,
        String state,
        String codeVerifier,
        String clientId,
        String redirectUri,
        String proxyUrl,
        Instant createdAt,
        String authorizationUrl) {

    /**
     * Returns the session values using the field names expected by a JSON API.
     * The authorization URL is included in addition to the local session data.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("session_id", sessionId);
        values.put("state", state);
        values.put("code_verifier", codeVerifier);
        values.put("client_id", clientId);
        values.put("redirect_uri", redirectUri);
        values.put("proxy_url", proxyUrl);
        values.put("created_at", createdAt.toString());
        values.put("authorization_url", authorizationUrl);
        return Map.copyOf(values);
    }
}
