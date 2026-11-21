package io.github.lino9999.blocksadder.command.search;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import io.github.lino9999.blocksadder.command.CatalogSearchIndex;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSignOpenEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

final class ProtocolSignPrompt implements CatalogPrompt, Listener {
    private static final int TIMEOUT_TICKS = 1200;
    private static final int RECENT_SUBMISSIONS = 16;
    private static final int MAX_UNANSWERED = 4096;
    private static final List<BlockPosition> OFFSETS = offsets();
    private final JavaPlugin plugin;
    private final ProtocolManager manager;
    private final SignPackets packets;
    private final PacketAdapter listener;
    private final Map<PlayerKey, Request> active = new ConcurrentHashMap<>();
    private final Map<Player, Session> sessions = new IdentityHashMap<>();
    private boolean closed;
    private boolean clearing;
    private boolean warned;

    ProtocolSignPrompt(JavaPlugin plugin) {
        this.plugin = plugin;
        manager = ProtocolLibrary.getProtocolManager();
        packets = new SignPackets(manager);
        listener = new PacketAdapter(plugin, ListenerPriority.HIGHEST, SignPackets.UPDATE) {
            @Override public void onPacketReceiving(PacketEvent event) { receive(event); }
        };
        try {
            manager.addPacketListener(listener);
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
        } catch (RuntimeException | LinkageError failure) {
            close();
            throw failure;
        }
    }

    @Override public boolean available() { return !closed; }

