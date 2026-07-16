package ink.icoding.codex.http;

import java.util.Objects;

/** A curl-impersonate browser profile passed to {@code --impersonate}. */
public record ChromeProfile(String name) {

    /** Stable default supported by both older wrappers and the pinned native release. */
    public static final ChromeProfile CHROME = new ChromeProfile("chrome136");
    public static final ChromeProfile CHROME_120 = new ChromeProfile("chrome120");
    public static final ChromeProfile CHROME_124 = new ChromeProfile("chrome124");
    public static final ChromeProfile CHROME_131 = new ChromeProfile("chrome131");
    public static final ChromeProfile CHROME_133 = new ChromeProfile("chrome133a");
    public static final ChromeProfile CHROME_136 = new ChromeProfile("chrome136");
    public static final ChromeProfile CHROME_142 = new ChromeProfile("chrome142");
    public static final ChromeProfile CHROME_145 = new ChromeProfile("chrome145");
    public static final ChromeProfile CHROME_146 = new ChromeProfile("chrome146");

    public ChromeProfile {
        name = Objects.requireNonNull(name, "name").trim();
        if (name.isEmpty() || !name.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Chrome profile must contain only letters, digits, '.', '_' or '-'");
        }
    }
}
