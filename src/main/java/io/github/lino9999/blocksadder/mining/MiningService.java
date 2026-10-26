package io.github.lino9999.blocksadder.mining;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.item.ItemService;
import io.github.lino9999.blocksadder.util.Text;
import io.github.lino9999.blocksadder.world.WorldContentService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public final class MiningService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private final ItemService items;
    private final WorldContentService world;
    private Map<String, MiningProfile> profiles = Map.of();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private BukkitTask task;
    private long tick;
    private io.github.lino9999.blocksadder.sound.ContentSoundSink sounds;

    public void setSounds(io.github.lino9999.blocksadder.sound.ContentSoundSink sounds) { this.sounds = java.util.Objects.requireNonNull(sounds); }

    public MiningService(JavaPlugin plugin, Supplier<ContentRegistry> registry, ItemService items, WorldContentService world) {
        this.plugin = plugin;
        this.registry = registry;
        this.items = items;
        this.world = world;
    }

    public void validate(ContentRegistry candidate) {
        candidate.items().stream().filter(item -> item.options().isConfigurationSection("block")).forEach(item -> MiningProfile.parse(item, candidate));
        for (var item : candidate.items()) {
            double speed = item.options().getDouble("mining.speed", 1);
            if (!Double.isFinite(speed) || speed < 0 || speed > 100000) throw new IllegalArgumentException("Mining speed must be between 0 and 100000: " + item.id());
            if (item.options().contains("mining.tier")) MiningProfile.tier(item.options().getString("mining.tier"));
        }
    }

    public void reload() {
        validate(registry.get());
        Map<String, MiningProfile> next = new HashMap<>();
        registry.get().items().stream().filter(item -> item.options().isConfigurationSection("block"))
                .forEach(item -> next.put(item.id().toString(), MiningProfile.parse(item, registry.get())));
        close();
        profiles = Map.copyOf(next);
    }

    public MiningProfile profile(String id) {
        String resolved = registry.get().find(id).map(item -> item.id().toString()).orElse(id);
        MiningProfile profile = profiles.get(resolved);
        if (profile == null) throw new IllegalArgumentException("No custom mining profile: " + id);
        return profile;
    }

    public boolean begin(Player player, Block block) {
        requireThread();
        String id = world.identify(block).orElse(null);
        MiningProfile profile = id == null ? null : profiles.get(id);
        if (profile == null) {
            if (id != null && world.kind(block).filter(io.github.lino9999.blocksadder.world.ContentKind.BLOCK::equals).isPresent()) { world.breakPlaced(block.getLocation(), player, false); return true; }
            return false;
        }
        if (!player.hasPermission("blocksadder.break") || player.getGameMode() == GameMode.ADVENTURE || player.getGameMode() == GameMode.SPECTATOR) return true;
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (profile.unbreakable()) return true;
        if (profile.denyWrongTool() && !profile.correctTool(tool, items)) {
            Text.send(player, "This block requires a suitable tool and mining tier.");
            return true;
        }
        Session current = sessions.get(player.getUniqueId());
        if (current != null && current.block.equals(block) && current.id.equals(id)) {
            current.lastSwing = tick;
            return true;
        }
        cancel(player);
        if (player.getGameMode() == GameMode.CREATIVE || profile.hardness() <= 0) {
            world.breakPlaced(block.getLocation(), player, true);
            return true;
        }
        if (!world.canBreak(block.getLocation(), player)) return true;
        Session session = new Session(block, id, tool.clone(), tick);
        sessions.put(player.getUniqueId(), session);
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::advance, 1, 1);
        return true;
    }

    public int activeCount() { return sessions.size(); }

    public boolean breakExtra(Player player, Block block) {
        requireThread();
        if (!player.isOnline() || player.isDead() || !player.getWorld().equals(block.getWorld())
                || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                || !player.hasPermission("blocksadder.break") || player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.CREATIVE) return false;
        String id = world.identify(block).orElse(null);
        if (id == null || world.kind(block).orElse(null) != io.github.lino9999.blocksadder.world.ContentKind.BLOCK) return false;
        MiningProfile profile = profiles.get(id);
        ItemStack tool = player.getInventory().getItemInMainHand().clone();
        int slot = player.getInventory().getHeldItemSlot();
        boolean correct = profile == null || profile.correctTool(tool, items);
        if (profile != null && (profile.unbreakable() || profile.denyWrongTool() && !correct)) return false;
        boolean removed = world.breakPlaced(block.getLocation(), player, profile == null || !profile.correctToolDrops() || correct);
        if (removed && player.getGameMode() == GameMode.SURVIVAL && items.identify(tool).isEmpty()
                && (profile == null || profile.hardness() > 0) && player.getInventory().getHeldItemSlot() == slot
                && player.getInventory().getItemInMainHand().equals(tool)) damageTool(player);
        return removed;
    }

    public void cancel(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session != null) cracks(player, session, 0);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK || event.getClickedBlock() == null || event.getHand() != EquipmentSlot.HAND) return;
        if (begin(event.getPlayer(), event.getClickedBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(BlockDamageEvent event) {
        if (profiles.containsKey(world.identify(event.getBlock()).orElse(""))) {
            event.setCancelled(true);
            begin(event.getPlayer(), event.getBlock());
        }
    }

    @EventHandler
    public void onAbort(BlockDamageAbortEvent event) { cancel(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) session.lastSwing = tick;
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { cancel(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onTeleport(PlayerTeleportEvent event) { cancel(event.getPlayer()); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent event) { cancel(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onSlot(PlayerItemHeldEvent event) { cancel(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onSwap(PlayerSwapHandItemsEvent event) { cancel(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onMode(PlayerGameModeChangeEvent event) { cancel(event.getPlayer()); }

    private void advance() {
        tick++;
        for (UUID uuid : List.copyOf(sessions.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            Session session = sessions.get(uuid);
            if (session == null) continue;
            if (player == null || !valid(player, session)) {
                if (player != null) cancel(player); else sessions.remove(uuid);
                continue;
            }
            MiningProfile profile = profiles.get(session.id);
            ItemStack tool = player.getInventory().getItemInMainHand();
            boolean correct = profile.correctTool(tool, items);
            var haste = player.getPotionEffect(PotionEffectType.HASTE);
            var fatigue = player.getPotionEffect(PotionEffectType.MINING_FATIGUE);
            var speedAttribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("block_break_speed"));
            var value = speedAttribute == null ? null : player.getAttribute(speedAttribute);
            boolean submerged = player.getEyeLocation().getBlock().getType() == org.bukkit.Material.WATER;
            var aquaAffinity = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("aqua_affinity"));
            ItemStack helmet = player.getInventory().getHelmet();
            if (aquaAffinity != null && helmet != null && helmet.getEnchantmentLevel(aquaAffinity) > 0) submerged = false;
            session.progress += profile.progress(profile.speed(tool, items), correct, haste == null ? -1 : haste.getAmplifier(),
                    fatigue == null ? -1 : fatigue.getAmplifier(), submerged, player.isOnGround(), value == null ? 1 : value.getValue());
            if (tick % 4 == 0) {
                if (sounds != null) sounds.hit(session.block.getLocation(), session.id, player);
                else if (!profile.hitSound().isBlank()) player.getWorld().playSound(session.block.getLocation(), profile.hitSound(), 0.25f, 0.8f);
            }
            if (session.progress >= 1) {
                cancel(player);
                ItemStack expected = tool.clone();
                int slot = player.getInventory().getHeldItemSlot();
                if (world.breakPlaced(session.block.getLocation(), player, !profile.correctToolDrops() || correct)
                        && player.getInventory().getHeldItemSlot() == slot && player.getInventory().getItemInMainHand().equals(expected)) damageTool(player);
            } else {
                int stage = Math.min(9, (int) (session.progress * 10));
                if (stage != session.stage) {
                    cracks(player, session, (float) session.progress);
                    session.stage = stage;
                }
            }
        }
        if (sessions.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    private boolean valid(Player player, Session session) {
        if (!player.isOnline() || player.isDead() || !player.hasPermission("blocksadder.break") || player.getGameMode() != GameMode.SURVIVAL
                || tick - session.lastSwing > 12 || !session.block.getWorld().isChunkLoaded(session.block.getX() >> 4, session.block.getZ() >> 4)
                || !world.identify(session.block).filter(session.id::equals).isPresent()) return false;
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!tool.isSimilar(session.tool)) return false;
        var rangeAttribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("block_interaction_range"));
        var range = rangeAttribute == null ? null : player.getAttribute(rangeAttribute);
        double reach = range == null ? 4.5 : Math.clamp(range.getValue(), 0, 64);
        if (!player.getWorld().equals(session.block.getWorld()) || player.getEyeLocation().distanceSquared(session.block.getLocation().add(.5, .5, .5)) > (reach + 1) * (reach + 1)) return false;
        Block target = player.getTargetBlockExact((int) Math.ceil(reach + 1));
        return session.block.equals(target);
    }

    private void cracks(Player player, Session session, float progress) {
        for (Player viewer : session.block.getWorld().getPlayers()) {
            if (viewer.getLocation().distanceSquared(session.block.getLocation()) <= 4096) viewer.sendBlockDamage(session.block.getLocation(), Math.clamp(progress, 0, .999f), player.getEntityId());
        }
    }

    private void damageTool(Player player) {
        int slot = player.getInventory().getHeldItemSlot();
        ItemStack held = player.getInventory().getItemInMainHand();
        ItemStack expected = held.clone();
        if (items.identify(held).isPresent()) return;
        if (!(held.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable meta) || meta.isUnbreakable()) return;
        int max = meta.hasMaxDamage() ? meta.getMaxDamage() : held.getType().getMaxDurability();
        if (max <= 0) return;
        var unbreaking = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
        int level = unbreaking == null ? 0 : held.getEnchantmentLevel(unbreaking);
        if (java.util.concurrent.ThreadLocalRandom.current().nextInt(level + 1) != 0) return;
        var event = new org.bukkit.event.player.PlayerItemDamageEvent(player, held, 1);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || event.getDamage() <= 0 || player.getInventory().getHeldItemSlot() != slot || !player.getInventory().getItemInMainHand().equals(expected)) return;
        if ((long) meta.getDamage() + event.getDamage() >= max) {
            var broken = held.clone();
            Bukkit.getPluginManager().callEvent(new org.bukkit.event.player.PlayerItemBreakEvent(player, broken));
            if (player.getInventory().getHeldItemSlot() != slot || !player.getInventory().getItemInMainHand().equals(expected)) return;
            held.setAmount(held.getAmount() - 1);
        } else {
            meta.setDamage(meta.getDamage() + event.getDamage());
            held.setItemMeta(meta);
        }
        player.getInventory().setItemInMainHand(held);
    }

    private static void requireThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Mining operations must run on the server thread.");
    }

    @Override
    public void close() {
        for (UUID uuid : List.copyOf(sessions.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) cancel(player);
        }
        sessions.clear();
        if (task != null) task.cancel();
        task = null;
    }

    private static final class Session {
        private final Block block;
        private final String id;
        private final ItemStack tool;
        private long lastSwing;
        private double progress;
        private int stage = -1;

        private Session(Block block, String id, ItemStack tool, long tick) {
            this.block = block;
            this.id = id;
            this.tool = tool;
            this.lastSwing = tick;
        }
    }
}
