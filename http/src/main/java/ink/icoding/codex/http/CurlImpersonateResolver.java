package ink.icoding.codex.http;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Resolves a local executable or provisions the pinned official release on first use. */
public final class CurlImpersonateResolver {

    public static final String EXECUTABLE_PROPERTY = "codex.http.curl-impersonate";
    public static final String EXECUTABLE_ENVIRONMENT = "CURL_IMPERSONATE_BIN";
    public static final String HOME_ENVIRONMENT = "CURL_IMPERSONATE_HOME";

    private CurlImpersonateResolver() {
    }

    public static Path requireExecutable() {
        return findExecutable().orElseGet(CurlImpersonateInstaller::installCurrentPlatform);
    }

    public static Optional<Path> findExecutable() {
        Set<Path> candidates = new LinkedHashSet<>();
        addConfigured(candidates, System.getProperty(EXECUTABLE_PROPERTY));
        addConfigured(candidates, System.getenv(EXECUTABLE_ENVIRONMENT));
        Optional<Path> configured = firstExecutable(candidates);
        if (configured.isPresent()) {
            return configured;
        }

        CurlPlatform platform = CurlPlatform.current();
        String suffix = platform.operatingSystem() == CurlPlatform.OperatingSystem.WINDOWS
                ? ".exe" : "";
        List<String> names = List.of(
                "curl-impersonate-chrome" + suffix,
                "curl_chrome" + suffix,
                "curl_chrome146" + suffix,
                "curl_chrome145" + suffix,
                "curl_chrome142" + suffix,
                "curl_chrome136" + suffix,
                "curl_chrome133a" + suffix,
                "curl_chrome131" + suffix,
                "curl_chrome124" + suffix,
                "curl_chrome120" + suffix);
        String home = System.getenv(HOME_ENVIRONMENT);
        if (home != null && !home.isBlank()) {
            for (String name : names) {
                candidates.add(Path.of(home, name));
                candidates.add(Path.of(home, "bin", name));
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!directory.isBlank()) {
                    for (String name : names) {
                        candidates.add(Path.of(directory, name));
                    }
                }
            }
        }
        return firstExecutable(candidates);
    }

    private static Optional<Path> firstExecutable(Set<Path> candidates) {
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized) && Files.isExecutable(normalized)) {
                return Optional.of(normalized);
            }
        }
        return Optional.empty();
    }

    static List<Path> candidatesForTest(String configured, String environment, String path, CurlPlatform platform) {
        Set<Path> candidates = new LinkedHashSet<>();
        addConfigured(candidates, configured);
        addConfigured(candidates, environment);
        String suffix = platform.operatingSystem() == CurlPlatform.OperatingSystem.WINDOWS ? ".exe" : "";
        if (path != null) {
            for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                candidates.add(Path.of(directory, "curl-impersonate-chrome" + suffix));
            }
        }
        return new ArrayList<>(candidates);
    }

    private static void addConfigured(Set<Path> candidates, String value) {
        if (value != null && !value.isBlank()) {
            candidates.add(Path.of(value.trim()));
        }
    }
}
