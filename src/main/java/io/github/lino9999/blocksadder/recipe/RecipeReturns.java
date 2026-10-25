package io.github.lino9999.blocksadder.recipe;

import io.github.lino9999.blocksadder.item.ItemService;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

final class RecipeReturns {
    private final ItemService items;
    private final List<Rule> rules;
    private final String sound;
    private final SoundCategory category;
    private final float volume;
    private final float pitch;
    private final boolean stop;

    RecipeReturns(ConfigurationSection section, ItemService items, Function<String, ItemStack> resolve) {
        this.items = items;
        List<Rule> parsed = new ArrayList<>();
        if (section != null) {
            ConfigurationSection replace = section.getConfigurationSection("replace");
            if (replace != null) for (String name : replace.getKeys(false)) parsed.add(new Rule(resolve.apply(name), resolve.apply(replace.getString(name)), 0));
            for (String action : List.of("decrement_durability", "increment_durability")) {
                ConfigurationSection entries = section.getConfigurationSection(action);
                if (entries == null) continue;
                for (String name : entries.getKeys(false)) {
                    ItemStack input = resolve.apply(entries.getString(name + ".item"));
                    int amount = entries.getInt(name + ".amount", 1);
                    if (amount < 1 || amount > 1000000 || !(input.getItemMeta() instanceof Damageable damageable) || maximum(input, damageable) < 1) throw new IllegalArgumentException("Return-item durability rules require a damageable item and an amount between 1 and 1000000.");
                    parsed.add(new Rule(input, null, action.startsWith("decrement") ? amount : -amount));
                }
            }
        }
        for (int a = 0; a < parsed.size(); a++) for (int b = a + 1; b < parsed.size(); b++) if (matches(parsed.get(a).input, parsed.get(b).input)) throw new IllegalArgumentException("A recipe ingredient cannot have overlapping return-item rules.");
        rules = List.copyOf(parsed);
        sound = normalizeSound(section == null ? "" : section.getString("play_sound.name", ""));
        if (!sound.isBlank() && !sound.matches("[a-z0-9_.:/-]+")) throw new IllegalArgumentException("Invalid return-item sound identifier: " + sound);
        String channel = section == null ? "PLAYERS" : section.getString("play_sound.category", "PLAYERS");
        category = SoundCategory.valueOf(channel.toUpperCase(Locale.ROOT));
        volume = section == null ? 0.3f : (float) section.getDouble("play_sound.volume", 0.3);
        pitch = section == null ? 1 : (float) section.getDouble("play_sound.pitch", 1);
        stop = section == null || section.getBoolean("play_sound.stop_previous", true);
        if (!Float.isFinite(volume) || volume < 0 || volume > 16 || !Float.isFinite(pitch) || pitch < 0 || pitch > 2) throw new IllegalArgumentException("Return-item sound volume must be 0-16 and pitch 0-2.");
    }

    boolean active() { return !rules.isEmpty() || !sound.isBlank(); }
    boolean reusable(ItemStack stack) { return rules.stream().anyMatch(rule -> rule.damage != 0 && matches(rule.input, stack)); }

    ItemStack remainder(ItemStack input) {
        if (input == null || input.getType().isAir()) return new ItemStack(Material.AIR);
        for (Rule rule : rules) {
            if (!matches(rule.input, input)) continue;
            if (rule.replacement != null) return rule.replacement.clone();
            ItemStack returned = input.clone();
            returned.setAmount(1);
            Damageable meta = (Damageable) returned.getItemMeta();
            int maximum = maximum(returned, meta);
            long damage = (long) meta.getDamage() + rule.damage;
            if (damage >= maximum) return new ItemStack(Material.AIR);
            meta.setDamage((int) Math.max(0, damage));
            returned.setItemMeta(meta);
            return returned;
        }
        Material remaining = input.getType().getCraftingRemainingItem();
        return new ItemStack(remaining == null ? Material.AIR : remaining);
    }

    void sound(Player player) {
        if (sound.isBlank()) return;
        if (stop) player.stopSound(sound, category);
        player.playSound(player.getLocation(), sound, category, volume, pitch);
    }

    private static String normalizeSound(String value) {
        if (value.isBlank() || value.equals(value.toLowerCase(Locale.ROOT))) return value;
        for (var candidate : org.bukkit.Registry.SOUNDS) {
            var key = ((org.bukkit.Keyed) candidate).getKey();
            if (key.getKey().replace('.', '_').toUpperCase(Locale.ROOT).equals(value)) return key.toString();
        }
        throw new IllegalArgumentException("Unknown legacy sound name: " + value);
    }

    private boolean matches(ItemStack expected, ItemStack actual) {
        if (actual == null || actual.getType().isAir()) return false;
        var identity = items.identify(expected);
        return identity.isPresent() ? identity.equals(items.identify(actual)) : items.identify(actual).isEmpty() && expected.getType() == actual.getType();
    }

    static int maximum(ItemStack stack, Damageable meta) { return meta.hasMaxDamage() ? meta.getMaxDamage() : stack.getType().getMaxDurability(); }
    private record Rule(ItemStack input, ItemStack replacement, int damage) {}
}
