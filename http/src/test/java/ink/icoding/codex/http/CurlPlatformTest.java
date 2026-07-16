package ink.icoding.codex.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CurlPlatformTest {

    @Test
    void normalizesOperatingSystemAndArchitecture() {
        assertEquals(
                new CurlPlatform(CurlPlatform.OperatingSystem.MACOS, CurlPlatform.Architecture.AARCH64),
                CurlPlatform.from("Mac OS X", "arm64"));
        assertEquals(
                new CurlPlatform(CurlPlatform.OperatingSystem.LINUX, CurlPlatform.Architecture.X86_64),
                CurlPlatform.from("Linux", "amd64"));
        assertEquals(
                new CurlPlatform(CurlPlatform.OperatingSystem.WINDOWS, CurlPlatform.Architecture.X86_64),
                CurlPlatform.from("Windows 11", "x86_64"));
    }

    @Test
    void rejectsUnknownPlatforms() {
        assertThrows(UnsupportedOperationException.class, () -> CurlPlatform.from("Plan 9", "amd64"));
        assertThrows(UnsupportedOperationException.class, () -> CurlPlatform.from("Linux", "riscv64"));
    }
}
