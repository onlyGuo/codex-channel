package ink.icoding.codex.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChromeHttpClientTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void sendsRequestsAndStreamsSseThroughConfiguredExecutable() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
        Path executable = fakeCurl();

        try (ChromeHttpClient client = ChromeHttpClient.newBuilder()
                .executable(executable)
                .cookiesEnabled(false)
                .build()) {
            ChromeHttpResponse<String> response = client.send(
                    ChromeHttpRequest.newBuilder("https://example.com/resource")
                            .POST("request")
                            .header("Content-Type", "text/plain")
                            .build());

            assertEquals(201, response.statusCode());
            assertEquals("response-body", response.body());
            assertEquals(List.of("one", "two"), response.headers().get("X-Test"));

            List<SseEvent> events = new ArrayList<>();
            client.streamSse(
                    ChromeHttpRequest.newBuilder("https://example.com/events")
                            .header("Accept", "text/event-stream")
                            .build(),
                    events::add);
            assertEquals(List.of(new SseEvent("update", "hello", "7", null)), events);

            assertTrue(client.capabilities().httpChromeTls());
            assertTrue(client.capabilities().sseChromeTls());
            assertFalse(client.capabilities().webSocketChromeTls());
            assertTrue(client.capabilities().webSocketAvailable());
        }
    }

    @Test
    void autoModeDoesNotPassImpersonateOptionToVersionedWrapper() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
        Path executable = temporaryDirectory.resolve("curl_chrome120");
        Files.writeString(executable, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(executable, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));

        try (ChromeHttpClient client = ChromeHttpClient.newBuilder()
                .executable(executable)
                .profile(ChromeProfile.CHROME_120)
                .cookiesEnabled(false)
                .build()) {
            List<String> command = client.commandFor(
                    ChromeHttpRequest.newBuilder("https://example.com").build(), temporaryDirectory);
            assertFalse(command.contains("--impersonate"));
        }
    }

    private Path fakeCurl() throws Exception {
        Path executable = temporaryDirectory.resolve("fake-curl");
        String script = """
                #!/bin/sh
                headers=''
                output=''
                while [ "$#" -gt 0 ]; do
                  case "$1" in
                    --dump-header) headers="$2"; shift 2 ;;
                    --output) output="$2"; shift 2 ;;
                    *) shift ;;
                  esac
                done
                printf 'HTTP/2 201 Created\r\nX-Test: one\r\nX-Test: two\r\nContent-Type: text/plain\r\n\r\n' > "$headers"
                if [ "$output" = "-" ]; then
                  printf 'id: 7\nevent: update\ndata: hello\n\n'
                else
                  printf 'response-body' > "$output"
                fi
                """;
        Files.writeString(executable, script, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(executable, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        return executable;
    }
}
