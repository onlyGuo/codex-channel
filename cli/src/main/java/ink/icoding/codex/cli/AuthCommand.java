package ink.icoding.codex.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ink.icoding.codex.core.oauth.AuthorizationSession;
import ink.icoding.codex.core.oauth.ChatGptAccountClient;
import ink.icoding.codex.core.oauth.OpenAiAccount;
import ink.icoding.codex.core.oauth.OpenAiAuthorizationUrlGenerator;
import ink.icoding.codex.core.oauth.OpenAiIdTokenParser;
import ink.icoding.codex.core.oauth.OpenAiTokenClient;
import ink.icoding.codex.core.oauth.OpenAiTokenResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(name = "auth", description = "Authenticate or import accounts", subcommands = {
        AuthCommand.Login.class,
        AuthCommand.Import.class
})
final class AuthCommand implements Runnable {
    @ParentCommand CodexChannelCli root;
    @Override public void run() { new CommandLine(this).usage(System.out); }

    @Command(name = "login", description = "Login with OAuth using local callback or pasted redirect URL")
    static final class Login implements Callable<Integer> {
        @ParentCommand AuthCommand parent;

        @Option(names = "--alias", description = "Account alias") String alias;
        @Option(names = "--manual", description = "Do not start a local callback listener") boolean manual;
        @Option(names = "--timeout-seconds", defaultValue = "600") long timeoutSeconds;
        @Option(names = "--force", description = "Replace an existing alias") boolean force;

        @Override
        public Integer call() throws Exception {
            AuthorizationSession session = new OpenAiAuthorizationUrlGenerator().create();
            CompletableFuture<String> callback = new CompletableFuture<>();
            HttpServer server = manual ? null : startCallbackServer(session, callback);
            CliOutput output = parent.root.output(new CommandLine(this));
            output.line("Open this URL in a browser:\n" + session.authorizationUrl());
            output.line(manual
                    ? "Paste the complete redirected URL below:"
                    : "Waiting for localhost callback. If it cannot reach this host, paste the complete redirected URL:");
            CompletableFuture<String> pasted = readPastedCallback(output);
            try {
                String callbackUrl = (String) CompletableFuture.anyOf(callback, pasted)
                        .get(timeoutSeconds, TimeUnit.SECONDS);
                Map<String, String> parameters = queryParameters(URI.create(callbackUrl));
                if (parameters.containsKey("error")) {
                    throw new CliException("Authorization failed: " + parameters.get("error")
                            + " " + parameters.getOrDefault("error_description", ""));
                }
                if (!session.state().equals(parameters.get("state"))) {
                    throw new CliException("OAuth state mismatch; the pasted callback does not belong to this login");
                }
                String code = parameters.get("code");
                if (code == null || code.isBlank()) {
                    throw new CliException("Callback URL does not contain an authorization code");
                }
                OpenAiTokenResponse token;
                try (OpenAiTokenClient tokenClient = new OpenAiTokenClient()) {
                    token = tokenClient.exchangeAuthorizationCode(code, session);
                }
                OpenAiAccount parsed = new OpenAiIdTokenParser().parse(token);
                OpenAiAccount enriched;
                OpenAiTokenResponse effectiveToken;
                try (ChatGptAccountClient client = new ChatGptAccountClient(token, parsed)) {
                    enriched = client.account();
                    effectiveToken = client.tokenResponse();
                }
                StoredAccount saved = parent.root.store().save(alias, effectiveToken, enriched, force);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("alias", saved.metadata().alias());
                result.put("email", saved.account().email());
                result.put("account_id", saved.account().chatgptAccountId());
                result.put("plan", saved.account().planType());
                output.value(Collections.unmodifiableMap(result));
                return 0;
            } finally {
                if (server != null) {
                    server.stop(0);
                }
            }
        }

        private static HttpServer startCallbackServer(
                AuthorizationSession session, CompletableFuture<String> callback) {
            try {
                URI redirect = URI.create(session.redirectUri());
                HttpServer server = HttpServer.create(
                        new InetSocketAddress("127.0.0.1", redirect.getPort()), 0);
                server.createContext(redirect.getPath(), exchange -> {
                    String url = session.redirectUri()
                            + (exchange.getRequestURI().getRawQuery() == null
                                    ? "" : "?" + exchange.getRequestURI().getRawQuery());
                    callback.complete(url);
                    respond(exchange, 200, "Login received. You can return to the terminal.");
                });
                server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "oauth-callback");
                    thread.setDaemon(true);
                    return thread;
                }));
                server.start();
                return server;
            } catch (IOException exception) {
                return null;
            }
        }

        private static CompletableFuture<String> readPastedCallback(CliOutput output) {
            CompletableFuture<String> result = new CompletableFuture<>();
            Thread thread = new Thread(() -> {
                try {
                    output.line("Callback URL > ");
                    String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
                    if (line != null && !line.isBlank()) {
                        result.complete(line.trim());
                    }
                } catch (IOException exception) {
                    result.completeExceptionally(exception);
                }
            }, "oauth-manual-input");
            thread.setDaemon(true);
            thread.start();
            return result;
        }

        private static void respond(HttpExchange exchange, int status, String text) throws IOException {
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }
    }

    @Command(name = "import", description = "Import token.json and account.json")
    static final class Import implements Callable<Integer> {
        @ParentCommand AuthCommand parent;
        @Parameters(index = "0") Path tokenFile;
        @Parameters(index = "1") Path accountFile;
        @Option(names = "--alias") String alias;
        @Option(names = "--force") boolean force;

        @Override
        public Integer call() throws Exception {
            var mapper = parent.root.store().mapper();
            OpenAiTokenResponse token = mapper.readValue(tokenFile.toFile(), OpenAiTokenResponse.class);
            OpenAiAccount account = mapper.readValue(accountFile.toFile(), OpenAiAccount.class);
            StoredAccount saved = parent.root.store().save(alias, token, account, force);
            parent.root.output(new CommandLine(this)).value(Map.of(
                    "alias", saved.metadata().alias(),
                    "email", String.valueOf(saved.account().email())));
            return 0;
        }
    }

    private static Map<String, String> queryParameters(URI uri) {
        if (uri.getRawQuery() == null) {
            throw new CliException("Redirect URL does not contain query parameters");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String parameter : uri.getRawQuery().split("&")) {
            String[] parts = parameter.split("=", 2);
            result.put(
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }
}
