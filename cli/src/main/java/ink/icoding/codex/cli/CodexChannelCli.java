package ink.icoding.codex.cli;

import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "codex-channel",
        mixinStandardHelpOptions = true,
        versionProvider = CliVersionProvider.class,
        description = "Manage ChatGPT/Codex accounts and run an OpenAI-compatible HTTP service.",
        subcommands = {
                AuthCommand.class,
                AccountCommand.class,
                ServerCommand.class,
                TransportCommand.class
        })
public final class CodexChannelCli implements Callable<Integer> {

    @Option(names = "--home", description = "State directory (default: ${DEFAULT-VALUE})")
    Path home = Path.of(System.getProperty("user.home"), ".codex-channel");

    @Option(names = "--json", description = "Print machine-readable JSON")
    boolean json;

    @Option(names = "--debug", description = "Print stack traces on failure")
    boolean debug;

    private AccountStore store;

    AccountStore store() {
        if (store == null) {
            store = new AccountStore(home);
        }
        return store;
    }

    CliOutput output(CommandLine commandLine) {
        return new CliOutput(store().mapper(), json, commandLine.getOut());
    }

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    public static void main(String[] args) {
        CodexChannelCli root = new CodexChannelCli();
        CommandLine commandLine = new CommandLine(root);
        enableStandardHelp(commandLine, Collections.newSetFromMap(new IdentityHashMap<>()));
        commandLine.setExecutionExceptionHandler((exception, parsed, parseResult) -> {
            parsed.getErr().println("Error: " + rootMessage(exception));
            if (parseResult.hasMatchedOption("--debug")) {
                exception.printStackTrace(parsed.getErr());
            }
            return 1;
        });
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    private static void enableStandardHelp(CommandLine commandLine, Set<CommandLine> visited) {
        if (!visited.add(commandLine)) return;
        commandLine.getCommandSpec().mixinStandardHelpOptions(true);
        for (CommandLine child : commandLine.getSubcommands().values()) {
            enableStandardHelp(child, visited);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getMessage() == null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