    @Override
    public boolean open(Player player, Consumer<String> submitted) {
        if (!Bukkit.isPrimaryThread() || closed || clearing || !connected(player)) return false;
        cancel(player);
        if (closed || clearing || !connected(player)) return false;
        InventoryView view = player.getOpenInventory();
        if (view.getType() != InventoryType.CRAFTING && view.getType() != InventoryType.CREATIVE) return false;
        PlayerKey key = new PlayerKey(player);
        if (active.containsKey(key)) return false;
        Session session = sessions.computeIfAbsent(player, ignored -> new Session());
        World world = player.getWorld();
        BlockPosition position = nextPosition(player.getEyeLocation(), world, session);
        if (position == null) return false;
        Request request = new Request(key, view, world, position, session, submitted);
        active.put(key, request);
        boolean opened = false;
        try {
            var opener = packets.open(position);
            request.job = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(request), 1L, 1L);
            Location location = request.location();
            request.shown = true;
            player.sendBlockChange(location, Material.OAK_SIGN.createBlockData());
            if (!ready(request)) return false;
            player.sendSignChange(location, new String[]{"", "", "", ""});
            if (!ready(request)) return false;
            manager.sendServerPacket(player, opener);
            opened = ready(request);
            request.accepting = opened;
            return opened;
        } catch (RuntimeException | LinkageError failure) {
            warn(failure);
            return false;
        } finally {
            if (!opened) retire(request);
        }
    }

    private boolean ready(Request request) {
        return active.get(request.key) == request && current(request);
    }

    private void receive(PacketEvent event) {
        if (event.isCancelled()) return;
        Request request = active.get(new PlayerKey(event.getPlayer()));
        if (request == null || !request.accepting) return;
        var packet = event.getPacket();
        if (!request.position.equals(packet.getBlockPositionModifier().readSafely(0)) || !packets.front(packet)) return;
        event.setCancelled(true);
        if (request.reply.get() != null) return;
        String query;
        try {
            query = CatalogSearchIndex.query(packets.lines(packet));
        } catch (RuntimeException failure) {
            query = null;
        }
        request.reply.compareAndSet(null, new Submission(query));
    }

    private void tick(Request request) {
        if (active.get(request.key) != request) return;
        Submission reply = request.reply.get();
        if (!current(request) || ++request.ticks >= TIMEOUT_TICKS) {
            retire(request);
        } else if (reply != null) {
            if (reply.query != null) request.session.acknowledge(request.position);
            retire(request);
            if (reply.query != null && request.session.generation == request.generation && sessions.get(request.key.player) == request.session
                    && !closed && current(request)) request.submitted.accept(reply.query);
        }
    }

    @Override
    public void cancel(Player player) {
        Session session = sessions.get(player);
        if (session != null) session.generation++;
        Request request = active.get(new PlayerKey(player));
        if (request != null) retire(request);
    }

    private void retire(Request request) {
        if (!active.remove(request.key, request)) return;
        request.accepting = false;
        if (request.job != null) request.job.cancel();
        Player player = request.key.player;
        if (!request.shown) request.session.unanswered.remove(request.position);
        if (!request.shown || !connected(player) || player.getWorld() != request.world
                || !request.world.isChunkLoaded(request.position.getX() >> 4, request.position.getZ() >> 4)) return;
        try {
            Location location = request.location();
            var state = request.world.getBlockAt(location).getState();
            player.sendBlockChange(location, state.getBlockData());
            if (state instanceof TileState tile && connected(player) && player.getWorld() == request.world) player.sendBlockUpdate(location, tile);
        } catch (RuntimeException | LinkageError failure) {
            warn(failure);
        }
    }

    private void warn(Throwable failure) {
        if (!warned) {
            warned = true;
            plugin.getLogger().warning("Catalog sign search could not update the client: " + failure.getMessage());
        }
    }

    private static boolean current(Request request) {
        Player player = request.key.player;
        return connected(player) && player.getWorld() == request.world && player.getOpenInventory() == request.view;
    }

    private static boolean connected(Player player) {
        return player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player;
    }

    private static List<BlockPosition> offsets() {
        var offsets = new ArrayList<BlockPosition>();
        for (int x = -3; x <= 3; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -3; z <= 3; z++) offsets.add(new BlockPosition(x, y, z));
            }
        }
        offsets.sort(Comparator.comparingInt(offset -> offset.getX() * offset.getX() + (offset.getY() - 1) * (offset.getY() - 1) + offset.getZ() * offset.getZ()));
        return List.copyOf(offsets);
    }

    private static BlockPosition nextPosition(Location eye, World world, Session session) {
        int centerX = eye.getBlockX();
        int centerZ = eye.getBlockZ();
        if (session.unanswered.size() >= MAX_UNANSWERED || !world.isChunkLoaded(centerX >> 4, centerZ >> 4)) return null;
        for (var offset : OFFSETS) {
            int x = centerX + offset.getX(), y = eye.getBlockY() + offset.getY(), z = centerZ + offset.getZ();
            if (x >> 4 != centerX >> 4 || z >> 4 != centerZ >> 4 || y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
            var position = new BlockPosition(x, y, z);
            if (!session.unanswered.contains(position) && !session.recent.contains(position)
                    && !Tag.ALL_SIGNS.isTagged(world.getBlockAt(x, y, z).getType())) {
                session.unanswered.add(position);
                return position;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void inventoryOpened(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) cancel(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void realSign(PlayerSignOpenEvent event) { cancel(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) { cancel(event.getPlayer()); }

    @EventHandler
    public void changedWorld(PlayerChangedWorldEvent event) { cancel(event.getPlayer()); }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        cancel(event.getPlayer());
        sessions.remove(event.getPlayer());
    }

    @EventHandler
    public void providerDisabled(PluginDisableEvent event) {
        if (event.getPlugin().getName().equals("ProtocolLib")) close();
    }

    @Override
    public void clear() {
        if (clearing) return;
        clearing = true;
        try {
            sessions.values().forEach(session -> session.generation++);
            List.copyOf(active.values()).forEach(this::retire);
        } finally {
            clearing = false;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        clear();
        sessions.clear();
        HandlerList.unregisterAll(this);
        manager.removePacketListener(listener);
    }

    private record PlayerKey(Player player) {
        @Override public int hashCode() { return System.identityHashCode(player); }
        @Override public boolean equals(Object other) { return other instanceof PlayerKey key && player == key.player; }
    }

    private record Submission(String query) {}

    private static final class Session {
        final Set<BlockPosition> unanswered = new HashSet<>();
        final ArrayDeque<BlockPosition> recent = new ArrayDeque<>();
        long generation;

        void acknowledge(BlockPosition position) {
            unanswered.remove(position);
            recent.addLast(position);
            if (recent.size() > RECENT_SUBMISSIONS) recent.removeFirst();
        }
    }

    private static final class Request {
        final PlayerKey key;
        final InventoryView view;
        final World world;
        final BlockPosition position;
        final Session session;
        final long generation;
        final Consumer<String> submitted;
        final AtomicReference<Submission> reply = new AtomicReference<>();
        BukkitTask job;
        int ticks;
        boolean shown;
        volatile boolean accepting;

        Request(PlayerKey key, InventoryView view, World world, BlockPosition position, Session session, Consumer<String> submitted) {
            this.key = key;
            this.view = view;
            this.world = world;
            this.position = position;
            this.session = session;
            this.generation = session.generation;
            this.submitted = submitted;
        }

        Location location() { return new Location(world, position.getX(), position.getY(), position.getZ()); }
    }
}
