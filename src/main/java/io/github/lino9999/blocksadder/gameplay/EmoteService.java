package io.github.lino9999.blocksadder.gameplay;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

final class EmoteService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private Map<String, Emote> definitions = Map.of();
    private final Map<UUID, PlayingEmote> active = new HashMap<>();
    private BukkitTask task;

    EmoteService(JavaPlugin plugin, Supplier<ContentRegistry> registry) {
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
        Map<String, Emote> next = new HashMap<>();
        registry.get().sections("emotes").forEach((id, section) -> {
            if (section.contains("model") || section.contains("animation") || section.contains("id") && !section.contains("frames")) return;
            int duration = section.getInt("duration", 40);
            if (duration < 1 || duration > 1200) throw new IllegalArgumentException("Emote " + id + ": duration must be between 1 and 1200 ticks.");
            List<Frame> frames = new ArrayList<>();
            for (Map<?, ?> configured : section.getMapList("frames")) {
                int at = number(configured.get("at"), 0);
                int count = number(configured.get("count"), 5);
                if (at < 0 || at >= duration || count < 1 || count > 100) throw new IllegalArgumentException("Emote " + id + ": invalid frame time or particle count.");
                String particleId = configured.containsKey("particle") ? String.valueOf(configured.get("particle")) : "";
                Particle particle = particleId.isBlank() ? null : Particle.valueOf(particleId.toUpperCase(Locale.ROOT));
                if (particle != null && particle.getDataType() != Void.class) throw new IllegalArgumentException("Emote " + id + ": particle requires extra data and is unsupported.");
                String sound = configured.containsKey("sound") ? String.valueOf(configured.get("sound")) : "";
                if (!sound.isBlank() && NamespacedKey.fromString(sound) == null) throw new IllegalArgumentException("Emote " + id + ": invalid sound key.");
                String message = configured.containsKey("message") ? String.valueOf(configured.get("message")) : "";
                frames.add(new Frame(at, Boolean.TRUE.equals(configured.get("swing")), particle, count, sound, message));
            }
            if (frames.isEmpty() || frames.size() > 128) throw new IllegalArgumentException("Emote " + id + ": configure between 1 and 128 frames.");
            frames.sort(Comparator.comparingInt(Frame::at));
            next.put(id, new Emote(duration, List.copyOf(frames), section.getString("permission", "blocksadder.emote")));
        });
        if (!activate) return;
        close();
        definitions = Map.copyOf(next);
    }

    private int number(Object value, int fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.intValue();
        throw new IllegalArgumentException("Emote frame values must be numbers.");
    }

    boolean play(Player player, String id) {
        if (id.equalsIgnoreCase("stop") || id.equalsIgnoreCase("stop-now")) {
            active.remove(player.getUniqueId());
            return true;
        }
        Emote emote = definitions.get(id);
        if (emote == null && !id.contains(":")) {
            List<Emote> matches = definitions.entrySet().stream().filter(entry -> entry.getKey().endsWith(":" + id)).map(Map.Entry::getValue).limit(2).toList();
            if (matches.size() == 1) emote = matches.getFirst();
        }
        if (emote == null || !emote.permission().isBlank() && !player.hasPermission(emote.permission())) return false;
        active.put(player.getUniqueId(), new PlayingEmote(emote));
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        return true;
    }

    private void tick() {
        Iterator<Map.Entry<UUID, PlayingEmote>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            PlayingEmote playing = entry.getValue();
            if (player == null || !player.isOnline() || player.isDead() || playing.tick >= playing.definition.duration()) {
                iterator.remove();
                continue;
            }
            while (playing.nextFrame < playing.definition.frames().size() && playing.definition.frames().get(playing.nextFrame).at() <= playing.tick) {
                Frame frame = playing.definition.frames().get(playing.nextFrame++);
                if (frame.swing()) player.swingMainHand();
                if (frame.particle() != null) player.getWorld().spawnParticle(frame.particle(), player.getLocation().add(0, 1.25, 0), frame.count(), 0.35, 0.5, 0.35, 0);
                if (!frame.sound().isBlank()) player.getWorld().playSound(player.getLocation(), frame.sound(), 1, 1);
                if (!frame.message().isBlank()) Text.send(player, frame.message());
            }
            playing.tick++;
        }
        if (active.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    void remove(Player player) {
        active.remove(player.getUniqueId());
    }

    @Override
    public void close() {
        if (task != null) task.cancel();
        task = null;
        active.clear();
    }

    private record Frame(int at, boolean swing, Particle particle, int count, String sound, String message) {}
    private record Emote(int duration, List<Frame> frames, String permission) {}
    private static final class PlayingEmote {
        private final Emote definition;
        private int tick;
        private int nextFrame;

        private PlayingEmote(Emote definition) {
            this.definition = definition;
        }
    }
}
