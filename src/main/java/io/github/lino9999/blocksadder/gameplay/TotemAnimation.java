package io.github.lino9999.blocksadder.gameplay;

import org.bukkit.Bukkit;
import org.bukkit.EntityEffect;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class TotemAnimation {
    private static final Set<UUID> ACTIVE = new HashSet<>();
    private static final ThreadLocal<Overlay> OVERLAY = new ThreadLocal<>();

    private TotemAnimation() {}

    public static Display prepare(ItemStack source) {
        thread();
        if (source == null || source.getType().isAir()) throw new IllegalArgumentException("Totem display requires a nonempty item.");
        ItemStack expected = source.clone();
        expected.setAmount(1);
        String patch = expected.getItemMeta().getAsComponentString();
        String protectedPatch = protectivePatch(patch);
        String material = expected.getType().getKey().toString();
        var factory = Bukkit.getItemFactory();
        ItemStack roundTrip = factory.createItemStack(material + patch);
        if (!expected.isSimilar(roundTrip)) throw new IllegalArgumentException("The public item codec cannot preserve this totem display's components.");
        ItemStack display = factory.createItemStack(material + protectedPatch);
        display.setAmount(1);
        return new Display(display);
    }

    public static boolean play(Player player) { return play(player, null, () -> true); }

    private static boolean play(Player player, ItemStack display, BooleanSupplier source) {
        thread();
        UUID id = player.getUniqueId();
        if (!player.isOnline() || player.isDead() || !ACTIVE.add(id)) return false;
        boolean spoofed = false;
        try {
            if (!source.getAsBoolean()) return false;
            if (display != null) {
                spoofed = true;
                Overlay previous = OVERLAY.get();
                OVERLAY.set(new Overlay(id, display));
                try { player.sendEquipmentChange(player, EquipmentSlot.HAND, display.clone()); }
                finally { if (previous == null) OVERLAY.remove(); else OVERLAY.set(previous); }
                if (!player.isOnline() || player.isDead() || !source.getAsBoolean()) return false;
            }
            player.playEffect(EntityEffect.TOTEM_RESURRECT);
            return true;
        } finally {
            try {
                if (spoofed && player.isOnline()) player.sendEquipmentChange(player, EquipmentSlot.HAND, player.getInventory().getItemInMainHand().clone());
            } finally { ACTIVE.remove(id); }
        }
    }

    public static boolean temporaryDisplay(Player viewer, ItemStack item) {
        Overlay overlay = OVERLAY.get();
        return overlay != null && overlay.player().equals(viewer.getUniqueId()) && overlay.item().equals(item);
    }

    private record Overlay(UUID player, ItemStack item) {}

    private static String protectivePatch(String input) {
        if (input.length() > 1048576 || input.getBytes(StandardCharsets.UTF_8).length > 1048576) throw new IllegalArgumentException("Totem component data cannot exceed 1 MiB.");
        if (input.length() < 2 || input.charAt(0) != '[' || input.charAt(input.length() - 1) != ']') throw new IllegalArgumentException("The public item codec returned an invalid component patch.");
        List<String> entries = new ArrayList<>();
        int start = 1, depth = 0;
        char quote = 0;
        boolean escaped = false;
        char[] closing = new char[64];
        for (int index = 1; index < input.length() - 1; index++) {
            char current = input.charAt(index);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == quote) quote = 0;
                continue;
            }
            if (current == '\'' || current == '"') quote = current;
            else if (current == '[' || current == '{') {
                if (depth == closing.length) throw new IllegalArgumentException("Totem component nesting cannot exceed 64 levels.");
                closing[depth++] = current == '[' ? ']' : '}';
            } else if (current == ']' || current == '}') {
                if (depth == 0 || closing[--depth] != current) throw new IllegalArgumentException("The public item codec returned unbalanced component data.");
            } else if (current == ',' && depth == 0) {
                entry(input.substring(start, index), entries);
                start = index + 1;
            }
        }
        if (quote != 0 || depth != 0) throw new IllegalArgumentException("The public item codec returned unfinished component data.");
        if (start < input.length() - 1) entry(input.substring(start, input.length() - 1), entries);
        else if (start != 1) throw new IllegalArgumentException("The public item codec returned a trailing component separator.");
        if (entries.size() >= 128) throw new IllegalArgumentException("Totem display cannot exceed 128 component entries.");
        entries.add("minecraft:death_protection={death_effects:[]}");
        return "[" + String.join(",", entries) + "]";
    }

    private static void entry(String input, List<String> entries) {
        if (entries.size() >= 128) throw new IllegalArgumentException("Totem display cannot exceed 128 component entries.");
        String value = input.trim();
        int assignment = value.indexOf('=');
        boolean removed = value.startsWith("!");
        String key = (assignment < 0 ? value : value.substring(0, assignment)).trim();
        if (removed) key = key.substring(1);
        NamespacedKey parsed = NamespacedKey.fromString(key);
        if (parsed == null || value.isEmpty() || assignment < 0 && !removed) throw new IllegalArgumentException("The public item codec returned an invalid component entry.");
        if (!parsed.equals(NamespacedKey.minecraft("death_protection"))) entries.add(value);
    }

    private static void thread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Totem animation requires the server thread.");
    }

    public static final class Display {
        private final ItemStack item;
        private Display(ItemStack item) { this.item = item; }
        public boolean play(Player player, BooleanSupplier source) { return TotemAnimation.play(player, item, source); }
    }
}
