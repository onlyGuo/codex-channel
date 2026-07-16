package ink.icoding.codex.core.oauth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;

/** Generates OpenAI OAuth authorization URLs and their local PKCE session data. */
public final class OpenAiAuthorizationUrlGenerator {

    public static final String AUTHORIZATION_ENDPOINT = "https://auth.openai.com/oauth/authorize";
    public static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    public static final String DEFAULT_REDIRECT_URI = "http://localhost:1455/auth/callback";
    public static final String SCOPE = "openid profile email offline_access";

    private final SecureRandom secureRandom;
    private final Clock clock;
    private final String proxyUrl;

    /** Creates a generator without a project-specific proxy URL. */
    public OpenAiAuthorizationUrlGenerator() {
        this(new SecureRandom(), Clock.systemUTC(), "");
    }

    /**
     * Creates a generator that returns the supplied project proxy URL in every session.
     * This value is not sent to OpenAI.
     */
    public OpenAiAuthorizationUrlGenerator(String proxyUrl) {
        this(new SecureRandom(), Clock.systemUTC(), proxyUrl);
    }

    OpenAiAuthorizationUrlGenerator(SecureRandom secureRandom, Clock clock, String proxyUrl) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.proxyUrl = Objects.requireNonNullElse(proxyUrl, "");
    }

    /** Creates a session using {@link #DEFAULT_REDIRECT_URI}. */
    public AuthorizationSession create() {
        return create(null);
    }

    /**
     * Creates a complete OpenAI authorization request. A null or blank redirect URI
     * uses {@link #DEFAULT_REDIRECT_URI}.
     */
    public AuthorizationSession create(String redirectUri) {
        String effectiveRedirectUri = redirectUri == null || redirectUri.isBlank()
                ? DEFAULT_REDIRECT_URI
                : redirectUri;
        String sessionId = randomHex(32);
        String state = randomHex(32);
        String codeVerifier = randomHex(64);
        String codeChallenge = codeChallenge(codeVerifier);

        String authorizationUrl = AUTHORIZATION_ENDPOINT
                + "?response_type=code"
                + "&client_id=" + encode(CLIENT_ID)
                + "&redirect_uri=" + encode(effectiveRedirectUri)
                + "&scope=" + encode(SCOPE)
                + "&state=" + encode(state)
                + "&code_challenge=" + encode(codeChallenge)
                + "&code_challenge_method=S256"
                + "&id_token_add_organizations=true"
                + "&codex_cli_simplified_flow=true";

        return new AuthorizationSession(
                sessionId,
                state,
                codeVerifier,
                CLIENT_ID,
                effectiveRedirectUri,
                proxyUrl,
                clock.instant(),
                authorizationUrl);
    }

    private String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        secureRandom.nextBytes(bytes);
        StringBuilder value = new StringBuilder(byteCount * 2);
        for (byte current : bytes) {
            value.append(Character.forDigit((current >>> 4) & 0x0F, 16));
            value.append(Character.forDigit(current & 0x0F, 16));
        }
        return value.toString();
    }

    private static String codeChallenge(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available in the JDK", exception);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%7E", "~");
    }
}
