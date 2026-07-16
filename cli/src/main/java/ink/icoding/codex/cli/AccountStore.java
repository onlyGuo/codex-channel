package ink.icoding.codex.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ink.icoding.codex.core.oauth.ChatGptAccountClient;
import ink.icoding.codex.core.oauth.OpenAiAccount;
import ink.icoding.codex.core.oauth.OpenAiTokenResponse;
import ink.icoding.codex.http.ChromeHttpClient;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class AccountStore {

    private static final String TOKEN_FILE = "token.json";
    private static final String ACCOUNT_FILE = "account.json";
    private static final String METADATA_FILE = "metadata.json";
    private final Path home;
    private final Path accountsDirectory;
    private final Path configFile;
    private final Path serviceFile;
    private final ObjectMapper mapper;

    AccountStore(Path home) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.accountsDirectory = this.home.resolve("accounts");
        this.configFile = this.home.resolve("config.json");
        this.serviceFile = this.home.resolve("service.json");
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        initialize();
    }

    Path home() {
        return home;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    synchronized List<StoredAccount> list() {
        List<StoredAccount> result = new ArrayList<>();
        try (var paths = Files.list(accountsDirectory)) {
            for (Path path : paths.filter(Files::isDirectory).sorted().toList()) {
                try {
                    result.add(load(path.getFileName().toString()));
                } catch (RuntimeException ignored) {
                    // A malformed account is skipped by list but remains available for manual repair/removal.
                }
            }
        } catch (IOException exception) {
            throw storageFailure("Could not list accounts", exception);
        }
        return result;
    }

    synchronized StoredAccount load(String alias) {
        String validated = validateAlias(alias);
        Path directory = accountsDirectory.resolve(validated);
        try {
            if (!Files.isDirectory(directory)) {
                throw new CliException("Account does not exist: " + validated);
            }
            return new StoredAccount(
                    mapper.readValue(directory.resolve(METADATA_FILE).toFile(), AccountMetadata.class),
                    mapper.readValue(directory.resolve(TOKEN_FILE).toFile(), OpenAiTokenResponse.class),
                    mapper.readValue(directory.resolve(ACCOUNT_FILE).toFile(), OpenAiAccount.class),
                    directory);
        } catch (IOException exception) {
            throw storageFailure("Could not read account " + validated, exception);
        }
    }

    synchronized String resolveAlias(String requested) {
        if (requested != null && !requested.isBlank()) {
            return validateAlias(requested);
        }
        String active = config().activeAccount();
        if (active != null && !active.isBlank()) {
            return active;
        }
        List<StoredAccount> accounts = list();
        if (accounts.size() == 1) {
            return accounts.get(0).metadata().alias();
        }
        throw new CliException("No account selected. Use --account <alias> or 'account use <alias>'.");
    }

    synchronized StoredAccount save(
            String requestedAlias, OpenAiTokenResponse token, OpenAiAccount account, boolean overwrite) {
        String alias = requestedAlias == null || requestedAlias.isBlank()
                ? uniqueAlias(defaultAlias(account)) : validateAlias(requestedAlias);
        Path directory = accountsDirectory.resolve(alias);
        if (Files.exists(directory) && !overwrite) {
            throw new CliException("Account alias already exists: " + alias + " (use --force to replace it)");
        }
        try {
            Files.createDirectories(directory);
            secureDirectory(directory);
            Instant now = Instant.now();
            AccountMetadata metadata = Files.isRegularFile(directory.resolve(METADATA_FILE))
                    ? mapper.readValue(directory.resolve(METADATA_FILE).toFile(), AccountMetadata.class)
                    : new AccountMetadata(alias, true, 1, now, now);
            writeJson(directory.resolve(TOKEN_FILE), token, true);
            writeJson(directory.resolve(ACCOUNT_FILE), account, true);
            writeJson(directory.resolve(METADATA_FILE), metadata, false);
            CliConfig config = config();
            if (config.activeAccount() == null) {
                writeConfig(config.withActiveAccount(alias));
            }
            return load(alias);
        } catch (IOException exception) {
            throw storageFailure("Could not save account " + alias, exception);
        }
    }

    synchronized void updateMetadata(AccountMetadata metadata) {
        Path path = accountsDirectory.resolve(validateAlias(metadata.alias())).resolve(METADATA_FILE);
        if (!Files.isRegularFile(path)) {
            throw new CliException("Account does not exist: " + metadata.alias());
        }
        writeJson(path, metadata, false);
    }

    synchronized void use(String alias) {
        load(alias);
        writeConfig(config().withActiveAccount(validateAlias(alias)));
    }

    synchronized void remove(String alias) {
        String validated = validateAlias(alias);
        Path directory = accountsDirectory.resolve(validated);
        if (!Files.isDirectory(directory)) {
            throw new CliException("Account does not exist: " + validated);
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw storageFailure("Could not remove account " + validated, exception);
        }
        CliConfig config = config();
        if (validated.equals(config.activeAccount())) {
            List<StoredAccount> remaining = list();
            writeConfig(config.withActiveAccount(
                    remaining.isEmpty() ? null : remaining.get(0).metadata().alias()));
        }
    }

    synchronized CliConfig config() {
        try {
            return Files.isRegularFile(configFile)
                    ? mapper.readValue(configFile.toFile(), CliConfig.class)
                    : CliConfig.defaults();
        } catch (IOException exception) {
            throw storageFailure("Could not read CLI config", exception);
        }
    }

    synchronized void writeConfig(CliConfig config) {
        writeJson(configFile, config, false);
    }

    synchronized ServiceState serviceState() {
        if (!Files.isRegularFile(serviceFile)) {
            return null;
        }
        try {
            return mapper.readValue(serviceFile.toFile(), ServiceState.class);
        } catch (IOException exception) {
            throw storageFailure("Could not read service state", exception);
        }
    }

    synchronized void writeServiceState(ServiceState state) {
        writeJson(serviceFile, state, true);
    }

    synchronized void clearServiceState() {
        try {
            Files.deleteIfExists(serviceFile);
        } catch (IOException exception) {
            throw storageFailure("Could not clear service state", exception);
        }
    }

    ChatGptAccountClient openClient(String alias) {
        StoredAccount stored = load(alias);
        ChromeHttpClient transport = ChromeHttpClient.newBuilder()
                .cookieJar(stored.directory().resolve("cookies.txt"))
                .build();
        try {
            return new ChatGptAccountClient(
                    stored.directory().resolve(TOKEN_FILE).toFile(),
                    stored.directory().resolve(ACCOUNT_FILE).toFile(),
                    transport);
        } catch (IOException | RuntimeException exception) {
            transport.close();
            throw exception instanceof RuntimeException runtime
                    ? runtime : storageFailure("Could not open account " + alias, exception);
        }
    }

    private void initialize() {
        try {
            Files.createDirectories(accountsDirectory);
            secureDirectory(home);
            secureDirectory(accountsDirectory);
            if (!Files.exists(configFile)) {
                writeConfig(CliConfig.defaults());
            }
        } catch (IOException exception) {
            throw storageFailure("Could not initialize CLI directory " + home, exception);
        }
    }

    private void writeJson(Path target, Object value, boolean secret) {
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".write-", ".tmp");
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
                if (secret) {
                    secureFile(temporary);
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                if (secret) {
                    secureFile(target);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw storageFailure("Could not write " + target, exception);
        }
    }

    private String uniqueAlias(String base) {
        String candidate = base;
        int suffix = 2;
        while (Files.exists(accountsDirectory.resolve(candidate))) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private static String defaultAlias(OpenAiAccount account) {
        String email = account.email();
        int at = email == null ? -1 : email.indexOf('@');
        String source = email == null || email.isBlank()
                ? account.chatgptAccountId() : at > 0 ? email.substring(0, at) : email;
        if (source == null || source.isBlank()) source = "account";
        String normalized = source.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "account" : normalized;
    }

    static String validateAlias(String alias) {
        String value = Objects.requireNonNull(alias, "alias").trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new CliException("Account alias must match [A-Za-z0-9][A-Za-z0-9._-]{0,63}");
        }
        return value;
    }

    private static void secureDirectory(Path path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // POSIX permissions are unavailable on Windows.
        }
    }

    private static void secureFile(Path path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // POSIX permissions are unavailable on Windows.
        }
    }

    private static CliException storageFailure(String message, Exception cause) {
        return new CliException(message, cause);
    }
}
