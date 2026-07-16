package ink.icoding.codex.http;

import java.io.IOException;
import java.nio.file.Path;

/** Converts a completed response body file to the caller's desired representation. */
@FunctionalInterface
public interface ChromeBodyHandler<T> {
    T apply(Path bodyFile, ChromeHttpResponse.Metadata metadata) throws IOException;
}
