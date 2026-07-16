package ink.icoding.codex.cli;

import ink.icoding.codex.http.ChromeHttpClient;
import ink.icoding.codex.http.CurlImpersonateResolver;
import ink.icoding.codex.http.CurlPlatform;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(name = "transport", description = "Inspect or install the Chrome TLS transport", subcommands = {
        TransportCommand.Doctor.class,
        TransportCommand.Install.class
})
final class TransportCommand implements Runnable {
    @ParentCommand CodexChannelCli root;
    @Override public void run() { new CommandLine(this).usage(System.out); }

    @Command(name = "doctor", description = "Verify curl-impersonate and show transport capabilities")
    static final class Doctor implements Callable<Integer> {
        @ParentCommand TransportCommand parent;
        @Override public Integer call() {
            CurlPlatform platform = CurlPlatform.current();
            Path executable = CurlImpersonateResolver.requireExecutable();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("os", platform.operatingSystem().name().toLowerCase());
            result.put("architecture", platform.architecture().name().toLowerCase());
            result.put("executable", executable.toString());
            try (ChromeHttpClient client = ChromeHttpClient.newBuilder().executable(executable).build()) {
                result.put("capabilities", client.capabilities());
            }
            parent.root.output(new CommandLine(this)).value(result);
            return 0;
        }
    }

    @Command(name = "install", description = "Install or resolve the pinned curl-impersonate binary")
    static final class Install implements Callable<Integer> {
        @ParentCommand TransportCommand parent;
        @Override public Integer call() {
            Path executable = CurlImpersonateResolver.requireExecutable();
            parent.root.output(new CommandLine(this)).value(Map.of(
                    "status", "ready", "executable", executable.toString()));
            return 0;
        }
    }
}
