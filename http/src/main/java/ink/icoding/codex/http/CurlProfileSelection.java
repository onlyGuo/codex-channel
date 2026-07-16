package ink.icoding.codex.http;

/** How a Chrome profile is selected for a curl-impersonate distribution. */
public enum CurlProfileSelection {
    /** Detect versioned {@code curl_chrome*} wrappers; use an argument for generic executables. */
    AUTO,
    /** Pass {@code --impersonate <profile>}. */
    ARGUMENT,
    /** Set {@code CURL_IMPERSONATE} and {@code CURL_IMPERSONATE_HEADERS}. */
    ENVIRONMENT,
    /** Let a versioned wrapper select its built-in profile. */
    WRAPPER
}
