package ink.icoding.codex.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/** Secure, version-pinned first-run installer for official curl-impersonate CLI releases. */
final class CurlImpersonateInstaller {

    static final String VERSION = "v1.5.6";
    static final String AUTO_DOWNLOAD_PROPERTY = "codex.http.auto-download";
    static final String AUTO_DOWNLOAD_ENVIRONMENT = "CURL_IMPERSONATE_AUTO_DOWNLOAD";
    static final String CACHE_PROPERTY = "codex.http.cache-dir";
    static final String CACHE_ENVIRONMENT = "CURL_IMPERSONATE_CACHE_DIR";
    static final String LIBC_PROPERTY = "codex.http.libc";
    private static final String RELEASE_BASE =
            "https://github.com/lexiforest/curl-impersonate/releases/download/" + VERSION + "/";
    private static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;
    private static final long MAX_ARCHIVE_CONTENT_BYTES = 128L * 1024 * 1024;

    private CurlImpersonateInstaller() {
    }

    static Path installCurrentPlatform() {
        if (!autoDownloadEnabled()) {
            throw new CurlImpersonateNotFoundException(
                    "curl-impersonate was not found and automatic download is disabled by "
                            + AUTO_DOWNLOAD_PROPERTY + " or " + AUTO_DOWNLOAD_ENVIRONMENT + ".");
        }
        CurlPlatform platform = CurlPlatform.current();
        Asset asset = assetFor(platform, effectiveLibc(platform));
        Path versionDirectory = cacheRoot().resolve("curl-impersonate").resolve(VERSION);
        Path installation = versionDirectory.resolve(asset.platformId());
        Path executable = installation.resolve("curl-impersonate"
                + (platform.operatingSystem() == CurlPlatform.OperatingSystem.WINDOWS ? ".exe" : ""));
        Path marker = installation.resolve(".complete");
        if (isComplete(executable, marker, asset.sha256())) {
            return executable;
        }

        try {
            Files.createDirectories(versionDirectory);
            Path lockPath = versionDirectory.resolve(".install.lock");
            try (FileChannel channel = FileChannel.open(
                    lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                if (isComplete(executable, marker, asset.sha256())) {
                    return executable;
                }
                return downloadAndInstall(asset, versionDirectory, installation, executable, marker);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CurlImpersonateNotFoundException(
                    "Interrupted while downloading curl-impersonate " + VERSION, exception);
        } catch (IOException exception) {
            throw new CurlImpersonateNotFoundException(
                    "Could not automatically install curl-impersonate " + VERSION + " for "
                            + asset.platformId() + ": " + exception.getMessage(), exception);
        }
    }

    static Asset assetFor(CurlPlatform platform, Libc libc) {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(libc, "libc");
        String name;
        String sha256;
        if (platform.operatingSystem() == CurlPlatform.OperatingSystem.MACOS) {
            if (platform.architecture() == CurlPlatform.Architecture.AARCH64) {
                name = "curl-impersonate-v1.5.6.arm64-macos.tar.gz";
                sha256 = "8960639189747306262fd26cfdc3f5ece52c300257c448a2171252c1060f3f04";
            } else {
                name = "curl-impersonate-v1.5.6.x86_64-macos.tar.gz";
                sha256 = "e652f640ec5865b70ee409d76769193bb8caf4b6d4b714727807f74428b46908";
            }
        } else if (platform.operatingSystem() == CurlPlatform.OperatingSystem.LINUX) {
            boolean musl = libc == Libc.MUSL;
            if (platform.architecture() == CurlPlatform.Architecture.AARCH64) {
                name = "curl-impersonate-v1.5.6.aarch64-linux-" + (musl ? "musl" : "gnu") + ".tar.gz";
                sha256 = musl
                        ? "09705dbc58b9df3fc522e59b17929cc5f45f1f76bcff72d92914066fce7b0986"
                        : "6766bc67fd3e8e2313875f32b36b5a3fab02beffe77e5f1cf7fc5da99731d403";
            } else {
                name = "curl-impersonate-v1.5.6.x86_64-linux-" + (musl ? "musl" : "gnu") + ".tar.gz";
                sha256 = musl
                        ? "186140c3567c1ace7d0cac4703eb9e47ce6984956f8cc623e404a094e788b602"
                        : "b60344f63b9ed8806f0e9f7fd357d9f6c9a82aca279ed1e9e257d544885dcbde";
            }
        } else {
            throw new CurlImpersonateNotFoundException(
                    "The official curl-impersonate " + VERSION
                            + " release does not publish a Windows CLI asset. "
                            + "Configure an executable explicitly with -D"
                            + CurlImpersonateResolver.EXECUTABLE_PROPERTY + "=C:\\path\\curl-impersonate.exe.");
        }
        return new Asset(name, sha256, platformId(platform, libc));
    }

    static void extract(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        long total = 0;
        try (InputStream fileInput = Files.newInputStream(archive);
                GzipCompressorInputStream gzip = new GzipCompressorInputStream(fileInput);
                TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (entry.isSymbolicLink() || entry.isLink() || entry.isCharacterDevice()
                        || entry.isBlockDevice() || entry.isFIFO()) {
                    throw new IOException("Unsupported archive entry: " + entry.getName());
                }
                if (entry.getSize() < 0 || entry.getSize() > MAX_ENTRY_BYTES) {
                    throw new IOException("Archive entry is too large: " + entry.getName());
                }
                total += entry.getSize();
                if (total > MAX_ARCHIVE_CONTENT_BYTES) {
                    throw new IOException("Archive expands beyond " + MAX_ARCHIVE_CONTENT_BYTES + " bytes");
                }
                Path target = destination.resolve(entry.getName()).normalize();
                if (!target.startsWith(destination)) {
                    throw new IOException("Archive entry escapes destination: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else if (entry.isFile()) {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(tar, target, StandardCopyOption.REPLACE_EXISTING);
                    if ((entry.getMode() & 0100) != 0) {
                        target.toFile().setExecutable(true, true);
                    }
                }
            }
        }
    }

    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static Path downloadAndInstall(
            Asset asset, Path versionDirectory, Path installation, Path executable, Path marker)
            throws IOException, InterruptedException {
        Path archive = Files.createTempFile(versionDirectory, "curl-impersonate-", ".tar.gz");
        Path temporary = versionDirectory.resolve(".extract-" + UUID.randomUUID());
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(20))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(RELEASE_BASE + asset.fileName()))
                    .timeout(Duration.ofMinutes(3))
                    .header("User-Agent", "codex-channel-http/" + VERSION)
                    .GET()
                    .build();
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(archive));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("GitHub release download returned HTTP " + response.statusCode());
            }
            String actual = sha256(archive);
            if (!asset.sha256().equals(actual)) {
                throw new IOException("SHA-256 mismatch for " + asset.fileName()
                        + ": expected " + asset.sha256() + " but received " + actual);
            }
            extract(archive, temporary);
            Path extractedExecutable = temporary.resolve("curl-impersonate");
            if (!Files.isRegularFile(extractedExecutable)) {
                throw new IOException("Official archive does not contain curl-impersonate");
            }
            extractedExecutable.toFile().setExecutable(true, true);
            Files.writeString(
                    temporary.resolve(".complete"),
                    asset.sha256() + "\n" + sha256(extractedExecutable) + "\n",
                    StandardCharsets.US_ASCII);
            deleteRecursively(installation);
            moveDirectory(temporary, installation);
            if (!isComplete(executable, marker, asset.sha256())) {
                throw new IOException("Installed curl-impersonate executable is not usable: " + executable);
            }
            return executable;
        } finally {
            Files.deleteIfExists(archive);
            deleteRecursively(temporary);
        }
    }

    private static boolean isComplete(Path executable, Path marker, String expectedSha256) {
        try {
            if (!Files.isRegularFile(executable) || !Files.isExecutable(executable) || !Files.isRegularFile(marker)) {
                return false;
            }
            var values = Files.readAllLines(marker, StandardCharsets.US_ASCII);
            return values.size() >= 2
                    && expectedSha256.equals(values.get(0).trim())
                    && sha256(executable).equals(values.get(1).trim());
        } catch (IOException exception) {
            return false;
        }
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path candidate : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    private static boolean autoDownloadEnabled() {
        String configured = System.getProperty(AUTO_DOWNLOAD_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(AUTO_DOWNLOAD_ENVIRONMENT);
        }
        return configured == null || configured.isBlank() || Boolean.parseBoolean(configured);
    }

    private static Path cacheRoot() {
        String configured = System.getProperty(CACHE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(CACHE_ENVIRONMENT);
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".cache", "codex-channel");
    }

    private static Libc effectiveLibc(CurlPlatform platform) {
        if (platform.operatingSystem() != CurlPlatform.OperatingSystem.LINUX) {
            return Libc.NONE;
        }
        String configured = System.getProperty(LIBC_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            try {
                return Libc.valueOf(configured.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new CurlImpersonateNotFoundException(
                        LIBC_PROPERTY + " must be 'gnu' or 'musl'", exception);
            }
        }
        return Files.isRegularFile(Path.of("/etc/alpine-release")) ? Libc.MUSL : Libc.GNU;
    }

    private static String platformId(CurlPlatform platform, Libc libc) {
        String os = platform.operatingSystem().name().toLowerCase(Locale.ROOT);
        String arch = platform.architecture().name().toLowerCase(Locale.ROOT);
        return os + "-" + arch + (platform.operatingSystem() == CurlPlatform.OperatingSystem.LINUX
                ? "-" + libc.name().toLowerCase(Locale.ROOT) : "");
    }

    enum Libc { NONE, GNU, MUSL }

    record Asset(String fileName, String sha256, String platformId) {
    }
}
