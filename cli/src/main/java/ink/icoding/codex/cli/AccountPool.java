package ink.icoding.codex.cli;

import ink.icoding.codex.core.oauth.ChatGptAccountClient;
import ink.icoding.codex.core.oauth.ChatGptAccountException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicInteger;

final class AccountPool implements AutoCloseable {

    private final AccountStore store;
    private final Map<String, AccountRuntime> runtimes = new LinkedHashMap<>();
    private final AtomicInteger cursor = new AtomicInteger();
    private volatile long nextReloadNanos;

    AccountPool(AccountStore store) {
        this.store = store;
        reload();
    }

    <T> ScheduledResult<T> execute(AccountOperation<T> operation) {
        List<AccountRuntime> weighted = candidates();
        if (weighted.isEmpty()) {
            throw new CliException("No enabled accounts are configured");
        }
        RuntimeException lastFailure = null;
        int start = Math.floorMod(cursor.getAndIncrement(), weighted.size());
        for (AccountRuntime runtime : failoverOrder(weighted, start)) {
            if (!runtime.breaker.tryAcquire(Instant.now())) {
                continue;
            }
            try {
                T result = operation.apply(runtime.client());
                runtime.breaker.success();
                return new ScheduledResult<>(runtime.metadata.alias(), result);
            } catch (RuntimeException exception) {
                if (!isAccountFailure(exception)) {
                    runtime.breaker.success();
                    throw exception;
                }
                runtime.breaker.failure(Instant.now());
                lastFailure = exception;
            }
        }
        throw new CliException("No account is currently available"
                + (lastFailure == null ? "" : ": " + lastFailure.getMessage()), lastFailure);
    }

    AccountLease acquire() {
        List<AccountRuntime> weighted = candidates();
        if (weighted.isEmpty()) {
            throw new CliException("No enabled accounts are configured");
        }
        int start = Math.floorMod(cursor.getAndIncrement(), weighted.size());
        for (AccountRuntime runtime : failoverOrder(weighted, start)) {
            if (runtime.breaker.tryAcquire(Instant.now())) {
                try {
                    return new AccountLease(runtime, runtime.client());
                } catch (RuntimeException exception) {
                    runtime.breaker.failure(Instant.now());
                }
            }
        }
        throw new CliException("All enabled accounts have open circuits");
    }

    List<Map<String, Object>> status() {
        refreshIfNeeded();
        Instant now = Instant.now();
        synchronized (this) {
            return runtimes.values().stream()
                    .sorted(Comparator.comparing(runtime -> runtime.metadata.alias()))
                    .map(runtime -> {
                        CircuitBreaker.Snapshot snapshot = runtime.breaker.snapshot(now);
                        Map<String, Object> value = new LinkedHashMap<>();
                        value.put("alias", runtime.metadata.alias());
                        value.put("enabled", runtime.metadata.enabled());
                        value.put("weight", runtime.metadata.weight());
                        value.put("circuit", snapshot.state().name().toLowerCase());
                        value.put("failures", snapshot.consecutiveFailures());
                        value.put("open_until", snapshot.openUntil());
                        return Collections.unmodifiableMap(value);
                    })
                    .toList();
        }
    }

    private List<AccountRuntime> candidates() {
        refreshIfNeeded();
        List<AccountRuntime> weighted = new ArrayList<>();
        synchronized (this) {
            for (AccountRuntime runtime : runtimes.values()) {
                if (runtime.metadata.enabled()) {
                    for (int index = 0; index < runtime.metadata.weight(); index++) {
                        weighted.add(runtime);
                    }
                }
            }
        }
        return weighted;
    }

    private static List<AccountRuntime> failoverOrder(List<AccountRuntime> weighted, int start) {
        LinkedHashSet<AccountRuntime> unique = new LinkedHashSet<>();
        for (int offset = 0; offset < weighted.size(); offset++) {
            unique.add(weighted.get((start + offset) % weighted.size()));
        }
        return List.copyOf(unique);
    }

    private void refreshIfNeeded() {
        if (System.nanoTime() >= nextReloadNanos) {
            reload();
        }
    }

    private synchronized void reload() {
        CliConfig config = store.config();
        Map<String, StoredAccount> current = new LinkedHashMap<>();
        for (StoredAccount account : store.list()) {
            current.put(account.metadata().alias(), account);
            AccountRuntime existing = runtimes.get(account.metadata().alias());
            if (existing == null) {
                runtimes.put(account.metadata().alias(), new AccountRuntime(
                        account.metadata(),
                        new CircuitBreaker(config.failureThreshold(), Duration.ofSeconds(config.circuitOpenSeconds()))));
            } else {
                existing.metadata = account.metadata();
            }
        }
        for (String removed : new ArrayList<>(runtimes.keySet())) {
            if (!current.containsKey(removed)) {
                AccountRuntime runtime = runtimes.remove(removed);
                runtime.close();
            }
        }
        nextReloadNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    }

    static boolean isAccountFailure(RuntimeException exception) {
        if (exception instanceof ChatGptAccountException accountException) {
            int status = accountException.statusCode();
            return status < 0 || status == 401 || status == 403 || status == 408 || status == 429 || status >= 500;
        }
        return true;
    }

    @Override
    public synchronized void close() {
        runtimes.values().forEach(AccountRuntime::close);
        runtimes.clear();
    }

    @FunctionalInterface
    interface AccountOperation<T> {
        T apply(ChatGptAccountClient client);
    }

    record ScheduledResult<T>(String accountAlias, T value) {
    }

    final class AccountLease {
        private final AccountRuntime runtime;
        private final ChatGptAccountClient client;
        private boolean completed;

        private AccountLease(AccountRuntime runtime, ChatGptAccountClient client) {
            this.runtime = runtime;
            this.client = client;
        }

        String alias() {
            return runtime.metadata.alias();
        }

        ChatGptAccountClient client() {
            return client;
        }

        void success() {
            if (!completed) {
                completed = true;
                runtime.breaker.success();
            }
        }

        void failure(Throwable error) {
            if (!completed) {
                completed = true;
                if (error instanceof RuntimeException runtimeException && !isAccountFailure(runtimeException)) {
                    runtime.breaker.success();
                } else {
                    runtime.breaker.failure(Instant.now());
                }
            }
        }
    }

    private final class AccountRuntime {
        private volatile AccountMetadata metadata;
        private final CircuitBreaker breaker;
        private ChatGptAccountClient client;

        private AccountRuntime(AccountMetadata metadata, CircuitBreaker breaker) {
            this.metadata = metadata;
            this.breaker = breaker;
        }

        private synchronized ChatGptAccountClient client() {
            if (client == null) {
                client = store.openClient(metadata.alias());
            }
            return client;
        }

        private synchronized void close() {
            if (client != null) {
                client.close();
                client = null;
            }
        }
    }
}
