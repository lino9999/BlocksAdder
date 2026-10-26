package io.github.lino9999.blocksadder.pack;

import io.github.lino9999.blocksadder.content.ContentId;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.util.Json;
import org.bukkit.configuration.ConfigurationSection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PackBuilder {
    private static final Set<String> EXTENSIONS = Set.of("json", "png", "ogg", "ttf", "otf", "mcmeta", "vsh", "fsh", "glsl", "txt");
    private static final Set<String> ASSET_DIRECTORIES = Set.of("textures", "models", "items", "font", "sounds", "equipment", "atlases", "blockstates", "shaders", "lang", "particles");
    private final Path root;

    public PackBuilder(Path root) {
        this.root = root;
    }

    public PackArtifact build(ContentRegistry registry, long maxBytes) throws IOException {
        return build(registry, maxBytes, false);
    }

    public PackArtifact build(ContentRegistry registry, long maxBytes, boolean rewriteSounds) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        add(entries, "pack.mcmeta", Map.of("pack", Map.of("description", "BlocksAdder custom content by Lino", "min_format", 75, "max_format", 97)));
        for (var item : registry.items()) {
            ContentId model = ContentId.parse(item.model());
            String texture = item.options().getString("texture", "minecraft:item/" + item.material().name().toLowerCase(java.util.Locale.ROOT));
            ContentId.parse(texture);
            String generated = "assets/" + model.namespace() + "/models/item/" + model.value() + ".json";
            String itemModel = "assets/" + model.namespace() + "/items/" + model.value() + ".json";
            Map<String, String> layers = new LinkedHashMap<>();
            List<String> textures = item.options().getStringList("textures");
            if (textures.isEmpty()) layers.put("layer0", texture);
            else for (int layer = 0; layer < textures.size(); layer++) {
                ContentId.parse(textures.get(layer));
                layers.put("layer" + layer, textures.get(layer));
            }
            ConfigurationSection textureMap = item.options().getConfigurationSection("model-textures");
            if (textureMap != null) {
                layers.clear();
                for (String key : textureMap.getKeys(false)) {
                    String value = textureMap.getString(key);
                    ContentId.parse(value);
                    layers.put(key, value);
                }
            }
            Object definition = Map.of("parent", item.options().getString("model-parent", "minecraft:item/generated"), "textures", layers);
            if (item.options().contains("block")) {
                String blockTexture = item.options().getString("block.texture", texture);
                ContentId.parse(blockTexture);
                definition = Map.of("parent", "minecraft:block/cube_all", "textures", Map.of("all", blockTexture));
            }
            add(entries, generated, definition);
            String source = item.options().getString("model-source", model.namespace() + ":item/" + model.value());
            ContentId.parse(source);
            add(entries, itemModel, Map.of("model", Map.of("type", "minecraft:model", "model", source)));
        }
        Map<String, List<Object>> fonts = new TreeMap<>();
        new TreeMap<>(registry.sections("font_images")).forEach((id, config) -> {
            String character = config.getString("character", "");
            String texture = config.getString("texture");
            if (character.isEmpty() || texture == null) return;
            ContentId.parse(texture);
            String font = config.getString("font", "minecraft:default");
            ContentId.parse(font);
            fonts.computeIfAbsent(font, ignored -> new ArrayList<>()).add(Map.of("type", "bitmap", "file", texture.endsWith(".png") ? texture : texture + ".png",
                    "ascent", config.getInt("ascent", 8), "height", config.getInt("height", 8), "chars", List.of(character)));
        });
        fonts.forEach((id, providers) -> {
            if (id.equals("minecraft:default")) {
                providers.add(Map.of("type", "reference", "id", "minecraft:include/space"));
                providers.add(Map.of("type", "reference", "id", "minecraft:include/default"));
                providers.add(Map.of("type", "reference", "id", "minecraft:include/unifont"));
            }
            ContentId key = ContentId.parse(id);
            add(entries, "assets/" + key.namespace() + "/font/" + key.value() + ".json", Map.of("providers", providers));
        });
        Map<String, Map<String, Object>> sounds = new TreeMap<>();
        registry.sections("sounds").forEach((id, config) -> {
            ContentId key = ContentId.parse(id);
            List<String> files = config.getStringList("files");
            Map<String, Object> sound = new LinkedHashMap<>();
            sound.put("sounds", files);
            if (config.contains("subtitle")) sound.put("subtitle", config.getString("subtitle"));
            sounds.computeIfAbsent(key.namespace(), ignored -> new TreeMap<>()).put(key.value(), sound);
        });
        sounds.forEach((namespace, definitions) -> add(entries, "assets/" + namespace + "/sounds.json", definitions));
        io.github.lino9999.blocksadder.model.ModelAssetCompiler.append(entries, registry, root);
        io.github.lino9999.blocksadder.blockstate.NativeBlockAssetCompiler.append(entries, registry, root);
        io.github.lino9999.blocksadder.environment.FireAssetCompiler.appendModels(entries, registry, root);
        copyAssets(entries, root.resolve("contents"), maxBytes);
        copyAssets(entries, root.resolve("resourcepack"), maxBytes);
        io.github.lino9999.blocksadder.crop.CropCardAssets.append(entries, registry);
        EquipmentAssetCompiler.append(entries, registry);
        io.github.lino9999.blocksadder.model.PlayerEquipmentModels.append(entries, registry);
        io.github.lino9999.blocksadder.model.PlayerSkinAssets.append(entries, registry, root);
        io.github.lino9999.blocksadder.sound.SoundAssetCompiler.append(entries, registry, rewriteSounds);
        io.github.lino9999.blocksadder.blockstate.NativeBlockAssetCompiler.appendBlockstates(entries, registry, root);
        io.github.lino9999.blocksadder.environment.FireAssetCompiler.appendBlockstates(entries, registry, root);
        long size = entries.values().stream().mapToLong(bytes -> bytes.length).sum();
        if (size > maxBytes) throw new IOException("Resource pack exceeds the configured uncompressed size limit.");
        Path output = root.resolve("pack");
        Files.createDirectories(output);
        Path temporary = output.resolve(".BlocksAdder.zip.tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
                zip.setLevel(6);
                for (var entry : entries.entrySet()) {
                    ZipEntry member = new ZipEntry(entry.getKey());
                    member.setTime(0);
                    zip.putNextEntry(member);
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }
            Path target = output.resolve("BlocksAdder.zip");
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            byte[] bytes = Files.readAllBytes(target);
            return new PackArtifact(target, digest("SHA-1", bytes), digest("SHA-256", bytes), bytes.length, entries.size());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void copyAssets(Map<String, byte[]> entries, Path directory, long maxBytes) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        long size = entries.values().stream().mapToLong(bytes -> bytes.length).sum();
        try (var stream = Files.walk(directory)) {
            for (Path path : stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                if (!path.toRealPath().startsWith(directory.toRealPath())) throw new IOException("Asset escapes resource directory.");
                String relative = directory.relativize(path).toString().replace('\\', '/');
                int marker = relative.indexOf("resourcepack/");
                if (directory.endsWith("contents")) {
                    String[] parts = relative.split("/", 3);
                    if (parts.length < 3) continue;
                    String namespace = parts[0];
                    new ContentId(namespace, "validation");
                    if (marker >= 0) {
                        relative = relative.substring(marker + "resourcepack/".length());
                        if (!relative.startsWith("assets/") && ASSET_DIRECTORIES.contains(relative.split("/", 2)[0])) relative = "assets/" + namespace + "/" + relative;
                    } else {
                        if (!ASSET_DIRECTORIES.contains(parts[1])) continue;
                        relative = "assets/" + namespace + "/" + parts[1] + "/" + parts[2];
                    }
                }
                if (!relative.startsWith("assets/") && !relative.equals("pack.png")) continue;
                if (relative.contains("..") || relative.contains(":")) throw new IOException("Invalid resource pack member: " + relative);
                String extension = relative.substring(relative.lastIndexOf('.') + 1);
                if (!EXTENSIONS.contains(extension)) continue;
                long length = Files.size(path);
                byte[] previous = entries.get(relative);
                size += length - (previous == null ? 0 : previous.length);
                if (length > maxBytes || size > maxBytes) throw new IOException("Resource pack exceeds the configured size limit.");
                entries.put(relative, Files.readAllBytes(path));
            }
        }
    }

    private static void add(Map<String, byte[]> entries, String path, Object content) {
        byte[] value = Json.write(content).getBytes(StandardCharsets.UTF_8);
        byte[] previous = entries.putIfAbsent(path, value);
        if (previous != null && !java.util.Arrays.equals(previous, value)) throw new IllegalArgumentException("Conflicting generated asset: " + path);
    }

    private static String digest(String algorithm, byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
