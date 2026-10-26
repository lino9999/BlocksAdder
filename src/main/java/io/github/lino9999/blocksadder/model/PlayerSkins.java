package io.github.lino9999.blocksadder.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.lino9999.blocksadder.util.Json;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import java.util.UUID;

public final class PlayerSkins {
    private static final List<String> DEFAULTS = List.of("alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri");
    private PlayerSkins() {}
    public record Source(URL url, boolean slim) {}

    public static Source parse(String input) {
        if (input == null || input.isBlank() || input.length() > 16384) throw new IllegalArgumentException("A player skin needs a bounded Minecraft texture URL or Base64 texture property.");
        String value = input.trim();
        boolean slim = false;
        if (!value.startsWith("https://")) {
            try {
                byte[] decoded = Base64.getDecoder().decode(value);
                if (decoded.length > 12288) throw new IllegalArgumentException();
                JsonObject skin = JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("textures").getAsJsonObject("SKIN");
                value = skin.get("url").getAsString();
                JsonObject metadata = skin.getAsJsonObject("metadata");
                if (metadata != null && metadata.has("model")) {
                    String model = metadata.get("model").getAsString();
                    if (!model.equals("slim") && !model.equals("classic")) throw new IllegalArgumentException();
                    slim = model.equals("slim");
                }
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid Base64 Minecraft skin texture property.");
            }
        }
        return new Source(url(value), slim);
    }

    private static URL url(String value) {
        try {
            URI uri = URI.create(value);
            if (!uri.getScheme().equals("https") || !"textures.minecraft.net".equals(uri.getHost()) || uri.getPort() != -1 || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || !uri.getRawPath().matches("/texture/[a-fA-F0-9]{32,128}")) throw new IllegalArgumentException();
            return uri.toURL();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Player skins require an HTTPS textures.minecraft.net texture URL without a port, credentials, query or fragment.");
        }
    }

    public static PlayerProfile profile(Source source) {
        PlayerProfile profile = Bukkit.createPlayerProfile(UUID.nameUUIDFromBytes((source.url().toString() + ":" + source.slim()).getBytes(StandardCharsets.UTF_8)));
        PlayerTextures textures = profile.getTextures();
        textures.setSkin(source.url(), source.slim() ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC);
        profile.setTextures(textures);
        return profile;
    }

    public static PlayerProfile checked(PlayerProfile input) {
        PlayerProfile profile = input.clone();
        URL skin = profile.getTextures().getSkin();
        if (skin != null) url(skin.toString().replaceFirst("^http://", "https://"));
        if (profile.getUniqueId() == null) {
            if (skin == null) throw new IllegalArgumentException("A default player-skin profile needs a UUID; name-only skin lookup is not supported.");
            return profile(new Source(url(skin.toString().replaceFirst("^http://", "https://")), slim(profile)));
        }
        return profile;
    }

    public static boolean slim(PlayerProfile profile) {
        return profile.getTextures().getSkin() == null && profile.getUniqueId() != null ? defaultSlim(profile.getUniqueId()) : profile.getTextures().getSkinModel() == PlayerTextures.SkinModel.SLIM;
    }

    public static boolean defaultSlim(UUID uuid) { return Math.floorMod(uuid.hashCode(), 18) < 9; }

    public static String defaultTexture(UUID uuid) {
        int index = Math.floorMod(uuid.hashCode(), 18);
        return "player/" + (index < 9 ? "slim/" : "wide/") + DEFAULTS.get(index % 9);
    }

    static PlayerProfile restore(String state) {
        if (!state.startsWith("default:")) return profile(parse(state));
        try {
            UUID uuid = UUID.fromString(state.substring(8));
            if (!uuid.toString().equals(state.substring(8))) throw new IllegalArgumentException();
            return Bukkit.createPlayerProfile(uuid);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid persisted default player-skin profile.");
        }
    }

    public static String serialize(PlayerProfile profile) {
        URL skin = profile.getTextures().getSkin();
        if (skin == null) {
            if (profile.getUniqueId() == null) throw new IllegalArgumentException("A default player-skin profile needs a UUID to persist.");
            return "default:" + profile.getUniqueId();
        }
        String value = skin.toString().replaceFirst("^http://", "https://");
        url(value);
        Map<String, Object> data = Map.of("textures", Map.of("SKIN", Map.of("url", value, "metadata", Map.of("model", slim(profile) ? "slim" : "classic"))));
        return Base64.getEncoder().encodeToString(Json.write(data).getBytes(StandardCharsets.UTF_8));
    }

    public static ItemStack item(PlayerProfile profile) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (profile.getTextures().getSkin() == null) meta.setItemModel(NamespacedKey.fromString("blocksadder:skin/default/" + defaultTexture(profile.getUniqueId()).substring(7)));
        else {
            meta.setOwnerProfile(profile.clone());
            meta.setItemModel(NamespacedKey.fromString("blocksadder:skin/player"));
        }
        item.setItemMeta(meta);
        return item;
    }
}
