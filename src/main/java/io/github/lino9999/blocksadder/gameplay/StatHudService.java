package io.github.lino9999.blocksadder.gameplay;

import io.github.lino9999.blocksadder.content.ContentId;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

final class StatHudService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private final Map<UUID, Map<String, BossBar>> bars = new HashMap<>();
    private Map<String, StatDefinition> stats = Map.of();
    private Map<String, HudDefinition> huds = Map.of();
    private BukkitTask task;

    StatHudService(JavaPlugin plugin, Supplier<ContentRegistry> registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    void reload() {
        load(true);
    }

    void validate() {
        load(false);
    }

    private void load(boolean activate) {
        Map<String, StatDefinition> nextStats = new HashMap<>();
        registry.get().sections("stats").forEach((id, section) -> {
            double min = section.getDouble("min", 0);
            double max = section.getDouble("max", 100);
            double initial = section.getDouble("default", min);
            double regeneration = section.getDouble("regeneration-per-second", 0);
            if (!Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(initial) || !Double.isFinite(regeneration) || max <= min || initial < min || initial > max) throw new IllegalArgumentException("Stat " + id + ": invalid bounds, default or regeneration.");
            nextStats.put(id, new StatDefinition(key("stat", id), min, max, initial, regeneration));
        });
        Map<String, HudDefinition> nextHuds = new HashMap<>();
        registry.get().sections("huds").forEach((id, section) -> {
            String stat = resolve(nextStats, section.getString("stat", ""));
            if (stat == null) throw new IllegalArgumentException("HUD " + id + ": unknown stat.");
            String type = section.getString("type", "bossbar").toLowerCase(Locale.ROOT);
            if (!List.of("bossbar", "actionbar").contains(type)) throw new IllegalArgumentException("HUD " + id + ": type must be bossbar or actionbar.");
            BarColor color = BarColor.valueOf(section.getString("color", "BLUE").toUpperCase(Locale.ROOT));
            BarStyle style = BarStyle.valueOf(section.getString("style", "SOLID").toUpperCase(Locale.ROOT));
            nextHuds.put(id, new HudDefinition(key("hud", id), stat, section.getString("title", "{value}/{max}"), type, color, style, section.getBoolean("visible-by-default", true), section.getString("permission", "blocksadder.hud")));
        });
        if (!activate) return;
        close();
        stats = Map.copyOf(nextStats);
        huds = Map.copyOf(nextHuds);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        tick();
    }

    private NamespacedKey key(String type, String id) {
        ContentId contentId = ContentId.parse(id);
        return new NamespacedKey(plugin, type + "/" + contentId.namespace() + "/" + contentId.value());
    }

    private <T> String resolve(Map<String, T> definitions, String id) {
        if (definitions.containsKey(id)) return id;
        if (id.contains(":")) return null;
        List<String> matches = definitions.keySet().stream().filter(value -> value.endsWith(":" + id)).limit(2).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    double stat(Player player, String requested) {
        String id = resolve(stats, requested);
        if (id == null) throw new IllegalArgumentException("Unknown stat: " + requested);
        StatDefinition definition = stats.get(id);
        double value = player.getPersistentDataContainer().getOrDefault(definition.key(), PersistentDataType.DOUBLE, definition.initial());
        return Double.isFinite(value) ? Math.clamp(value, definition.min(), definition.max()) : definition.initial();
    }

    double stat(Player player, String requested, double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Stat values must be finite numbers.");
        String id = resolve(stats, requested);
        if (id == null) throw new IllegalArgumentException("Unknown stat: " + requested);
        StatDefinition definition = stats.get(id);
        double clamped = Math.clamp(value, definition.min(), definition.max());
        player.getPersistentDataContainer().set(definition.key(), PersistentDataType.DOUBLE, clamped);
        update(player);
        return clamped;
    }

    void hud(Player player, String requested, boolean visible) {
        if (requested.equalsIgnoreCase("all")) {
            huds.values().forEach(hud -> player.getPersistentDataContainer().set(hud.key(), PersistentDataType.BYTE, (byte) (visible ? 1 : 0)));
        } else {
            String id = resolve(huds, requested);
            if (id == null) throw new IllegalArgumentException("Unknown HUD: " + requested);
            player.getPersistentDataContainer().set(huds.get(id).key(), PersistentDataType.BYTE, (byte) (visible ? 1 : 0));
        }
        update(player);
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            stats.forEach((id, stat) -> {
                if (stat.regeneration() == 0) return;
                double old = stat(player, id);
                double value = Math.clamp(old + stat.regeneration(), stat.min(), stat.max());
                if (Double.compare(old, value) != 0) player.getPersistentDataContainer().set(stat.key(), PersistentDataType.DOUBLE, value);
            });
            update(player);
        }
    }

    void update(Player player) {
        Map<String, BossBar> playerBars = bars.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
        StringBuilder actionbar = new StringBuilder();
        huds.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String id = entry.getKey();
            HudDefinition hud = entry.getValue();
            boolean visible = player.getPersistentDataContainer().getOrDefault(hud.key(), PersistentDataType.BYTE, (byte) (hud.visible() ? 1 : 0)) != 0;
            visible = visible && (hud.permission().isBlank() || player.hasPermission(hud.permission()));
            if (!visible) {
                BossBar old = playerBars.remove(id);
                if (old != null) old.removeAll();
                return;
            }
            StatDefinition definition = stats.get(hud.stat());
            double value = stat(player, hud.stat());
            double progress = (value - definition.min()) / (definition.max() - definition.min());
            String title = Text.color(hud.title().replace("{player}", player.getName()).replace("{value}", format(value)).replace("{min}", format(definition.min())).replace("{max}", format(definition.max())).replace("{percent}", format(progress * 100)));
            if (hud.type().equals("actionbar")) {
                if (!actionbar.isEmpty()) actionbar.append(Text.color(" &8| &r"));
                actionbar.append(title);
                return;
            }
            BossBar bar = playerBars.computeIfAbsent(id, ignored -> {
                BossBar created = Bukkit.createBossBar(title, hud.color(), hud.style());
                created.addPlayer(player);
                return created;
            });
            bar.setTitle(title);
            bar.setProgress(Math.clamp(progress, 0, 1));
        });
        if (!actionbar.isEmpty()) player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(actionbar.toString()));
    }

    private String format(double value) {
        if (value == Math.rint(value)) return String.format(Locale.ROOT, "%.0f", value);
        return String.format(Locale.ROOT, "%.1f", value);
    }

    void remove(Player player) {
        Map<String, BossBar> removed = bars.remove(player.getUniqueId());
        if (removed != null) removed.values().forEach(BossBar::removeAll);
    }

    @Override
    public void close() {
        if (task != null) task.cancel();
        task = null;
        bars.values().forEach(playerBars -> playerBars.values().forEach(BossBar::removeAll));
        bars.clear();
    }

    private record StatDefinition(NamespacedKey key, double min, double max, double initial, double regeneration) {}
    private record HudDefinition(NamespacedKey key, String stat, String title, String type, BarColor color, BarStyle style, boolean visible, String permission) {}
}
