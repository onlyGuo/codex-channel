package ink.icoding.codex.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Repeatable request body that can be consumed by a transport implementation. */
public interface ChromeRequestBody {

    InputStream openStream() throws IOException;

    long contentLength() throws IOException;

    default Path materialize(Path directory) throws IOException {
        Path path = directory.resolve("request-body.bin");
        try (InputStream input = openStream()) {
            Files.copy(input, path);
        }
        return path;
    }
}
