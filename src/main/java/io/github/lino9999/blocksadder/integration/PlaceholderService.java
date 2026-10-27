package io.github.lino9999.blocksadder.integration;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.gameplay.GameplayService;
import io.github.lino9999.blocksadder.integration.placeholder.PlaceholderRegistration;
import io.github.lino9999.blocksadder.integration.placeholderdata.PlaceholderView;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class PlaceholderService implements Listener, AutoCloseable {
    public interface Bridge extends AutoCloseable {
        boolean owned();
        @Override void close();
    }

    private final JavaPlugin plugin;
    private final GameplayService gameplay;
    private final Supplier<ContentRegistry> registry;
    private volatile PlaceholderView view;
    private volatile boolean active;
    private boolean closed = true;
    private boolean suspended;
    private boolean connecting;
    private long lifecycle;
    private int ticks;
    private Bridge bridge;
    private BukkitTask task;
    private BukkitTask retry;
    private boolean pendingConnect;
    private ContentRegistry reloadBase;
    private long providerRevision;

    public PlaceholderService(JavaPlugin plugin, GameplayService gameplay, Supplier<ContentRegistry> registry) {
        this.plugin = plugin;
        this.gameplay = gameplay;
        this.registry = registry;
        observe();
    }

    public void start() {
        closed = false;
        suspended = false;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        connect();
    }

    public boolean active() { return active; }

    public java.util.function.Function<String, String> commands(Player player) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Command placeholders require the server thread.");
        if (closed || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return null;
        long expected = providerRevision;
        try {
            return io.github.lino9999.blocksadder.integration.placeholder.CommandPlaceholderResolver.open(plugin, player,
                    () -> !closed && providerRevision == expected);
        } catch (RuntimeException | LinkageError unavailable) { return null; }
    }

    public void validate(ContentRegistry candidate) { new PlaceholderView(candidate).retire(); }

    public void beginReload() { reloadBase = registry.get(); suspended = true; }

    public void finishReload() {
        if (closed) { reloadBase = null; suspended = false; return; }
        try {
            if (!enabled()) disconnect();
            else if (!active) connect();
            else if (reloadBase == registry.get() && view != null) refreshPlayers(view);
            else publish();
        } catch (RuntimeException | LinkageError exception) {
            disconnect();
            plugin.getLogger().warning("Placeholder snapshots could not refresh: " + exception.getMessage());
        } finally {
            suspended = false;
            reloadBase = null;
        }
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("integrations.placeholders", true)
                && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    private void connect() {
        if (closed || active || connecting || !enabled()) return;
        connecting = true;
        long expected = ++lifecycle;
        Bridge candidate = null;
        try {
            observe();
            publish();
            active = true;
            candidate = PlaceholderRegistration.install(plugin, this::request);
            if (!candidate.owned()) throw new IllegalStateException("The placeholder registration changed during setup.");
            if (closed || lifecycle != expected || !active || !enabled()) {
                candidate.close();
                return;
            }
            bridge = candidate;
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
            plugin.getLogger().info("BlocksAdder placeholders registered.");
        } catch (RuntimeException | LinkageError exception) {
            if (candidate != null) try { candidate.close(); }
            catch (RuntimeException | LinkageError cleanup) { exception.addSuppressed(cleanup); }
            disconnect();
            plugin.getLogger().warning("Placeholder adapter could not register: " + exception.getMessage());
        } finally {
            connecting = false;
            if (pendingConnect && !closed) {
                pendingConnect = false;
                if (retry == null) retry = Bukkit.getScheduler().runTask(plugin, () -> { retry = null; connect(); });
            }
        }
    }

    private void observe() {
        gameplay.observeMeters(this::changed, failure -> {
            disconnect();
            plugin.getLogger().warning("Placeholder snapshots stopped after a meter notification failure: " + failure.getMessage());
        });
    }

    private void publish() {
        boolean wasSuspended = suspended;
        suspended = true;
        try {
            PlaceholderView prepared = new PlaceholderView(registry.get());
            refreshPlayers(prepared);
            PlaceholderView previous = view;
            view = prepared;
            if (previous != null) previous.retire();
        } finally {
            suspended = wasSuspended;
        }
    }

    private void refreshPlayers(PlaceholderView prepared) {
        for (Player player : Bukkit.getOnlinePlayers()) prepared.player(player, gameplay.meterSnapshot(player));
    }

    private void changed(Player player, String meter, double value) {
        PlaceholderView current = view;
        if (active && !suspended && current != null) current.value(player, meter, value);
    }

    private String request(Object context, String parameters) {
        if (!active) return null;
        PlaceholderView current = view;
        if (current == null) return null;
        Object selected = context;
        if (Bukkit.isPrimaryThread() && current.meterId(parameters) != null) {
            Player player = resolve(context, current);
            if (view != current || !active || closed) return null;
            String meter = current.meterId(parameters);
            if (player != null && meter != null && !suspended) current.value(player, meter, gameplay.stat(player, meter));
            if (player != null) selected = player;
        }
        return current.request(selected, parameters);
    }

    private Player resolve(Object context, PlaceholderView current) {
        if (!(context instanceof OfflinePlayer offline)) return null;
        Player player = offline.getPlayer();
        if (player == null || !player.isOnline() || Bukkit.getPlayer(player.getUniqueId()) != player) return null;
        if (view != current || !active || closed) return null;
        if (!current.contains(player) && !suspended) current.player(player, gameplay.meterSnapshot(player));
        if (context != player) current.alias(context, player);
        return player;
    }

    private void tick() {
        if (closed || !active || suspended) return;
        if (++ticks % 20 == 0) {
            try {
                if (bridge == null || !bridge.owned()) {
                    disconnect();
                    plugin.getLogger().warning("Placeholder registration ownership changed; the adapter stopped.");
                    return;
                }
            } catch (RuntimeException | LinkageError exception) {
                disconnect();
                return;
            }
        }
        PlaceholderView current = view;
        if (current != null) for (Object context : current.pendingContexts(64)) {
            if (view != current || !active || closed) return;
            resolve(context, current);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        PlaceholderView current = view;
        if (active && !suspended && current != null) current.player(event.getPlayer(), gameplay.meterSnapshot(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        PlaceholderView current = view;
        if (current != null) current.remove(event.getPlayer());
    }

    @EventHandler public void providerEnabled(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("PlaceholderAPI")) {
            providerRevision++;
            if (connecting) pendingConnect = true;
            else connect();
        }
    }

    @EventHandler public void providerDisabled(PluginDisableEvent event) {
        if (event.getPlugin().getName().equals("PlaceholderAPI")) { providerRevision++; pendingConnect = false; disconnect(); }
    }

    private void disconnect() {
        active = false;
        lifecycle++;
        if (task != null) task.cancel();
        task = null;
        if (retry != null) retry.cancel();
        retry = null;
        PlaceholderView previous = view;
        view = null;
        if (previous != null) previous.retire();
        Bridge previousBridge = bridge;
        bridge = null;
        if (previousBridge != null) try { previousBridge.close(); }
        catch (RuntimeException | LinkageError exception) { plugin.getLogger().warning("Placeholder adapter could not detach: " + exception.getMessage()); }
    }

    @Override public void close() {
        providerRevision++;
        closed = true;
        pendingConnect = false;
        disconnect();
        org.bukkit.event.HandlerList.unregisterAll(this);
    }
}
