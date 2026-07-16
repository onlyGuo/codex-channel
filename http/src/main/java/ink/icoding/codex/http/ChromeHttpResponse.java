package ink.icoding.codex.http;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Immutable response metadata and converted body. */
public record ChromeHttpResponse<T>(int statusCode, URI uri, Map<String, List<String>> headers, T body) {

    public ChromeHttpResponse {
        Map<String, List<String>> copied = new LinkedHashMap<>();
        headers.forEach((name, values) -> copied.put(name, List.copyOf(values)));
        headers = Collections.unmodifiableMap(copied);
    }

    public Optional<String> firstHeader(String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst();
    }

    /** Metadata made available to body handlers before temporary files are removed. */
    public record Metadata(int statusCode, URI uri, Map<String, List<String>> headers) {
    }
}
