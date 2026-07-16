package ink.icoding.codex.cli;

import ink.icoding.codex.core.oauth.ChatGptAccountClient;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(name = "account", aliases = "accounts", description = "Manage and inspect accounts", subcommands = {
        AccountCommand.ListAccounts.class,
        AccountCommand.Show.class,
        AccountCommand.Use.class,
        AccountCommand.Enable.class,
        AccountCommand.Disable.class,
        AccountCommand.Weight.class,
        AccountCommand.Schedule.class,
        AccountCommand.Remove.class,
        AccountCommand.Refresh.class,
        AccountCommand.Quota.class,
        AccountCommand.Models.class,
        AccountCommand.Profile.class,
        AccountCommand.Usage.class,
        AccountCommand.Training.class,
        AccountCommand.ResetCredits.class
})
final class AccountCommand implements Runnable {
    @ParentCommand CodexChannelCli root;
    @Override public void run() { new CommandLine(this).usage(System.out); }

    CliOutput output(Object command) { return root.output(new CommandLine(command)); }
    String alias(String requested) { return root.store().resolveAlias(requested); }

    @Command(name = "list", description = "List stored accounts")
    static final class ListAccounts implements Callable<Integer> {
        @ParentCommand AccountCommand parent;
        @Override public Integer call() {
            List<StoredAccount> accounts = parent.root.store().list();
            String active = parent.root.store().config().activeAccount();
            if (parent.root.json) {
                List<Map<String, Object>> result = accounts.stream()
                        .map(account -> summary(account, account.metadata().alias().equals(active)))
                        .toList();
                parent.output(this).value(result);
            } else if (accounts.isEmpty()) {
                parent.output(this).line("No accounts. Run 'auth login'.");
            } else {
                parent.output(this).line("ALIAS\tACTIVE\tENABLED\tWEIGHT\tPLAN\tEMAIL");
                for (StoredAccount account : accounts) {
                    parent.output(this).line(String.join("\t",
                            account.metadata().alias(),
                            account.metadata().alias().equals(active) ? "*" : "",
                            Boolean.toString(account.metadata().enabled()),
                            Integer.toString(account.metadata().weight()),
                            text(account.account().planType()),
                            text(account.account().email())));
                }
            }
            return 0;
        }
    }

    @Command(name = "show", description = "Show account metadata without exposing tokens")
    static final class Show implements Callable<Integer> {
        @ParentCommand AccountCommand parent;
        @Parameters(arity = "0..1") String requested;
        @Override public Integer call() {
            String alias = parent.alias(requested);
            StoredAccount account = parent.root.store().load(alias);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("alias", alias);
            result.put("active", alias.equals(parent.root.store().config().activeAccount()));
            result.put("enabled", account.metadata().enabled());
            result.put("weight", account.metadata().weight());
            result.put("email", account.account().email());
            result.put("account_id", account.account().chatgptAccountId());
            result.put("user_id", account.account().chatgptUserId());
            result.put("organization_id", account.account().organizationId());
            result.put("plan", account.account().planType());
            result.put("subscription_expires_at", account.account().subscriptionExpiresAt());
            parent.output(this).value(result);
            return 0;
        }
    }

    @Command(name = "use", description = "Select the default account")
    static final class Use implements Callable<Integer> {
        @ParentCommand AccountCommand parent;
        @Parameters String alias;
        @Override public Integer call() {
            parent.root.store().use(alias);
            parent.output(this).line("Active account: " + alias);
            return 0;
        }
    }

    @Command(name = "enable", description = "Enable an account for scheduling")
    static final class Enable implements Callable<Integer> {
        @ParentCommand AccountCommand parent;
        @Parameters String alias;
        @Override public Integer call() {
            StoredAccount account = parent.root.store().load(alias);
            parent.root.store().updateMetadata(account.metadata().withEnabled(true));
            parent.output(this).line("Enabled: " + alias);
            return 0;
        }
    }

    @Command(name = "disable", description = "Disable an account for scheduling")
    static final class Disable implements Callable<Integer> {
        @ParentCommand AccountCommand parent;
        @Parameters String alias;
        @Override public Integer call() {
            StoredAccount account = parent.root.store().load(alias);
            parent.root.store().updateMetadata(account.metadata().withEnabled(false));
            parent.output(this).line("Disabled: " + alias);
            return 0;
        }
    }

    @Command(name = "weight", description = "Set scheduling weight (1-100)")
    static final class Weight implements Callable<Integer> {
        @ParentCommand AccountCommand parent;
        @Parameters(index = "0") String alias;
        @Parameters(index = "1") int weight;
        @Override public Integer call() {
            StoredAccount account = parent.root.store().load(alias);
            parent.root.store().updateMetadata(account.metadata().withWeight(weight));
            parent.output(this).line("Weight " + alias + " = " + weight);
            return 0;
        }
    }

