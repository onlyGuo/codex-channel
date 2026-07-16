package ink.icoding.codex.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Standard response body handlers. */
public final class ChromeBodyHandlers {

    private ChromeBodyHandlers() {
    }

    public static ChromeBodyHandler<byte[]> ofByteArray() {
        return (path, metadata) -> Files.readAllBytes(path);
    }

    public static ChromeBodyHandler<String> ofString() {
        return ofString(StandardCharsets.UTF_8);
    }

    public static ChromeBodyHandler<String> ofString(Charset charset) {
        Objects.requireNonNull(charset, "charset");
        return (path, metadata) -> Files.readString(path, charset);
    }

    public static ChromeBodyHandler<Void> discarding() {
        return (path, metadata) -> null;
    }

    public static ChromeBodyHandler<Path> ofFile(Path destination) {
        Path target = Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        return (path, metadata) -> {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        };
    }

    public static <T> ChromeBodyHandler<T> ofJson(Class<T> type) {
        return ofJson(new ObjectMapper(), type);
    }

    public static <T> ChromeBodyHandler<T> ofJson(ObjectMapper objectMapper, Class<T> type) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(type, "type");
        return (path, metadata) -> objectMapper.readValue(path.toFile(), type);
    }
}
