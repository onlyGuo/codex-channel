package ink.icoding.codex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerCommandTest {

    @TempDir Path temporaryDirectory;

    @Test
    void exposesTheFilteredProjectVersion() {
        String version = new CliVersionProvider().getVersion()[0];
        assertTrue(version.matches("codex-channel [0-9]+\\.[0-9]+\\.[0-9]+(?:[-.][0-9A-Za-z.-]+)?"), version);
        assertFalse(version.contains("${"), version);
    }

    @Test
    void buildsJvmAndNativeBackgroundCommands() {
        Path home = temporaryDirectory.resolve("state");

        List<String> jvm = ServerCommand.serverProcessCommand(
                home, "127.0.0.1", 8787, false, "/jdk/bin/java", "app.jar:deps.jar");
        assertEquals(List.of(
                "/jdk/bin/java", "-cp", "app.jar:deps.jar", CodexChannelCli.class.getName(),
                "--home", home.toAbsolutePath().toString(),
                "server", "run", "--host", "127.0.0.1", "--port", "8787"), jvm);

        List<String> nativeCommand = ServerCommand.serverProcessCommand(
                home, "127.0.0.1", 8787, true, "/opt/codex-channel", "ignored");
        assertEquals(List.of(
                "/opt/codex-channel", "--home", home.toAbsolutePath().toString(),
                "server", "run", "--host", "127.0.0.1", "--port", "8787"), nativeCommand);
    }

    @Test
    void startsQueriesAndStopsABackgroundService() throws Exception {
        Path home = temporaryDirectory.resolve("state");
        AccountStore store = new AccountStore(home);
        try {
            Invocation started = invoke(home, "server", "start", "--port", "0");
            assertEquals(0, started.exitCode(), started.output());
            ServiceState state = store.serviceState();
            assertTrue(ProcessHandle.of(state.pid()).map(ProcessHandle::isAlive).orElse(false));

            Invocation status = invoke(home, "--json", "server", "status");
            assertEquals(0, status.exitCode(), status.output());
            assertTrue(status.output().contains("\"status\" : \"running\""));

            Invocation stopped = invoke(home, "server", "stop");
            assertEquals(0, stopped.exitCode(), stopped.output());
            assertFalse(Files.exists(home.resolve("service.json")));
        } finally {
            ServiceState state = store.serviceState();
            if (state != null) {
                ProcessHandle.of(state.pid()).ifPresent(ProcessHandle::destroy);
            }
        }
    }

    private static Invocation invoke(Path home, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(CodexChannelCli.class.getName());
        command.add("--home");
        command.add(home.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("CLI command timed out: " + String.join(" ", arguments));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Invocation(process.exitValue(), output);
    }

    private record Invocation(int exitCode, String output) {
    }
}
