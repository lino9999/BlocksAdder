package io.github.lino9999.blocksadder.environment;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

final class BiomeTemplateResolver implements AutoCloseable {
    private final JavaPlugin plugin;
    private final List<Source> sources = new ArrayList<>();
    private final long format;
    private final boolean authoritative;

    BiomeTemplateResolver(JavaPlugin plugin, Path datapacks) {
        this.plugin = plugin;
        format = format();
        Set<String> enabled = enabled();
        authoritative = enabled != null;
        if (!Files.isDirectory(datapacks, LinkOption.NOFOLLOW_LINKS)) return;
        try (var entries = Files.list(datapacks)) {
            for (Path path : entries.sorted().toList()) {
                if (Files.isSymbolicLink(path) || path.getFileName().toString().equals("blocksadder_liquids")) continue;
                if (enabled != null && !enabled.contains("file/" + path.getFileName())) continue;
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !path.getFileName().toString().endsWith(".zip")) continue;
                sources.add(new Source(path));
            }
        } catch (IOException exception) {
            close();
            throw new IllegalStateException("Could not inventory biome datapack templates.", exception);
        }
    }

    JsonObject read(NamespacedKey key) {
        String resource = "data/" + key.getNamespace() + "/worldgen/biome/" + key.getKey() + ".json";
        JsonObject override = null;
        List<String> owners = new ArrayList<>();
        for (Source source : sources) {
            JsonObject value = source.read(resource);
            if (value == null) continue;
            owners.add(source.path.getFileName().toString());
            override = value;
        }
        if (!owners.isEmpty() && (!authoritative || owners.size() > 1)) {
            plugin.getLogger().warning("Cannot resolve authoritative biome template priority for " + key + " in datapacks " + owners + ". Liquid coloring is unavailable for that biome.");
            return null;
        }
        if (override != null) return override;
        return classpath(resource);
    }

    private JsonObject classpath(String resource) {
        ClassLoader[] loaders = {Bukkit.getServer().getClass().getClassLoader(), plugin.getClass().getClassLoader()};
        for (ClassLoader loader : loaders) {
            try (InputStream stream = loader.getResourceAsStream(resource)) {
                if (stream != null) return parse(stream, resource);
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("Cannot read biome template " + resource + ".", exception);
            }
        }
        return null;
    }

    private long format() {
        JsonObject version = classpath("version.json");
        if (version == null || !version.has("pack_version")) throw new IllegalStateException("Server data-pack format metadata is unavailable.");
        JsonObject pack = version.getAsJsonObject("pack_version");
        return ((long) pack.get("data_major").getAsInt() << 32) | Integer.toUnsignedLong(pack.get("data_minor").getAsInt());
    }

    private Set<String> enabled() {
        try {
            Method factory;
            Method list;
            Method identity;
            Object manager;
            boolean spigot;
            try {
                factory = Bukkit.class.getMethod("getDataPackManager");
                manager = factory.invoke(null);
                list = factory.getReturnType().getMethod("getEnabledDataPacks", World.class);
                identity = Class.forName("org.bukkit.packs.DataPack").getMethod("getKey");
                spigot = true;
            } catch (NoSuchMethodException exception) {
                factory = Bukkit.class.getMethod("getDatapackManager");
                manager = factory.invoke(null);
                list = factory.getReturnType().getMethod("getEnabledPacks");
                identity = Class.forName("io.papermc.paper.datapack.DiscoveredDatapack").getMethod("getName");
                spigot = false;
            }
            Collection<?> packs = (Collection<?>) (spigot ? list.invoke(manager, Bukkit.getWorlds().getFirst()) : list.invoke(manager));
            Set<String> result = new HashSet<>();
            for (Object pack : packs) {
                Object key = identity.invoke(pack);
                if (key == null) return null;
                result.add(key instanceof NamespacedKey namespaced ? namespaced.getNamespace().equals("minecraft") ? namespaced.getKey() : namespaced.toString() : key.toString());
            }
            return Set.copyOf(result);
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("The public datapack inventory API is unavailable. Biome overrides will be treated conservatively.");
            return null;
        }
    }

    private static JsonObject parse(InputStream stream, String source) throws IOException {
        byte[] bytes = stream.readNBytes(1024 * 1024 + 1);
        if (bytes.length > 1024 * 1024) throw new IOException("Biome template exceeds one MiB: " + source);
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Override
    public void close() {
        for (Source source : sources) {
            if (source.zip == null) continue;
            try { source.zip.close(); }
            catch (IOException exception) { plugin.getLogger().warning("Could not close biome template archive: " + source.path.getFileName()); }
        }
        sources.clear();
    }

    private final class Source {
        private final Path path;
        private final ZipFile zip;
        private final List<String> overlays = new ArrayList<>();

        private Source(Path path) throws IOException {
            this.path = path;
            zip = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? new ZipFile(path.toFile()) : null;
            try {
                loadOverlays();
            } catch (IOException | RuntimeException exception) {
                if (zip != null) zip.close();
                throw exception;
            }
        }

        private void loadOverlays() throws IOException {
            JsonObject metadata = raw("pack.mcmeta");
            if (metadata == null || !metadata.has("overlays")) return;
            JsonObject configured = metadata.getAsJsonObject("overlays");
            if (!configured.has("entries")) return;
            for (JsonElement entry : configured.getAsJsonArray("entries")) {
                JsonObject overlay = entry.getAsJsonObject();
                String directory = overlay.get("directory").getAsString();
                if (directory.isBlank() || directory.startsWith("/") || directory.contains("\\") || java.util.Arrays.stream(directory.split("/", -1)).anyMatch(part -> !part.matches("[a-zA-Z0-9_.-]+") || part.equals(".") || part.equals(".."))) throw new IOException("Unsafe datapack overlay directory: " + directory);
                if (active(overlay)) overlays.add(directory + "/");
            }
        }

        private boolean active(JsonObject overlay) {
            if (overlay.has("min_format") && overlay.has("max_format")) return format >= version(overlay.get("min_format"), false) && format <= version(overlay.get("max_format"), true);
            JsonElement formats = overlay.get("formats");
            if (formats == null) return false;
            if (formats.isJsonObject()) return format >= version(formats.getAsJsonObject().get("min_inclusive"), false) && format <= version(formats.getAsJsonObject().get("max_inclusive"), true);
            if (formats.isJsonArray()) return format >= version(formats.getAsJsonArray().get(0), false) && format <= version(formats.getAsJsonArray().get(1), true);
            return format >= version(formats, false) && format <= version(formats, true);
        }

        private long version(JsonElement value, boolean maximum) {
            int major;
            long minor;
            if (value.isJsonArray()) {
                major = value.getAsJsonArray().get(0).getAsInt();
                minor = value.getAsJsonArray().size() > 1 ? value.getAsJsonArray().get(1).getAsInt() : maximum ? 0x7FFFFFFFL : 0;
            } else {
                major = value.getAsInt();
                minor = maximum ? 0x7FFFFFFFL : 0;
            }
            return ((long) major << 32) | minor;
        }

        private JsonObject read(String resource) {
            try {
                JsonObject value = raw(resource);
                for (String overlay : overlays) {
                    JsonObject replacement = raw(overlay + resource);
                    if (replacement != null) value = replacement;
                }
                return value;
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot read biome template from " + path.getFileName() + ".", exception);
            }
        }

        private JsonObject raw(String resource) throws IOException {
            if (zip != null) {
                var entry = zip.getEntry(resource);
                if (entry == null || entry.isDirectory()) return null;
                try (InputStream input = zip.getInputStream(entry)) { return parse(input, path.getFileName() + "/" + resource); }
            }
            Path target = path.resolve(resource).normalize();
            if (!target.startsWith(path.normalize()) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return null;
            try (InputStream input = Files.newInputStream(target)) { return parse(input, target.toString()); }
        }
    }
}
