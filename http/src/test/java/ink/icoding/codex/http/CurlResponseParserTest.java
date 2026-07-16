package ink.icoding.codex.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurlResponseParserTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void selectsFinalResponseAndPreservesRepeatedHeaders() throws Exception {
        Path headers = temporaryDirectory.resolve("headers.txt");
        Files.writeString(headers, "HTTP/1.1 200 Connection established\r\n\r\n"
                + "HTTP/2 302 Found\r\nLocation: /final\r\n\r\n"
                + "HTTP/2 200 OK\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2\r\nX-Test: yes\r\n\r\n",
                StandardCharsets.ISO_8859_1);

        ChromeHttpResponse.Metadata result = CurlResponseParser.parse(
                headers, URI.create("https://example.com/final"));

        assertEquals(200, result.statusCode());
        assertEquals(2, result.headers().get("Set-Cookie").size());
        assertEquals("yes", result.headers().get("X-Test").get(0));
    }
}
