package ink.icoding.codex.http;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** A running SSE exchange. Closing the call terminates its curl process. */
public final class SseCall implements AutoCloseable {

    private static final int MAX_ERROR_BYTES = 32 * 1024;

    private final ChromeHttpClient client;
    private final ChromeHttpRequest request;
    private final SseListener listener;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final AtomicReference<Process> process = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    SseCall(ChromeHttpClient client, ChromeHttpRequest request, SseListener listener) {
        this.client = client;
        this.request = Objects.requireNonNull(request, "request");
        this.listener = Objects.requireNonNull(listener, "listener");
        client.register(this);
        try {
            client.executor().execute(this::run);
        } catch (RuntimeException exception) {
            client.unregister(this);
            throw exception;
        }
    }

    public CompletableFuture<Void> completion() {
        return completion;
    }

    public void await() throws IOException, InterruptedException {
        try {
            completion.get();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new ChromeHttpException("SSE stream failed", -1, cause);
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void close() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        Process current = process.get();
        if (current != null) {
            current.destroy();
            if (current.isAlive()) {
                current.destroyForcibly();
            }
        }
        completion.cancel(false);
    }

    private void run() {
        Path directory = null;
        boolean locked = false;
        try {
            client.ensureOpen();
            if (client.cookieJar() != null) {
                client.cookieLock().lockInterruptibly();
                locked = true;
            }
            if (cancelled.get()) {
                return;
            }
            directory = Files.createTempDirectory("codex-chrome-sse-");
            Path headers = directory.resolve("response-headers.txt");
            List<String> command = client.commandFor(request, directory);
            command.add("--no-buffer");
            command.add("--dump-header");
            command.add(headers.toString());
            command.add("--output");
            command.add("-");
            Process current = client.processBuilder(command).start();
            process.set(current);
            ByteArrayOutputStream error = new ByteArrayOutputStream();
            Thread errorReader = new Thread(
                    () -> readError(current.getErrorStream(), error), "chrome-http-sse-stderr");
            errorReader.setDaemon(true);
            errorReader.start();

            AtomicBoolean opened = new AtomicBoolean();
            SseParser parser = new SseParser(event -> {
                openIfNeeded(headers, opened);
                listener.onEvent(event);
            });
            long characters = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    characters += line.length() + 1L;
                    if (characters > client.maxResponseBytes()) {
                        current.destroyForcibly();
                        throw new ChromeHttpException(
                                "SSE stream exceeded limit of " + client.maxResponseBytes() + " bytes", -1);
                    }
                    parser.accept(line);
                }
            }
            parser.endOfInput();
            int exitCode = current.waitFor();
            errorReader.join();
            if (cancelled.get()) {
                return;
            }
            if (exitCode != 0) {
                throw new ChromeHttpException(
                        "curl-impersonate SSE process exited with code " + exitCode
                                + (error.size() == 0 ? "" : ": " + error.toString(StandardCharsets.UTF_8).strip()),
                        exitCode);
            }
            openIfNeeded(headers, opened);
            listener.onClosed();
            completion.complete(null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(exception);
        } catch (Throwable exception) {
            fail(exception);
        } finally {
            Process current = process.getAndSet(null);
            if (current != null && current.isAlive()) {
                current.destroyForcibly();
            }
            ChromeHttpClient.deleteRecursively(directory);
            if (locked) {
                client.cookieLock().unlock();
            }
            client.unregister(this);
        }
    }

    private void openIfNeeded(Path headers, AtomicBoolean opened) {
        if (!opened.compareAndSet(false, true)) {
            return;
        }
        try {
            listener.onOpen(CurlResponseParser.parse(headers, request.uri()));
        } catch (IOException exception) {
            opened.set(false);
            throw new CompletionException(exception);
        }
    }

    private void fail(Throwable error) {
        if (cancelled.get() || completion.isDone()) {
            return;
        }
        Throwable reported = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
        try {
            listener.onError(reported);
        } catch (Throwable listenerFailure) {
            reported.addSuppressed(listenerFailure);
        }
        completion.completeExceptionally(reported);
    }

    private static void readError(InputStream input, ByteArrayOutputStream output) {
        try (input) {
            byte[] buffer = new byte[2048];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                int remaining = MAX_ERROR_BYTES - output.size();
                if (remaining > 0) {
                    output.write(buffer, 0, Math.min(read, remaining));
                }
            }
        } catch (IOException ignored) {
            // The process exit code remains the primary transport error.
        }
    }
}
