package io.github.lino9999.blocksadder.pack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
    private final PackDownloads revisions = new PackDownloads();
    private final Map<Player, BukkitTask> joining = new IdentityHashMap<>();
    private final PackDelivery delivery;
    private PackDeliveryPolicy policy;
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
        this.delivery = new PackDelivery(plugin, revisions);
    }

    public PackDelivery delivery() { return delivery; }

    public void reload() throws IOException {
        var config = plugin.getConfig();
        var candidatePolicy = PackDeliveryPolicy.read(config);
        String candidate = config.getString("resource-pack.url", "").trim();
        if (!candidate.isBlank()) {
            URI uri = URI.create(candidate);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException("resource-pack.url must be an HTTP(S) URL without credentials or a fragment.");
        }
        stopHost();
        policy = candidatePolicy;
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
                revisions.publish(new PackDownloads.Revision(result, Files.readAllBytes(result.path())));
                if (!closed) {
                    try {
                        Bukkit.getScheduler().runTask(plugin, this::offerWaiting);
                    } catch (org.bukkit.plugin.IllegalPluginAccessException stopped) {
                        if (!closed && plugin.isEnabled()) throw stopped;
                    }
                }
                return result;
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            } finally {
                building.set(false);
            }
        }, worker);
    }

    public PackArtifact artifact() {
        var current = revisions.current();
        return current == null ? null : current.artifact();
    }

    public void apply(Player player) {
        offer(player, false);
    }

    public void applySelf(Player player) {
        offer(player, true);
    }

    private void offer(Player player, boolean self) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Pack requests must run on the server thread.");
        var current = revisions.current();
        if (url.isBlank()) throw new IllegalArgumentException("Set resource-pack.url before sending the resource pack.");
        if (current == null) throw new IllegalArgumentException("Build the resource pack first with /ba pack build.");
        delivery.offer(player, current, url, required, policy, self);
        cancelJoin(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!autoApply || url.isBlank()) return;
        Player player = event.getPlayer();
        cancelJoin(player);
        joining.put(player, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            joining.put(player, null);
            offerIfReady(player);
        }, policy.joinDelayTicks()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { cancelJoin(event.getPlayer()); }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) { cancelJoin(event.getEntity()); }

    private void offerWaiting() {
        for (Player player : List.copyOf(joining.keySet())) {
            if (joining.get(player) == null) offerIfReady(player);
        }
    }

    private void offerIfReady(Player player) {
        if (!player.isOnline()) cancelJoin(player);
        else if (revisions.current() != null) apply(player);
    }

    private void cancelJoin(Player player) {
        BukkitTask timer = joining.remove(player);
        if (timer != null) timer.cancel();
    }

    public void resetDeliveries() {
        List.copyOf(joining.keySet()).forEach(this::cancelJoin);
        delivery.reset();
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
            if (revisions.current() == null) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            String query = exchange.getRequestURI().getRawQuery();
            String hash = null;
            if (query != null) {
                for (String field : query.split("&")) if (field.startsWith("v=")) hash = field.substring(2);
            }
            var current = revisions.resolve(hash);
            if (current == null) {
                exchange.sendResponseHeaders(410, -1);
                return;
            }
            byte[] data = current.bytes();
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
        resetDeliveries();
        delivery.close();
        stopHost();
        worker.shutdownNow();
    }
}
