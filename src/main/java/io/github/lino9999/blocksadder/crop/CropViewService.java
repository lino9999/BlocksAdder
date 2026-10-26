package io.github.lino9999.blocksadder.crop;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class CropViewService implements Listener, AutoCloseable {
    public enum Mode { AUTO, FULL, HIDDEN }
    private record Position(UUID world, int x, int y, int z) {
        static Position of(Location location) { return new Position(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ()); }
        ChunkKey chunk() { return new ChunkKey(world, x >> 4, z >> 4); }
    }
    private record ChunkKey(UUID world, int x, int z) {}
    private record Candidate(Entry entry, double distance) {}
    private record Shown(Entry entry, boolean card) {}
    private static final Comparator<Candidate> WORST_FIRST = Comparator.<Candidate>comparingDouble(Candidate::distance).thenComparingLong(value -> value.entry.serial).reversed();
    private final JavaPlugin plugin;
    private final NamespacedKey preference;
    private final Consumer<Entity> removeHelper;
    private final Map<Position, Entry> entries = new HashMap<>();
    private final Map<ChunkKey, NavigableMap<Long, Entry>> chunks = new HashMap<>();
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private final ArrayDeque<UUID> queue = new ArrayDeque<>();
    private final NavigableMap<Long, Entry> cards = new TreeMap<>();
    private CropViewPolicy policy;
    private BukkitTask task;
    private long serial;
    private long tick;
    private long cardCursor;

    public CropViewService(JavaPlugin plugin, Consumer<Entity> removeHelper) {
        this.plugin = plugin; this.removeHelper = removeHelper;
        preference = new NamespacedKey(plugin, "plant_view");
    }

    public static void validate(ContentRegistry registry, ConfigurationSection config) {
        CropViewPolicy.parse(config);
        for (ItemDefinition item : registry.items()) {
            ConfigurationSection crop = item.options().getConfigurationSection("crop");
            if (crop == null) continue;
            facing(crop);
            if (crop.contains("visuals.far-stages") && !crop.isList("visuals.far-stages")) throw new IllegalArgumentException("Plant far stages must be an item list: " + item.id());
            List<String> far = crop.getStringList("visuals.far-stages");
            if (!far.isEmpty() && far.size() != crop.getStringList("stages").size()) throw new IllegalArgumentException("Plant far stages must match the growth stage count: " + item.id());
            for (String id : far) if (registry.find(id).isEmpty()) throw new IllegalArgumentException("Unknown plant far-stage item: " + id);
        }
    }

    public static Display.Billboard facing(ConfigurationSection crop) {
        try { return Display.Billboard.valueOf(crop.getString("visuals.facing", "VERTICAL").toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Plant facing must be FIXED, VERTICAL, HORIZONTAL or CENTER."); }
    }

    public void reload() {
        requireThread();
        close(); policy = CropViewPolicy.parse(plugin.getConfig());
        for (Player player : Bukkit.getOnlinePlayers()) join(player);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    public Mode mode(Player player) {
        requireThread();
        String value = player.getPersistentDataContainer().get(preference, PersistentDataType.STRING);
        try { return value == null ? Mode.AUTO : Mode.valueOf(value); }
        catch (IllegalArgumentException exception) { return Mode.AUTO; }
    }

    public void mode(Player player, Mode mode) {
        requireThread(); java.util.Objects.requireNonNull(mode);
        player.getPersistentDataContainer().set(preference, PersistentDataType.STRING, mode.name());
        join(player); Viewer viewer = viewers.get(player.getUniqueId()); viewer.job = null; viewer.next = 0;
    }

    public boolean interactive(Player player) { return mode(player) != Mode.HIDDEN; }
    public int shown(Player player) { requireThread(); Viewer viewer = viewers.get(player.getUniqueId()); return viewer == null ? 0 : viewer.shown.size(); }
    public int count() { requireThread(); return entries.size(); }

    public void register(Location anchor, ItemDisplay normal, List<? extends Entity> interactions, Supplier<ItemDisplay> createCard) {
        requireThread(); unregister(anchor);
        Position position = Position.of(anchor);
        Entry entry = new Entry(++serial, position, normal, interactions, createCard);
        entries.put(position, entry); chunks.computeIfAbsent(position.chunk(), unused -> new TreeMap<>()).put(entry.serial, entry);
        for (Viewer viewer : viewers.values()) viewer.next = 0;
    }

    public void unregister(Location anchor) {
        requireThread();
        Entry entry = entries.remove(Position.of(anchor)); if (entry == null) return;
        NavigableMap<Long, Entry> chunk = chunks.get(entry.position.chunk());
        if (chunk != null && chunk.remove(entry.serial) != null && chunk.isEmpty()) chunks.remove(entry.position.chunk());
        for (UUID id : List.copyOf(entry.viewers)) {
            Viewer viewer = viewers.get(id);
            if (viewer != null) { Shown shown = viewer.shown.remove(entry.serial); if (shown != null) hide(viewer, shown); }
        }
        cards.remove(entry.serial);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { join(event.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Viewer viewer = viewers.remove(event.getPlayer().getUniqueId());
        queue.remove(event.getPlayer().getUniqueId());
        if (viewer != null) for (Shown shown : viewer.shown.values()) hide(viewer, shown);
    }
    private void join(Player player) {
        if (viewers.containsKey(player.getUniqueId())) return;
        viewers.put(player.getUniqueId(), new Viewer(player)); queue.addLast(player.getUniqueId());
    }

    private void tick() {
        tick++;
        long deadline = System.nanoTime() + (long) (policy.millisPerTick() * 1_000_000);
        int idle = 0;
        for (int work = 0; work < policy.workPerTick() && !queue.isEmpty() && System.nanoTime() < deadline;) {
            UUID id = queue.removeFirst(); Viewer viewer = viewers.get(id);
            if (viewer == null) continue;
            queue.addLast(id);
            if (viewer.job == null && tick < viewer.next) { if (++idle >= queue.size()) break; continue; }
            idle = 0; work++;
            if (viewer.job == null) viewer.job = new Job(viewer);
            if (viewer.job.step(viewer)) { viewer.job = null; viewer.next = tick + policy.refreshTicks(); }
        }
        var selected = cards.higherEntry(cardCursor);
        if (selected == null) cardCursor = 0;
        else {
            Entry entry = selected.getValue(); cardCursor = selected.getKey();
            if (entry.cardUsers == 0 && tick - entry.lastCard > 100) {
                ItemDisplay old = entry.card; entry.card = null; cards.remove(entry.serial);
                if (old != null) removeHelper.accept(old);
            }
        }
    }

    private final class Job {
        private final Location origin;
        private final Mode mode;
        private final int minX, maxX, minZ, maxZ;
        private int x, z, stage, nearby;
        private long cursor;
        private final PriorityQueue<Candidate> full = new PriorityQueue<>(WORST_FIRST);
        private final PriorityQueue<Candidate> sparse = new PriorityQueue<>(WORST_FIRST);
        private java.util.Iterator<Candidate> picked;
        private final NavigableMap<Long, Candidate> desired = new TreeMap<>();
        private final Set<Position> counted = new HashSet<>();
        private boolean crowded;
        Job(Viewer viewer) {
            origin = viewer.player.getLocation(); mode = mode(viewer.player);
            minX = (int) Math.floor((origin.getX() - policy.radius()) / 16); maxX = (int) Math.floor((origin.getX() + policy.radius()) / 16);
            minZ = (int) Math.floor((origin.getZ() - policy.radius()) / 16); maxZ = (int) Math.floor((origin.getZ() + policy.radius()) / 16);
            x = minX; z = minZ; stage = mode == Mode.HIDDEN ? 2 : 0;
        }
        boolean step(Viewer viewer) {
            if (!viewer.player.getWorld().getUID().equals(origin.getWorld().getUID()) || mode(viewer.player) != mode) { viewer.next = 0; return true; }
            if (stage == 0) {
                var bucket = chunks.get(new ChunkKey(origin.getWorld().getUID(), x, z));
                var next = bucket == null ? null : bucket.higherEntry(cursor);
                if (next == null) {
                    cursor = 0;
                    if (++z > maxZ) { z = minZ; x++; }
                    if (x > maxX) {
                        crowded = policy.crowded(nearby, viewer.crowded); viewer.crowded = crowded;
                        picked = (mode == Mode.AUTO && crowded ? sparse : full).iterator(); stage = 1;
                    }
                    return false;
                }
                Entry entry = next.getValue(); cursor = next.getKey();
                double dx = entry.position.x + .5 - origin.getX(), dz = entry.position.z + .5 - origin.getZ();
                double distance = dx * dx + dz * dz;
                if (distance <= policy.radius() * policy.radius()) {
                    if (nearby <= policy.crowdedAt() && counted.add(entry.position)) nearby++;
                    Candidate candidate = new Candidate(entry, distance); offer(full, candidate);
                    if (policy.retained(entry.position.x, entry.position.z, distance) && policy.faces(dx, dz, origin.getYaw(), origin.getY() - entry.position.y)) offer(sparse, candidate);
                }
                return false;
            }
            if (stage == 1) {
                if (picked.hasNext()) { Candidate candidate = picked.next(); desired.put(candidate.entry.serial, candidate); }
                else { stage = 2; cursor = 0; }
                return false;
            }
            if (stage == 2) {
                var old = viewer.shown.higherEntry(cursor);
                if (old == null) { stage = 3; cursor = 0; return false; }
                cursor = old.getKey(); Candidate selected = desired.get(cursor);
                boolean card = selected != null && card(selected);
                if (selected == null || entries.get(selected.entry.position) != selected.entry || old.getValue().card != card) {
                    hide(viewer, old.getValue()); viewer.shown.remove(cursor);
                }
                return false;
            }
            var next = desired.higherEntry(cursor);
            if (next == null) return true;
            cursor = next.getKey(); Candidate candidate = next.getValue(); Entry entry = candidate.entry;
            if (entries.get(entry.position) == entry && !viewer.shown.containsKey(cursor)) {
                Location now = viewer.player.getLocation();
                double dx = entry.position.x + .5 - now.getX(), dz = entry.position.z + .5 - now.getZ();
                if (dx * dx + dz * dz <= policy.radius() * policy.radius()) show(viewer, entry, card(candidate));
            }
            return false;
        }
        private boolean card(Candidate candidate) { return mode == Mode.AUTO && crowded && candidate.distance > policy.cardAfter() * policy.cardAfter() && candidate.entry.normal != null && !candidate.entry.cardFailed; }
        private void offer(PriorityQueue<Candidate> heap, Candidate candidate) {
            heap.add(candidate); if (heap.size() > policy.maximum()) heap.poll();
        }
    }

    private void show(Viewer viewer, Entry entry, boolean card) {
        ItemDisplay display = entry.normal;
        if (card && entry.createCard != null) {
            if (entry.card == null) {
                try { entry.card = entry.createCard.get(); }
                catch (RuntimeException exception) { entry.cardFailed = true; plugin.getLogger().warning("Plant card could not be created at " + entry.position + ": " + exception.getMessage()); }
                if (entry.card != null) cards.put(entry.serial, entry); else entry.cardFailed = true;
            }
            if (entry.card != null) display = entry.card; else card = false;
        } else card = false;
        if (display != null && !display.isDead()) viewer.player.showEntity(plugin, display);
        for (Entity interaction : entry.interactions) if (!interaction.isDead()) viewer.player.showEntity(plugin, interaction);
        viewer.shown.put(entry.serial, new Shown(entry, card)); entry.viewers.add(viewer.player.getUniqueId());
        if (card) { entry.cardUsers++; entry.lastCard = tick; }
    }
    private void hide(Viewer viewer, Shown shown) {
        Entry entry = shown.entry;
        Entity display = shown.card ? entry.card : entry.normal;
        if (display != null) viewer.player.hideEntity(plugin, display);
        for (Entity interaction : entry.interactions) viewer.player.hideEntity(plugin, interaction);
        entry.viewers.remove(viewer.player.getUniqueId());
        if (shown.card) { entry.cardUsers = Math.max(0, entry.cardUsers - 1); entry.lastCard = tick; }
    }

    @Override public void close() {
        requireThread();
        if (task != null) { task.cancel(); task = null; }
        for (Viewer viewer : viewers.values()) for (Shown shown : viewer.shown.values()) hide(viewer, shown);
        for (Entry entry : cards.values()) if (entry.card != null) removeHelper.accept(entry.card);
        viewers.clear(); queue.clear(); entries.clear(); chunks.clear(); cards.clear();
    }
    private static void requireThread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Plant views require the server thread."); }
    private static final class Viewer {
        final Player player;
        final NavigableMap<Long, Shown> shown = new TreeMap<>();
        Job job;
        long next;
        boolean crowded;
        Viewer(Player player) { this.player = player; }
    }
    private static final class Entry {
        final long serial;
        final Position position;
        final ItemDisplay normal;
        final List<Entity> interactions;
        final Supplier<ItemDisplay> createCard;
        final Set<UUID> viewers = new HashSet<>();
        ItemDisplay card;
        int cardUsers;
        long lastCard;
        boolean cardFailed;
        Entry(long serial, Position position, ItemDisplay normal, List<? extends Entity> interactions, Supplier<ItemDisplay> createCard) {
            this.serial = serial; this.position = position; this.normal = normal; this.interactions = List.copyOf(interactions); this.createCard = createCard;
        }
    }
}
