package io.github.lino9999.blocksadder.gameplay;

import io.github.lino9999.blocksadder.content.ContentId;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.gameplay.hud.HudArt;
import io.github.lino9999.blocksadder.gameplay.hud.HudOverlay;
import io.github.lino9999.blocksadder.gameplay.hud.MeterMath;
import io.github.lino9999.blocksadder.gameplay.hud.MeterPolicy;
import io.github.lino9999.blocksadder.gameplay.hud.MeterStore;
import io.github.lino9999.blocksadder.pack.HudSpaceFont;
import io.github.lino9999.blocksadder.util.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

final class StatHudService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private final MeterStore meters = new MeterStore();
    private final Map<UUID, PlayerState> players = new HashMap<>();
    private final Set<UUID> updating = new HashSet<>();
    private Map<String, HudOverlay> huds = Map.of();
    private List<HudOverlay> ordered = List.of();
    private BukkitTask task;
    private long ticks;
    private long generation;
    private int fixedGlyphs;

    StatHudService(JavaPlugin plugin, Supplier<ContentRegistry> registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    void reload() { load(true); }
    void validate() { load(false); }

    private void load(boolean activate) {
        ContentRegistry content = registry.get();
        Map<String, MeterStore.Definition> nextMeters = MeterStore.prepare(plugin, content);
        Map<String, HudOverlay> nextHuds = new LinkedHashMap<>();
        content.sections("huds").entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ContentId id = ContentId.parse(entry.getKey());
            nextHuds.put(entry.getKey(), HudOverlay.parse(entry.getKey(), new NamespacedKey(plugin, "hud/" + id.namespace() + "/" + id.value()), entry.getValue(), content, nextMeters.keySet()));
        });
        if (nextHuds.size() > 128) throw new IllegalArgumentException("At most 128 HUD definitions may be active.");
        int glyphCount = nextHuds.values().stream().filter(hud -> hud.art() != null).mapToInt(hud -> hud.art().fixedGlyphs()).sum();
        int textCount = 0;
        int styles = 0;
        for (HudOverlay hud : nextHuds.values()) {
            if (!hud.type().equals("actionbar") || hud.art() != null) continue;
            MeterStore.Definition meter = nextMeters.get(hud.meter());
            int digits = Math.max(format(meter.min()).length(), format(meter.max()).length()) + 2;
            textCount += hud.title().replace("{player}", "X".repeat(16)).replace("{value}", "X".repeat(digits))
                    .replace("{min}", format(meter.min())).replace("{max}", format(meter.max())).replace("{percent}", "100.0").length();
            styles += (int) hud.title().chars().filter(character -> character == '&' || character == 0xA7).count();
        }
        if (glyphCount > 256 || textCount > 8192 || styles > 128) throw new IllegalArgumentException("Actionbar HUDs exceed the shared 256-glyph, 8192-character or 128-style budget.");
        if (!activate) return;
        long expected = generation + 1;
        close();
        if (generation != expected) throw new IllegalStateException("HUD configuration changed during publication.");
        meters.activate(nextMeters);
        huds = Map.copyOf(nextHuds);
        ordered = List.copyOf(nextHuds.values());
        fixedGlyphs = glyphCount;
        ticks = 0;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L);
        Bukkit.getOnlinePlayers().forEach(this::update);
    }

    double stat(Player player, String id) { return meters.get(player, id); }

    double stat(Player player, String id, double value) {
        double changed = meters.set(player, id, value, MeterPolicy.Cause.API);
        update(player);
        return changed;
    }

    double adjust(Player player, String id, double delta) {
        double changed = meters.adjust(player, id, delta, MeterPolicy.Cause.API);
        update(player);
        return changed;
    }

    double command(Player player, String id, double value, boolean relative) {
        if (!meters.permits(player, id, MeterPolicy.Cause.COMMAND)) throw new IllegalArgumentException("This player's meter policy blocks command updates.");
        double changed = relative ? meters.adjust(player, id, value, MeterPolicy.Cause.COMMAND) : meters.set(player, id, value, MeterPolicy.Cause.COMMAND);
        update(player);
        return changed;
    }

    void action(Player player, String id, double value) {
        meters.itemAction(player, id, value, false);
        update(player);
    }

    void actionDelta(Player player, String id, double delta) {
        meters.itemAction(player, id, delta, true);
        update(player);
    }

    private HudOverlay overlay(String requested) {
        String id = HudOverlay.resolve(huds.keySet(), requested);
        if (id == null) throw new IllegalArgumentException("Unknown HUD: " + requested);
        return huds.get(id);
    }

    void hud(Player player, String requested, boolean visible) {
        if (requested.equalsIgnoreCase("all")) for (HudOverlay hud : ordered) remember(player, hud, visible);
        else remember(player, overlay(requested), visible);
        update(player);
    }

    private void remember(Player player, HudOverlay hud, boolean visible) {
        player.getPersistentDataContainer().set(hud.key(), PersistentDataType.BYTE, (byte) (visible ? 1 : 0));
    }

    boolean visible(Player player, String requested) { return visible(player, overlay(requested)); }

    private boolean visible(Player player, HudOverlay hud) {
        return player.isOnline() && hud.allowed(player) && player.getPersistentDataContainer().getOrDefault(hud.key(), PersistentDataType.BYTE, (byte) (hud.visible() ? 1 : 0)) != 0;
    }

    List<String> glyphs(Player player, String requested) {
        HudOverlay hud = custom(requested);
        PlayerState state = players.get(player.getUniqueId());
        return state == null || state.player != player ? List.of() : state.glyphs.getOrDefault(hud.id(), List.of());
    }

    void glyphs(Player player, String requested, List<String> glyphs) {
        HudOverlay hud = custom(requested);
        List<String> checked = hud.art().validateGlyphs(glyphs);
        if (!player.isOnline()) throw new IllegalArgumentException("Custom HUD art requires an online player.");
        PlayerState current = players.get(player.getUniqueId());
        int retained = current == null || current.player != player ? 0 : current.glyphs.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(hud.id())).mapToInt(entry -> entry.getValue().size()).sum();
        if (fixedGlyphs + retained + checked.size() > 256) throw new IllegalArgumentException("Custom HUD art exceeds the shared 256-glyph budget.");
        state(player).glyphs.put(hud.id(), checked);
        update(player);
    }

    private HudOverlay custom(String requested) {
        HudOverlay hud = overlay(requested);
        if (hud.art() == null || !hud.art().custom()) throw new IllegalArgumentException("The HUD does not use custom art.");
        return hud;
    }

    void food(Player player, int delta) {
        meters.food(player, delta);
        update(player);
    }

    void respawn(Player player) {
        long expected = generation;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (expected != generation || !player.isOnline() || Bukkit.getPlayer(player.getUniqueId()) != player) return;
            meters.respawn(player);
            update(player);
        });
    }

    private void tick() {
        ticks += 5;
        long expected = generation;
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            if (!player.isOnline()) continue;
            meters.tick(player, 5);
            if (generation != expected) return;
            if (ticks % 20 == 0) update(player);
        }
    }

    private PlayerState state(Player player) {
        PlayerState old = players.get(player.getUniqueId());
        if (old != null && old.player == player) return old;
        if (old != null) old.bars.values().forEach(BossBar::removeAll);
        PlayerState created = new PlayerState(player);
        players.put(player.getUniqueId(), created);
        return created;
    }

    void update(Player player) {
        if (!player.isOnline()) { remove(player); return; }
        UUID id = player.getUniqueId();
        if (!updating.add(id)) return;
        long expected = generation;
        try {
            PlayerState state = state(player);
            List<BaseComponent> components = new ArrayList<>();
            StringBuilder titles = new StringBuilder();
            for (HudOverlay hud : ordered) {
                if (!current(player, state, expected)) return;
                if (!visible(player, hud)) {
                    BossBar old = state.bars.remove(hud.id());
                    if (old != null) old.removeAll();
                    continue;
                }
                MeterStore.Definition meter = meters.definition(hud.meter());
                double value = meters.get(player, hud.meter());
                double progress = MeterMath.progress(value, meter.min(), meter.max());
                if (hud.type().equals("actionbar") && hud.art() != null) {
                    HudArt.Draw draw = hud.art().render(value, meter.min(), meter.max(), state.glyphs.getOrDefault(hud.id(), List.of()));
                    List<BaseComponent> images = draw.components();
                    if (images.isEmpty()) continue;
                    int offset = hud.x() - (hud.centered() ? draw.width() / 2 : 0);
                    components.add(HudSpaceFont.shift(offset));
                    components.addAll(images);
                    components.add(HudSpaceFont.shift(-offset - draw.width()));
                    continue;
                }
                String title = Text.color(hud.title().replace("{player}", player.getName()).replace("{value}", format(value)).replace("{min}", format(meter.min())).replace("{max}", format(meter.max())).replace("{percent}", format(progress * 100)));
                if (hud.type().equals("actionbar")) {
                    if (!titles.isEmpty()) titles.append(Text.color(" &8| &r"));
                    titles.append(title);
                    continue;
                }
                BossBar bar = state.bars.get(hud.id());
                if (bar == null) {
                    bar = Bukkit.createBossBar(title, hud.color(), hud.style());
                    state.bars.put(hud.id(), bar);
                    bar.addPlayer(player);
                    if (!current(player, state, expected)) { bar.removeAll(); return; }
                }
                bar.setTitle(title);
                if (!current(player, state, expected)) return;
                bar.setProgress(progress);
            }
            if (!current(player, state, expected)) return;
            if (!titles.isEmpty()) components.addAll(List.of(TextComponent.fromLegacyText(titles.toString())));
            if (!components.isEmpty()) {
                state.actionbar = true;
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, components.toArray(BaseComponent[]::new));
            } else if (state.actionbar) {
                state.actionbar = false;
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
            }
            if (state.bars.isEmpty() && state.glyphs.isEmpty() && !state.actionbar) players.remove(id, state);
        } finally {
            updating.remove(id);
        }
    }

    private boolean current(Player player, PlayerState state, long expected) {
        return generation == expected && player.isOnline() && players.get(player.getUniqueId()) == state;
    }

    private String format(double value) {
        return String.format(Locale.ROOT, value == Math.rint(value) ? "%.0f" : "%.1f", value);
    }

    void remove(Player player) {
        meters.remove(player);
        PlayerState state = players.get(player.getUniqueId());
        if (state != null && state.player == player && players.remove(player.getUniqueId(), state)) state.bars.values().forEach(BossBar::removeAll);
    }

    @Override public void close() {
        long expected = ++generation;
        if (task != null) task.cancel();
        task = null;
        meters.activate(Map.of());
        List<PlayerState> previous = List.copyOf(players.values());
        players.clear();
        for (PlayerState state : previous) {
            if (generation != expected) return;
            for (BossBar bar : List.copyOf(state.bars.values())) {
                bar.removeAll();
                if (generation != expected) return;
            }
            if (state.actionbar && state.player.isOnline()) state.player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
        }
    }

    private static final class PlayerState {
        private final Player player;
        private final Map<String, BossBar> bars = new HashMap<>();
        private final Map<String, List<String>> glyphs = new HashMap<>();
        private boolean actionbar;

        private PlayerState(Player player) { this.player = player; }
    }
}