    @Command(name = "schedule", description = "Show or configure weighted scheduling and circuit breaking")
    static final class Schedule implements Callable<Integer> {
        @ParentCommand AccountCommand parent;
        @Option(names = "--failure-threshold", description = "Failures before opening a circuit (1-100)")
        Integer failureThreshold;
        @Option(names = "--open-seconds", description = "How long an open circuit remains blocked (1-86400)")
        Long openSeconds;

        @Override public Integer call() {
            CliConfig config = parent.root.store().config();
            if (failureThreshold != null || openSeconds != null) {
                int threshold = failureThreshold == null ? config.failureThreshold() : failureThreshold;
                long duration = openSeconds == null ? config.circuitOpenSeconds() : openSeconds;
                try {
                    config = config.withCircuitBreaker(threshold, duration);
                } catch (IllegalArgumentException exception) {
                    throw new CliException(exception.getMessage(), exception);
                }
                parent.root.store().writeConfig(config);
            }
            parent.output(this).value(Map.of(
                    "policy", config.schedulePolicy(),
                    "failure_threshold", config.failureThreshold(),
                    "open_seconds", config.circuitOpenSeconds()));
            return 0;
        }
    }

    @Command(name = "remove", description = "Remove locally stored account credentials")
    static final class Remove implements Callable<Integer> {
        @ParentCommand AccountCommand parent;
        @Parameters String alias;
        @Option(names = {"-y", "--yes"}) boolean yes;
        @Override public Integer call() throws Exception {
            if (!yes) {
                parent.output(this).line("Remove local credentials for '" + alias + "'? [y/N]");
                String answer = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
                if (!"y".equalsIgnoreCase(answer) && !"yes".equalsIgnoreCase(answer)) {
                    parent.output(this).line("Cancelled.");
                    return 0;
                }
            }
            parent.root.store().remove(alias);
            parent.output(this).line("Removed: " + alias);
            return 0;
        }
    }

    abstract static class RemoteOperation implements Callable<Integer> {
        @ParentCommand AccountCommand parent;
        @Option(names = "--account") String requested;
        String alias() { return parent.alias(requested); }
        <T> T withClient(ClientFunction<T> function) {
            try (ChatGptAccountClient client = parent.root.store().openClient(alias())) {
                return function.apply(client);
            }
        }
    }

    @Command(name = "refresh", description = "Refresh an account token")
    static final class Refresh extends RemoteOperation {
        @Override public Integer call() {
            var token = withClient(ChatGptAccountClient::refreshToken);
            parent.output(this).value(Map.of("account", alias(), "expires_in", token.expiresIn()));
            return 0;
        }
    }

    @Command(name = "quota", description = "Show five-hour and weekly quota")
    static final class Quota extends RemoteOperation {
        @Override public Integer call() {
            parent.output(this).value(withClient(ChatGptAccountClient::fetchQuotaInfo));
            return 0;
        }
    }

    @Command(name = "models", description = "List available Codex models")
    static final class Models extends RemoteOperation {
        @Override public Integer call() {
            parent.output(this).value(withClient(ChatGptAccountClient::fetchModels));
            return 0;
        }
    }

    @Command(name = "profile", description = "Show the remote Codex profile")
    static final class Profile extends RemoteOperation {
        @Override public Integer call() {
            parent.output(this).value(withClient(ChatGptAccountClient::fetchProfile));
            return 0;
        }
    }

    @Command(name = "usage", description = "Show raw usage and rate-limit data")
    static final class Usage extends RemoteOperation {
        @Override public Integer call() {
            parent.output(this).value(withClient(ChatGptAccountClient::fetchUsageJson));
            return 0;
        }
    }

    @Command(name = "training", description = "Enable or disable training")
    static final class Training extends RemoteOperation {
        @Parameters(paramLabel = "enable|disable") String action;
        @Override public Integer call() {
            boolean allowed = switch (action.toLowerCase()) {
                case "enable", "on", "true" -> true;
                case "disable", "off", "false" -> false;
                default -> throw new CliException("training action must be enable or disable");
            };
            withClient(client -> {
                client.setTrainingAllowed(allowed);
                return null;
            });
            parent.output(this).line("Training " + (allowed ? "enabled" : "disabled") + " for " + alias());
            return 0;
        }
    }

    @Command(name = "reset-credits", description = "Show available rate-limit reset credits")
    static final class ResetCredits extends RemoteOperation {
        @Override public Integer call() {
            parent.output(this).value(withClient(ChatGptAccountClient::fetchRateLimitResetCredits));
            return 0;
        }
    }

    @FunctionalInterface
    interface ClientFunction<T> { T apply(ChatGptAccountClient client); }

    private static Map<String, Object> summary(StoredAccount account, boolean active) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("alias", account.metadata().alias());
        result.put("active", active);
        result.put("enabled", account.metadata().enabled());
        result.put("weight", account.metadata().weight());
        result.put("email", account.account().email());
        result.put("plan", account.account().planType());
        return Collections.unmodifiableMap(result);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
