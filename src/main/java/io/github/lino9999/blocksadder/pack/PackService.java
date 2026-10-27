package io.github.lino9999.blocksadder.pack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class PackService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("BlocksAdder-pack").factory());
    private final AtomicBoolean building = new AtomicBoolean();
    private volatile PackArtifact artifact;
    private volatile byte[] hostedBytes;
    private volatile String url = "";
    private volatile boolean required;
    private volatile long maxBytes;
    private boolean autoApply;
    private volatile boolean closed;
    private HttpServer server;
    private ExecutorService httpWorkers;
    private final Semaphore downloads = new Semaphore(8);
    private java.util.function.BooleanSupplier soundRewrites = () -> false;
    private java.util.function.BooleanSupplier placeholderFonts = () -> false;

    public void setSoundRewrites(java.util.function.BooleanSupplier soundRewrites) { this.soundRewrites = java.util.Objects.requireNonNull(soundRewrites); }
    public void setPlaceholderFonts(java.util.function.BooleanSupplier placeholderFonts) { this.placeholderFonts = java.util.Objects.requireNonNull(placeholderFonts); }

    public PackService(JavaPlugin plugin, Supplier<ContentRegistry> registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    public void reload() throws IOException {
        stopHost();
        var config = plugin.getConfig();
        String candidate = config.getString("resource-pack.url", "").trim();
        if (!candidate.isBlank()) {
            URI uri = URI.create(candidate);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException("resource-pack.url must be an HTTP(S) URL without credentials or a fragment.");
        }
        url = candidate;
        autoApply = config.getBoolean("resource-pack.auto-apply", false);
        required = config.getBoolean("resource-pack.required", false);
        maxBytes = Math.clamp(config.getLong("resource-pack.max-uncompressed-mb", 128), 1, 1024) * 1024L * 1024L;
        if (config.getBoolean("resource-pack.host.enabled", false)) {
            String bind = config.getString("resource-pack.host.bind", "0.0.0.0");
            int port = config.getInt("resource-pack.host.port", 8163);
            server = HttpServer.create(new InetSocketAddress(bind, port), 16);
            httpWorkers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().name("BlocksAdder-http-", 0).factory());
            server.setExecutor(httpWorkers);
            server.createContext("/BlocksAdder.zip", this::serve);
            server.start();
        }
    }

    public CompletableFuture<PackArtifact> build() {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Resource pack service is closed."));
        if (!building.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IllegalStateException("A resource pack build is already running."));
        ContentRegistry snapshot = registry.get();
        long limit = maxBytes;
        boolean rewriteSounds = soundRewrites.getAsBoolean();
        boolean exportFonts = placeholderFonts.getAsBoolean();
        return CompletableFuture.supplyAsync(() -> {
            try {
                PackArtifact result = new PackBuilder(plugin.getDataFolder().toPath()).build(snapshot, limit, rewriteSounds, exportFonts);
                hostedBytes = Files.readAllBytes(result.path());
                artifact = result;
                return result;
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            } finally {
                building.set(false);
            }
        }, worker);
    }

    public PackArtifact artifact() {
        return artifact;
    }

    public void apply(Player player) {
        PackArtifact current = artifact;
        if (url.isBlank()) throw new IllegalArgumentException("Set resource-pack.url before sending the resource pack.");
        if (current == null) throw new IllegalArgumentException("Build the resource pack first with /ba pack build.");
        String separator = url.contains("?") ? "&" : "?";
        player.setResourcePack(UUID.nameUUIDFromBytes(("BlocksAdder:" + current.sha1()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                url + separator + "v=" + current.sha1(), HexFormat.of().parseHex(current.sha1()), "BlocksAdder custom content", required);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!autoApply || url.isBlank() || artifact == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) apply(event.getPlayer());
        }, 20L);
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!required || artifact == null || !event.getID().equals(UUID.nameUUIDFromBytes(("BlocksAdder:" + artifact.sha1()).getBytes(java.nio.charset.StandardCharsets.UTF_8)))) return;
        String status = event.getStatus().name();
        if (status.equals("DECLINED") || status.startsWith("FAILED") || status.equals("INVALID_URL")) event.getPlayer().kickPlayer("The BlocksAdder resource pack is required on this server.");
    }

    private void serve(HttpExchange exchange) throws IOException {
        try {
            if (!exchange.getRequestURI().getPath().equals("/BlocksAdder.zip")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (!exchange.getRequestMethod().equals("GET") && !exchange.getRequestMethod().equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] data = hostedBytes;
            if (data == null) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            if (!downloads.tryAcquire()) {
                exchange.sendResponseHeaders(429, -1);
                return;
            }
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                if (exchange.getRequestMethod().equals("HEAD")) {
                    exchange.getResponseHeaders().set("Content-Length", Integer.toString(data.length));
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                }
            } finally {
                downloads.release();
            }
        } finally {
            exchange.close();
        }
    }

    private void stopHost() {
        if (server != null) server.stop(0);
        if (httpWorkers != null) httpWorkers.shutdownNow();
        server = null;
        httpWorkers = null;
    }

    @Override
    public void close() {
        closed = true;
        stopHost();
        worker.shutdownNow();
    }
}
