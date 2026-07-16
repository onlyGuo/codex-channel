package ink.icoding.codex.http;

import java.util.List;
import java.util.Locale;

/** Normalized operating-system and CPU pair used when locating native curl binaries. */
public record CurlPlatform(OperatingSystem operatingSystem, Architecture architecture) {

    public enum OperatingSystem { LINUX, MACOS, WINDOWS }

    public enum Architecture { X86_64, AARCH64 }

    public static CurlPlatform current() {
        return from(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    static CurlPlatform from(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        OperatingSystem operatingSystem;
        if (os.contains("win")) {
            operatingSystem = OperatingSystem.WINDOWS;
        } else if (os.contains("mac") || os.contains("darwin")) {
            operatingSystem = OperatingSystem.MACOS;
        } else if (os.contains("linux")) {
            operatingSystem = OperatingSystem.LINUX;
        } else {
            throw new UnsupportedOperationException("Unsupported operating system: " + osName);
        }

        String arch = osArch.toLowerCase(Locale.ROOT);
        Architecture architecture;
        if (List.of("amd64", "x86_64", "x64").contains(arch)) {
            architecture = Architecture.X86_64;
        } else if (List.of("aarch64", "arm64").contains(arch)) {
            architecture = Architecture.AARCH64;
        } else {
            throw new UnsupportedOperationException("Unsupported CPU architecture: " + osArch);
        }
        return new CurlPlatform(operatingSystem, architecture);
    }

    String resourceDirectory() {
        return operatingSystem.name().toLowerCase(Locale.ROOT) + "-"
                + architecture.name().toLowerCase(Locale.ROOT);
    }
}
