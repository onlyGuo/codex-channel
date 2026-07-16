package ink.icoding.codex.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChromeHttpRequestTest {

    @Test
    void buildsArbitraryMethodsBodiesAndRepeatedHeaders() {
        ChromeHttpRequest request = ChromeHttpRequest.newBuilder(URI.create("https://example.com/items"))
                .header("Accept", "application/json")
                .header("Accept", "text/event-stream")
                .method("propfind", "payload", StandardCharsets.UTF_8)
                .followRedirects(false)
                .build();

        assertEquals("PROPFIND", request.method());
        assertEquals(2, request.headers().get("Accept").size());
        assertEquals(false, request.followRedirects());
    }

    @Test
    void rejectsHeaderInjectionAndNonHttpUris() {
        assertThrows(IllegalArgumentException.class,
                () -> ChromeHttpRequest.newBuilder("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> ChromeHttpRequest.newBuilder("https://example.com")
                        .header("X-Test", "ok\r\nInjected: yes"));
    }

    @Test
    void representsAnExplicitlyFramedEmptyBody() {
        ChromeHttpRequest request = ChromeHttpRequest.newBuilder("https://example.com/settings")
                .methodWithEmptyBody("PATCH")
                .build();

        assertEquals("PATCH", request.method());
        assertEquals(List.of("0"), request.headers().get("Content-Length"));
        assertEquals(true, request.body().isEmpty());
    }
}
