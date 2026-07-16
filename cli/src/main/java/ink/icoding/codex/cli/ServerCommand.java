package ink.icoding.codex.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "server", description = "Run and control the HTTP service", subcommands = {
        ServerCommand.Start.class,
        ServerCommand.Run.class,
        ServerCommand.Stop.class,
        ServerCommand.Status.class,
        ServerCommand.Logs.class
})
final class ServerCommand implements Runnable {
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(5);

    @ParentCommand CodexChannelCli root;
    @Override public void run() { new CommandLine(this).usage(System.out); }

    @Command(name = "start", description = "Start the HTTP service")
    static final class Start implements Callable<Integer> {
        @ParentCommand ServerCommand parent;
        @Option(names = "--host", defaultValue = "127.0.0.1") String host;
        @Option(names = "--port", defaultValue = "8787") int port;
        @Option(names = "--api-key", description = "Bearer token required by API endpoints") String apiKey;
        @Option(names = "--foreground", description = "Keep the service attached to this terminal") boolean foreground;

        @Override public Integer call() throws Exception {
            validateAddress(host, port, apiKey);
            AccountStore store = parent.root.store();
            ServiceState existing = liveState(store);
            if (existing != null) {
                throw new CliException("HTTP service is already running on " + existing.host() + ":" + existing.port());
            }
            if (foreground) {
                return runServer(store, host, port, apiKey, parent.root.output(new CommandLine(this)));
            }

            Path logs = store.home().resolve("logs");
            Files.createDirectories(logs);
            Path logFile = logs.resolve("server.log");
            List<String> command = serverProcessCommand(store.home(), host, port);
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                    .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.environment().put("CODEX_CHANNEL_API_KEY", apiKey);
            }
            Process process = builder.start();
            ServiceState state = waitUntilStarted(store, process, Duration.ofSeconds(15));
            parent.root.output(new CommandLine(this)).value(Map.of(
                    "status", "running",
                    "pid", state.pid(),
                    "url", publicUrl(state),
                    "log", logFile.toString()));
            return 0;
        }
    }

    @Command(name = "run", hidden = true, description = "Internal foreground service entry point")
    static final class Run implements Callable<Integer> {
        @ParentCommand ServerCommand parent;
        @Option(names = "--host", required = true) String host;
        @Option(names = "--port", required = true) int port;

        @Override public Integer call() throws Exception {
            String apiKey = System.getenv("CODEX_CHANNEL_API_KEY");
            validateAddress(host, port, apiKey);
            return runServer(parent.root.store(), host, port, apiKey,
                    parent.root.output(new CommandLine(this)));
        }
    }

    @Command(name = "stop", description = "Stop the background HTTP service")
    static final class Stop implements Callable<Integer> {
        @ParentCommand ServerCommand parent;

        @Override public Integer call() {
            AccountStore store = parent.root.store();
            ServiceState state = liveState(store);
            if (state == null) {
                parent.root.output(new CommandLine(this)).line("HTTP service is not running.");
                return 0;
            }
            controlRequest(state, "POST", "/_admin/stop");
            Instant deadline = Instant.now().plusSeconds(10);
            while (Instant.now().isBefore(deadline) && processAlive(state.pid())) {
                sleep(100);
            }
            if (processAlive(state.pid())) {
                throw new CliException("HTTP service did not stop within 10 seconds");
            }
            store.clearServiceState();
            parent.root.output(new CommandLine(this)).line("HTTP service stopped.");
            return 0;
        }
    }

    @Command(name = "status", description = "Show HTTP service and circuit-breaker status")
    static final class Status implements Callable<Integer> {
        @ParentCommand ServerCommand parent;

        @Override public Integer call() {
            AccountStore store = parent.root.store();
            ServiceState state = liveState(store);
            if (state == null) {
                parent.root.output(new CommandLine(this)).value(Map.of("status", "stopped"));
                return 1;
            }
            JsonNode remote = controlRequest(state, "GET", "/_admin/status");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "running");
            result.put("pid", state.pid());
            result.put("url", publicUrl(state));
            result.put("started_at", state.startedAt());
            result.put("accounts", remote.path("accounts"));
            parent.root.output(new CommandLine(this)).value(result);
            return 0;
        }
    }

    @Command(name = "logs", description = "Print the latest HTTP service log lines")
    static final class Logs implements Callable<Integer> {
        @ParentCommand ServerCommand parent;
        @Option(names = {"-n", "--lines"}, defaultValue = "50") int lines;

        @Override public Integer call() throws IOException {
            if (lines < 1 || lines > 10_000) throw new CliException("--lines must be between 1 and 10000");
            Path log = parent.root.store().home().resolve("logs/server.log");
            if (!Files.isRegularFile(log)) {
                parent.root.output(new CommandLine(this)).line("No service log exists yet: " + log);
                return 0;
            }
            List<String> all = Files.readAllLines(log, StandardCharsets.UTF_8);
            CliOutput output = parent.root.output(new CommandLine(this));
            for (String line : all.subList(Math.max(0, all.size() - lines), all.size())) output.line(line);
            return 0;
        }
    }

    private static int runServer(AccountStore store, String host, int port, String apiKey, CliOutput output)
            throws Exception {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String adminToken = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        try (ApiServer server = new ApiServer(store, host, port, apiKey, adminToken)) {
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "codex-channel-shutdown"));
            server.start();
            ServiceState state = store.serviceState();
            output.line("HTTP service listening at " + publicUrl(state));
            server.await();
        }
        return 0;
    }

    private static ServiceState waitUntilStarted(AccountStore store, Process process, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new CliException("HTTP service exited during startup. Check "
                        + store.home().resolve("logs/server.log"));
            }
            ServiceState state = store.serviceState();
            if (state != null && state.pid() == process.pid() && processAlive(state.pid())) return state;
            sleep(100);
        }
        process.destroy();
        throw new CliException("HTTP service did not start within " + timeout.toSeconds() + " seconds");
    }

    private static ServiceState liveState(AccountStore store) {
        ServiceState state = store.serviceState();
        if (state == null) return null;
        if (!processAlive(state.pid())) {
            store.clearServiceState();
            return null;
        }
        return state;
    }

    private static JsonNode controlRequest(ServiceState state, String method, String path) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) controlUri(state, path).toURL().openConnection();
            int timeoutMillis = Math.toIntExact(CONTROL_TIMEOUT.toMillis());
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestMethod(method);
            connection.setRequestProperty("X-Admin-Token", state.adminToken());
            connection.setUseCaches(false);
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            if (status < 200 || status >= 300) {
                throw new CliException("Service control request returned HTTP " + status);
            }
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        } catch (IOException exception) {
            throw new CliException("Could not contact the HTTP service", exception);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static URI controlUri(ServiceState state, String path) {
        String host = connectHost(state.host());
        String authority = host.contains(":") ? "[" + host + "]" : host;
        return URI.create("http://" + authority + ":" + state.port() + path);
    }

    private static String publicUrl(ServiceState state) {
        String authority = state.host().contains(":") ? "[" + state.host() + "]" : state.host();
        return "http://" + authority + ":" + state.port();
    }

    private static String connectHost(String host) {
        if ("0.0.0.0".equals(host)) return "127.0.0.1";
        if ("::".equals(host) || "0:0:0:0:0:0:0:0".equals(host)) return "::1";
        return host;
    }

    private static boolean processAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    static List<String> serverProcessCommand(Path home, String host, int port) {
        boolean nativeImage = isNativeImage();
        String executable = nativeImage
                ? ProcessHandle.current().info().command()
                        .orElseThrow(() -> new CliException("Could not resolve the current native executable"))
                : Path.of(System.getProperty("java.home"), "bin", executableName("java")).toString();
        return serverProcessCommand(
                home, host, port, nativeImage, executable, System.getProperty("java.class.path"));
    }

    static List<String> serverProcessCommand(
            Path home, String host, int port, boolean nativeImage, String executable, String classPath) {
        List<String> applicationArguments = List.of(
                "--home", home.toAbsolutePath().normalize().toString(),
                "server", "run",
                "--host", host,
                "--port", Integer.toString(port));
        if (nativeImage) {
            List<String> command = new java.util.ArrayList<>(applicationArguments.size() + 1);
            command.add(executable);
            command.addAll(applicationArguments);
            return List.copyOf(command);
        }
        List<String> command = new java.util.ArrayList<>(applicationArguments.size() + 4);
        command.add(executable);
        command.add("-cp");
        command.add(classPath);
        command.add(CodexChannelCli.class.getName());
        command.addAll(applicationArguments);
        return List.copyOf(command);
    }

    private static boolean isNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }

    private static void validateAddress(String host, int port, String apiKey) {
        if (host == null || host.isBlank()) throw new CliException("--host must not be blank");
        if (port < 0 || port > 65_535) throw new CliException("--port must be between 0 and 65535");
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        if (!loopback && (apiKey == null || apiKey.isBlank())) {
            throw new CliException("--api-key is required when binding to a non-loopback address");
        }
    }

    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CliException("Interrupted while waiting for the HTTP service", exception);
        }
    }

    private static String executableName(String name) {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? name + ".exe" : name;
    }

    private static java.io.File nullDevice() {
        return Path.of(System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null")
                .toFile();
    }
}
