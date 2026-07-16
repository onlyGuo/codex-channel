package ink.icoding.codex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiServerTest {

    @TempDir Path temporaryDirectory;

    @Test
    void exposesHealthProtectsApiAndStopsThroughAdminEndpoint() throws Exception {
        AccountStore store = new AccountStore(temporaryDirectory.resolve("state"));
        ApiServer server = new ApiServer(store, "127.0.0.1", 0, "api-secret", "admin-secret");
        server.start();
        ServiceState state = store.serviceState();
        URI base = URI.create("http://127.0.0.1:" + state.port());
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

        HttpResponse<String> health = client.send(
                HttpRequest.newBuilder(base.resolve("/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("\"status\":\"ok\""));

        HttpResponse<String> unauthorized = client.send(
                HttpRequest.newBuilder(base.resolve("/v1/models")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauthorized.statusCode());

        HttpResponse<String> stopped = client.send(
                HttpRequest.newBuilder(base.resolve("/_admin/stop"))
                        .header("X-Admin-Token", "admin-secret")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, stopped.statusCode());
        server.await();
        assertFalse(java.nio.file.Files.exists(store.home().resolve("service.json")));
    }
}
