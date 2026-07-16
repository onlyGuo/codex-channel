package ink.icoding.codex.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurlImpersonateInstallerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void mapsSupportedPlatformsToPinnedAssets() {
        CurlImpersonateInstaller.Asset macArm = CurlImpersonateInstaller.assetFor(
                new CurlPlatform(CurlPlatform.OperatingSystem.MACOS, CurlPlatform.Architecture.AARCH64),
                CurlImpersonateInstaller.Libc.NONE);
        CurlImpersonateInstaller.Asset linuxX64Musl = CurlImpersonateInstaller.assetFor(
                new CurlPlatform(CurlPlatform.OperatingSystem.LINUX, CurlPlatform.Architecture.X86_64),
                CurlImpersonateInstaller.Libc.MUSL);

        assertEquals("curl-impersonate-v1.5.6.arm64-macos.tar.gz", macArm.fileName());
        assertEquals("8960639189747306262fd26cfdc3f5ece52c300257c448a2171252c1060f3f04",
                macArm.sha256());
        assertEquals("curl-impersonate-v1.5.6.x86_64-linux-musl.tar.gz", linuxX64Musl.fileName());
    }

    @Test
    void securelyExtractsRegularExecutableFiles() throws Exception {
        Path archive = archive("curl-impersonate", "binary");
        Path destination = temporaryDirectory.resolve("output");

        CurlImpersonateInstaller.extract(archive, destination);

        assertEquals("binary", Files.readString(destination.resolve("curl-impersonate")));
        assertTrue(Files.isExecutable(destination.resolve("curl-impersonate")));
        Path digestInput = temporaryDirectory.resolve("digest.txt");
        Files.writeString(digestInput, "abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CurlImpersonateInstaller.sha256(digestInput));
    }

    @Test
    void rejectsArchivePathTraversal() throws Exception {
        Path archive = archive("../outside", "bad");
        assertThrows(java.io.IOException.class,
                () -> CurlImpersonateInstaller.extract(archive, temporaryDirectory.resolve("safe")));
        assertTrue(Files.notExists(temporaryDirectory.resolve("outside")));
    }

    @Test
    void downloadsOfficialBinaryWhenIntegrationTestIsEnabled() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("codex.http.integration-download"));
        System.setProperty(CurlImpersonateInstaller.CACHE_PROPERTY,
                temporaryDirectory.resolve("cache").toString());
        Path executable = CurlImpersonateInstaller.installCurrentPlatform();
        Process process = new ProcessBuilder(executable.toString(), "--version")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        assertTrue(output.toLowerCase().contains("curl"), output);

        try (ChromeHttpClient client = ChromeHttpClient.newBuilder()
                .executable(executable)
                .cookiesEnabled(false)
                .build()) {
            ChromeHttpResponse<Void> response = client.send(
                    ChromeHttpRequest.newBuilder("https://example.com/").build(),
                    ChromeBodyHandlers.discarding());
            assertEquals(200, response.statusCode());
        }
    }

    private Path archive(String name, String content) throws Exception {
        Path archive = temporaryDirectory.resolve("archive-" + Math.abs(name.hashCode()) + ".tar.gz");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (var file = Files.newOutputStream(archive);
                var gzip = new GzipCompressorOutputStream(file);
                var tar = new TarArchiveOutputStream(gzip)) {
            TarArchiveEntry entry = new TarArchiveEntry(name);
            entry.setMode(0755);
            entry.setSize(bytes.length);
            tar.putArchiveEntry(entry);
            tar.write(bytes);
            tar.closeArchiveEntry();
            tar.finish();
        }
        return archive;
    }
}
