package io.github.lino9999.blocksadder.environment;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class LiquidBiomePalette {
    private static final String OWNER = "BlocksAdder liquid biome palette v1";
    private final JavaPlugin plugin;
    private final NamespacedKey persistence;
    private final Map<NamespacedKey, Template> templates = new LinkedHashMap<>();
    private final Map<Quart, Tint> tints = new HashMap<>();
    private final Map<ChunkKey, Set<Quart>> chunkIndex = new HashMap<>();
    private final Set<ChunkKey> dirty = new LinkedHashSet<>();
    private final Set<ChunkKey> refresh = new LinkedHashSet<>();
    private final Set<NamespacedKey> generated = new LinkedHashSet<>();
    private final Set<NamespacedKey> missing = new LinkedHashSet<>();

    LiquidBiomePalette(JavaPlugin plugin) {
        this.plugin = plugin;
        persistence = new NamespacedKey(plugin, "environment_biomes_v1");
    }

    void prepare(Map<String, EnvironmentDefinition> definitions) {
        templates.clear();
        generated.clear();
        missing.clear();
        Set<Integer> colors = new LinkedHashSet<>();
        definitions.values().stream().filter(definition -> definition.kind() == EnvironmentKind.LIQUID && definition.material() == org.bukkit.Material.WATER).forEach(definition -> colors.add(definition.color()));
        if (definitions.isEmpty() || Bukkit.getWorlds().isEmpty()) return;
        try (BiomeTemplateResolver resolver = new BiomeTemplateResolver(plugin, directory().getParent())) {
            for (Biome biome : Registry.BIOME) {
                NamespacedKey key = biome.getKey();
                if (generatedKey(key)) continue;
                Template template = readTemplate(key, resolver);
                if (template != null) templates.put(key, template);
                else missing.add(key);
            }
        }
        if (colors.isEmpty()) return;
        if (templates.isEmpty()) throw new IllegalStateException("Vanilla biome templates are unavailable through the server resource loader.");
        int limit = Math.clamp(plugin.getConfig().getInt("environment.max-color-biome-variants", 8192), 1, 65536);
        if ((long) colors.size() * templates.size() > limit) throw new IllegalArgumentException("Liquid color biome variants exceed environment.max-color-biome-variants.");
        try {
            Path pack = directory();
            initializePack(pack);
            for (Template template : templates.values()) for (int color : colors) {
                NamespacedKey key = key(template, color);
                JsonObject data = template.data().deepCopy();
                JsonObject effects = data.has("effects") ? data.getAsJsonObject("effects") : new JsonObject();
                effects.addProperty("water_color", hex(color));
                data.add("effects", effects);
                Path target = pack.resolve("data/" + key.getNamespace() + "/worldgen/biome/" + key.getKey() + ".json");
                if (!target.normalize().startsWith(pack.normalize())) throw new IOException("Biome output path escapes its datapack.");
                write(target, data.toString());
                generated.add(key);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not prepare the BlocksAdder liquid biome datapack.", exception);
        }
        int pending = pending();
        if (pending > 0) plugin.getLogger().warning("Prepared " + pending + " liquid color biome variants. A clean server restart is required before those colors can be placed.");
        if (!missing.isEmpty()) plugin.getLogger().warning("Liquid colors cannot preserve " + missing.size() + " biome definitions whose active JSON templates are unavailable: " + missing.stream().map(NamespacedKey::toString).sorted().limit(8).toList());
    }

    Path directory() {
        if (Bukkit.getWorlds().isEmpty()) throw new IllegalStateException("No world is available for the liquid biome datapack.");
        Path folder = Bukkit.getWorlds().getFirst().getWorldFolder().toPath().toAbsolutePath().normalize();
        Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Path dimensionsRoot = null;
        Path cursor = folder;
        for (int depth = 0; depth < 8 && cursor != null; depth++, cursor = cursor.getParent()) {
            if (Files.isRegularFile(cursor.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS)) return cursor.resolve("datapacks/blocksadder_liquids");
            if (cursor.getFileName() != null && cursor.getFileName().toString().equals("dimensions")) dimensionsRoot = cursor.getParent();
            if (cursor.equals(container)) break;
        }
        return (dimensionsRoot == null ? folder : dimensionsRoot).resolve("datapacks/blocksadder_liquids");
    }

    private void initializePack(Path pack) throws IOException {
        if (Files.isSymbolicLink(pack)) throw new IOException("Liquid biome datapack must not be a symbolic link.");
        Files.createDirectories(pack);
        Path marker = pack.resolve(".blocksadder-owner");
        if (Files.exists(marker)) {
            if (!Files.readString(marker).equals(OWNER)) throw new IOException("Liquid biome datapack has an unexpected ownership marker.");
        } else {
            try (var members = Files.list(pack)) {
                if (members.findAny().isPresent()) throw new IOException("Refusing to overwrite an unowned blocksadder_liquids datapack.");
            }
            write(marker, OWNER);
        }
        JsonObject settings = new JsonObject();
        JsonObject metadata = new JsonObject();
        metadata.addProperty("description", "BlocksAdder liquid colors by Lino");
        metadata.add("min_format", JsonParser.parseString("[94,1]"));
        metadata.addProperty("max_format", 121);
        settings.add("pack", metadata);
        write(pack.resolve("pack.mcmeta"), settings.toString());
    }

    private Template readTemplate(NamespacedKey key, BiomeTemplateResolver resolver) {
        JsonObject data = resolver.read(key);
        return data == null ? null : new Template(key, data, digest(data.toString()).substring(0, 16));
    }

    private static void write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        if (Files.isSymbolicLink(path)) throw new IOException("Biome output must not be a symbolic link.");
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.readString(path, StandardCharsets.UTF_8).equals(text)) return;
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    int pending() {
        return (int) generated.stream().filter(key -> Registry.BIOME.get(key) == null).count();
    }

    boolean ready(EnvironmentPosition position, int color) {
        if (!position.available()) return false;
        NamespacedKey base = base(position.block().getBiome().getKey());
        Tint tint = tints.get(Quart.of(position));
        if (tint != null) base = tint.original;
        Template template = templates.get(base);
        return template != null && Registry.BIOME.get(key(template, color)) != null;
    }

    boolean compatible(EnvironmentPosition position, int color) {
        Tint tint = tints.get(Quart.of(position));
        return tint == null || tint.positions.isEmpty() || tint.color == color || tint.positions.size() == 1 && tint.positions.contains(position);
    }

    boolean raining(EnvironmentPosition position) {
        if (!position.available()) return false;
        var block = position.block();
        if (!block.getWorld().hasStorm() || block.getWorld().getHighestBlockYAt(block.getX(), block.getZ()) > block.getY()) return false;
        Template template = templates.get(base(block.getBiome().getKey()));
        if (template == null || !template.data.has("has_precipitation") || !template.data.get("has_precipitation").getAsBoolean()) return false;
        return template.data.has("temperature") && template.data.get("temperature").getAsDouble() >= 0.15 && block.getTemperature() >= 0.15;
    }

    boolean cold(org.bukkit.block.Block block) {
        Template template = templates.get(base(block.getBiome().getKey()));
        return template != null && template.data.has("temperature") && template.data.get("temperature").getAsDouble() <= .15 || block.getTemperature() <= .15;
    }

    boolean apply(EnvironmentPosition position, int color) {
        Quart quart = Quart.of(position);
        Tint tint = tints.get(quart);
        if (!compatible(position, color)) return false;
        NamespacedKey original = tint == null ? base(position.block().getBiome().getKey()) : tint.original;
        Template template = templates.get(original);
        Biome selected = template == null ? null : Registry.BIOME.get(key(template, color));
        if (selected == null) return false;
        if (tint == null) {
            tint = new Tint(original, color);
            putTint(quart, tint);
        }
        boolean changed = tint.color != color;
        tint.color = color;
        changed |= tint.positions.add(position);
        if (changed) dirty.add(ChunkKey.of(position));
        set(quart, selected);
        return true;
    }

    boolean reserve(EnvironmentPosition position, int color) {
        if (!compatible(position, color)) return false;
        Quart quart = Quart.of(position);
        Tint tint = tints.get(quart);
        if (tint == null) {
            tint = new Tint(base(position.block().getBiome().getKey()), color);
            putTint(quart, tint);
        }
        boolean changed = tint.color != color;
        tint.color = color;
        changed |= tint.positions.add(position);
        if (changed) dirty.add(ChunkKey.of(position));
        return true;
    }

    void retain(EnvironmentPosition position) {
        Tint tint = tints.get(Quart.of(position));
        if (tint != null) tint.positions.add(position);
    }

    void neutralize(EnvironmentPosition position) {
        Tint tint = tints.get(Quart.of(position));
        if (tint == null) return;
        Biome original = Registry.BIOME.get(tint.original);
        if (original != null) set(Quart.of(position), original);
        tint.positions.add(position);
    }

    void release(EnvironmentPosition position) {
        Quart quart = Quart.of(position);
        Tint tint = tints.get(quart);
        if (tint == null) return;
        tint.positions.remove(position);
        dirty.add(ChunkKey.of(position));
        if (!tint.positions.isEmpty()) return;
        Biome original = Registry.BIOME.get(tint.original);
        if (original == null) return;
        set(quart, original);
        dropTint(quart);
    }

    void load(Chunk chunk) {
        String encoded = chunk.getPersistentDataContainer().get(persistence, PersistentDataType.STRING);
        if (encoded == null || encoded.isBlank()) return;
        int rejected = 0;
        for (String row : encoded.split("\n")) {
            try {
                String[] parts = row.split("\\|", -1);
                if (parts.length != 5) throw new IllegalArgumentException();
                Quart quart = new Quart(chunk.getWorld().getUID(), Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                if (!quart.in(chunk) || (quart.x & 3) != 0 || (quart.y & 3) != 0 || (quart.z & 3) != 0 || quart.y < chunk.getWorld().getMinHeight() || quart.y >= chunk.getWorld().getMaxHeight()) throw new IllegalArgumentException();
                NamespacedKey original = NamespacedKey.fromString(parts[3]);
                int color = Integer.parseInt(parts[4]);
                if (original == null || color < 0 || color > 0xFFFFFF) throw new IllegalArgumentException();
                putTint(quart, new Tint(original, color));
            } catch (IllegalArgumentException exception) {
                rejected++;
            }
        }
        if (rejected > 0) plugin.getLogger().warning("Ignored " + rejected + " invalid liquid biome records in chunk " + chunk.getX() + ", " + chunk.getZ() + ".");
    }

    void finishLoad(Chunk chunk) {
        for (Quart quart : List.copyOf(chunkIndex.getOrDefault(ChunkKey.of(chunk), Set.of()))) {
            Tint tint = tints.get(quart);
            if (tint.positions.isEmpty()) {
                Biome original = Registry.BIOME.get(tint.original);
                if (original != null) {
                    set(quart, original);
                    dropTint(quart);
                    dirty.add(ChunkKey.of(chunk));
                }
            }
        }
    }

    void unload(Chunk chunk) {
        save(chunk);
        Set<Quart> removed = chunkIndex.remove(ChunkKey.of(chunk));
        if (removed != null) removed.forEach(tints::remove);
        dirty.remove(ChunkKey.of(chunk));
        refresh.remove(ChunkKey.of(chunk));
    }

    void flush() {
        for (ChunkKey key : List.copyOf(dirty)) {
            World world = Bukkit.getWorld(key.world);
            if (world != null && world.isChunkLoaded(key.x, key.z)) save(world.getChunkAt(key.x, key.z));
        }
        dirty.clear();
        for (ChunkKey key : List.copyOf(refresh)) {
            World world = Bukkit.getWorld(key.world);
            if (world != null && world.isChunkLoaded(key.x, key.z) && !world.getPlayers().isEmpty()) world.refreshChunk(key.x, key.z);
        }
        refresh.clear();
    }

    void clear() {
        flush();
        tints.clear();
        chunkIndex.clear();
    }

    private void save(Chunk chunk) {
        StringBuilder encoded = new StringBuilder();
        chunkIndex.getOrDefault(ChunkKey.of(chunk), Set.of()).stream().sorted().forEach(quart -> {
            Tint tint = tints.get(quart);
            encoded.append(quart.x).append('|').append(quart.y).append('|').append(quart.z).append('|').append(tint.original).append('|').append(tint.color).append('\n');
        });
        if (encoded.isEmpty()) chunk.getPersistentDataContainer().remove(persistence);
        else chunk.getPersistentDataContainer().set(persistence, PersistentDataType.STRING, encoded.toString());
    }

    private void putTint(Quart quart, Tint tint) {
        tints.put(quart, tint);
        chunkIndex.computeIfAbsent(new ChunkKey(quart.world, quart.x >> 4, quart.z >> 4), ignored -> new LinkedHashSet<>()).add(quart);
    }

    private void dropTint(Quart quart) {
        tints.remove(quart);
        ChunkKey key = new ChunkKey(quart.world, quart.x >> 4, quart.z >> 4);
        Set<Quart> indexed = chunkIndex.get(key);
        if (indexed != null) {
            indexed.remove(quart);
            if (indexed.isEmpty()) chunkIndex.remove(key);
        }
    }

    private void set(Quart quart, Biome biome) {
        World world = Bukkit.getWorld(quart.world);
        if (world == null || !world.isChunkLoaded(quart.x >> 4, quart.z >> 4)) return;
        if (world.getBiome(quart.x, quart.y, quart.z).getKey().equals(biome.getKey())) return;
        world.setBiome(quart.x, quart.y, quart.z, biome);
        refresh.add(new ChunkKey(quart.world, quart.x >> 4, quart.z >> 4));
    }

    private static NamespacedKey key(Template template, int color) {
        return new NamespacedKey("blocksadder", "liquid/" + hex(color).substring(1) + "/" + template.key.getNamespace() + "/" + template.key.getKey() + "__" + template.digest);
    }

    private static boolean generatedKey(NamespacedKey key) {
        return key.getNamespace().equals("blocksadder") && key.getKey().startsWith("liquid/");
    }

    private static NamespacedKey base(NamespacedKey key) {
        if (!generatedKey(key)) return key;
        String[] parts = key.getKey().split("/", 4);
        if (parts.length != 4) return key;
        int suffix = parts[3].lastIndexOf("__");
        String value = suffix < 0 ? parts[3] : parts[3].substring(0, suffix);
        NamespacedKey original = NamespacedKey.fromString(parts[2] + ":" + value);
        return original == null ? key : original;
    }

    private static String hex(int color) {
        return String.format(java.util.Locale.ROOT, "#%06x", color);
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Template(NamespacedKey key, JsonObject data, String digest) {}
    private static final class Tint {
        private final NamespacedKey original;
        private int color;
        private final Set<EnvironmentPosition> positions = new LinkedHashSet<>();
        private Tint(NamespacedKey original, int color) { this.original = original; this.color = color; }
    }
    private record ChunkKey(UUID world, int x, int z) {
        static ChunkKey of(Chunk chunk) { return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()); }
        static ChunkKey of(EnvironmentPosition position) { return new ChunkKey(position.world(), position.x() >> 4, position.z() >> 4); }
    }
    private record Quart(UUID world, int x, int y, int z) implements Comparable<Quart> {
        static Quart of(EnvironmentPosition position) { return new Quart(position.world(), position.x() & ~3, position.y() & ~3, position.z() & ~3); }
        boolean in(Chunk chunk) { return world.equals(chunk.getWorld().getUID()) && (x >> 4) == chunk.getX() && (z >> 4) == chunk.getZ(); }
        @Override public int compareTo(Quart other) { return java.util.Comparator.comparing(Quart::world).thenComparingInt(Quart::x).thenComparingInt(Quart::y).thenComparingInt(Quart::z).compare(this, other); }
    }
}
