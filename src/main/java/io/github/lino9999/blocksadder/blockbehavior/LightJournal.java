package io.github.lino9999.blocksadder.blockbehavior;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class LightJournal implements AutoCloseable {
    private final Path file;
    private final ExecutorService executor;
    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean writing = new AtomicBoolean();
    private final Consumer<IOException> failure;
    private volatile IOException error;

    LightJournal(Path file, Consumer<IOException> failure) throws IOException {
        this.file = file;
        this.failure = failure;
        Files.createDirectories(file.getParent());
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BlocksAdder-light-storage");
            thread.setDaemon(true);
            return thread;
        });
    }

    void append(String row) {
        if (error != null) return;
        pending.add(row + "\n");
        if (error == null && writing.compareAndSet(false, true)) executor.execute(this::drain);
    }

    private void drain() {
        try {
            while (!pending.isEmpty()) {
                StringBuilder batch = new StringBuilder();
                String next;
                while (batch.length() < 1048576 && (next = pending.poll()) != null) batch.append(next);
                Files.writeString(file, batch, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException exception) {
            error = exception;
            failure.accept(exception);
        } finally {
            writing.set(false);
            if (error == null && !pending.isEmpty() && writing.compareAndSet(false, true)) executor.execute(this::drain);
        }
    }

    void compact(List<String> rows) {
        executor.execute(() -> {
            if (error != null) return;
            try {
                Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
                Files.write(temporary, rows, StandardCharsets.UTF_8);
                try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException exception) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            } catch (IOException exception) { error = exception; failure.accept(exception); }
        });
    }

    boolean healthy() { return error == null; }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) failure.accept(new IOException("Light storage did not finish within 15 seconds."));
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
