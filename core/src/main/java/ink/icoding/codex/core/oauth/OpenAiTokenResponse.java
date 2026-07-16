package ink.icoding.codex.core.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents the response from the OpenAI token endpoint, containing access, refresh, and ID tokens along with their metadata.
 * @param accessToken
 *      授权令牌
 * @param refreshToken
 *      用于刷新授权令牌的令牌
 * @param idToken
 *      用于身份验证的JWT ID令牌
 * @param expiresIn
 *      授权令牌的有效期（以秒为单位）
 * @param earliestRefreshAt
 *      授权令牌的最早刷新时间（以秒为单位）
 * @param scope
 *      授权令牌的作用域
 * @param tokenType
 *      授权令牌的类型（通常为"Bearer"）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("id_token") String idToken,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("earliest_refresh_at") long earliestRefreshAt,
        @JsonProperty("scope") String scope,
        @JsonProperty("token_type") String tokenType){
}
