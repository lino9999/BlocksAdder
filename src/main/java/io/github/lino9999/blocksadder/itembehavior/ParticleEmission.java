package io.github.lino9999.blocksadder.itembehavior;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Function;

final class ParticleEmission {
    private ParticleEmission() {}

    static BehaviorActions.Action compile(Particle particle, boolean line, ConfigurationSection config, Function<String, ItemStack> items) {
        int distance = BehaviorActions.integer(config, "distance", 7, 1, 32);
        var payload = ParticlePayloads.compile(particle, ParticlePayloads.section(config, "payload"), items);
        ConfigurationSection emit = ParticlePayloads.section(config, "emit");
        if (emit == null) emit = new org.bukkit.configuration.file.YamlConfiguration();
        ParticlePayloads.fields(emit, Set.of("count", "spread", "speed", "offset", "force"));
        int count = BehaviorActions.integer(emit, "count", line ? 1 : 40, 0, 1024);
        Vector spread = ParticlePayloads.vector(emit, "spread", new Vector(), 32);
        Vector offset = ParticlePayloads.vector(emit, "offset", new Vector(), 64);
        if (count != 0 && (spread.getX() < 0 || spread.getY() < 0 || spread.getZ() < 0)) throw new IllegalArgumentException("Particle spread must be nonnegative unless count is zero.");
        double speed = BehaviorActions.number(emit, "speed", 0, 0, 16);
        boolean force = ParticlePayloads.flag(emit, "force", false);
        int points = line ? distance * 2 + 1 : 1;
        if ((long) Math.max(1, count) * points > 4096) throw new IllegalArgumentException("A particle action cannot exceed 4096 emitted particles.");
        return context -> {
            Location origin = context.effectOrigin().clone().add(offset);
            Vector direction = origin.getDirection();
            var frames = new ArrayList<Frame>();
            for (int index = 0; index < points; index++) {
                Location sample = origin.clone().add(direction.clone().multiply(index * .5));
                if (!resident(sample)) continue;
                Object data = payload.create(context, sample);
                if (data == null && particle.getDataType() != Void.class) continue;
                frames.add(new Frame(sample, data));
            }
            for (Frame frame : frames) frame.at().getWorld().spawnParticle(particle, frame.at(), count, spread.getX(), spread.getY(), spread.getZ(), speed, frame.data(), force);
            return !frames.isEmpty();
        };
    }

    static boolean resident(Location location) {
        return location.getWorld() != null && Double.isFinite(location.getX()) && Double.isFinite(location.getY()) && Double.isFinite(location.getZ())
                && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private record Frame(Location at, Object data) {}
}
