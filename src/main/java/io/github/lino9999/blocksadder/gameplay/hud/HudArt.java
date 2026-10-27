package io.github.lino9999.blocksadder.gameplay.hud;

import io.github.lino9999.blocksadder.content.ContentId;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.pack.HudSpaceFont;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.configuration.ConfigurationSection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class HudArt {
    private final String mode;
    private final int slots;
    private final boolean left;
    private final List<Glyph> images;
    private final Map<String, Choice> catalog;
    private final String label;

    private HudArt(String mode, int slots, boolean left, List<Glyph> images, Map<String, Choice> catalog, String label) {
        this.mode = mode;
        this.slots = slots;
        this.left = left;
        this.images = List.copyOf(images);
        this.catalog = Map.copyOf(catalog);
        this.label = label;
    }

    public static HudArt parse(ConfigurationSection art, ContentRegistry registry, String label) {
        if (art == null || registry == null || label == null) throw new IllegalArgumentException("HUD art needs a configuration, registry and label.");
        String mode = text(art, "mode", "pips", label).toLowerCase(Locale.ROOT);
        Set<String> fields = switch (mode) {
            case "pips" -> Set.of("mode", "slots", "full", "half", "empty", "direction");
            case "frames" -> Set.of("mode", "frames");
            case "custom" -> Set.of("mode");
            default -> throw invalid(label, "art mode must be pips, frames or custom.");
        };
        for (String field : art.getKeys(false)) if (!fields.contains(field)) throw invalid(label, "unknown art field: " + field);
        Map<String, Choice> catalog = new HashMap<>();
        registry.sections("font_images").forEach((id, definition) -> {
            try { catalog.put(id, new Choice(glyph(id, definition), null)); }
            catch (IllegalArgumentException exception) { catalog.put(id, new Choice(null, exception.getMessage())); }
        });
        HudArt parsed = new HudArt(mode, mode.equals("pips") ? integer(art, "slots", 10, 1, 64, label) : 0,
                mode.equals("pips") && direction(art, label), List.of(), catalog, label);
        List<Glyph> images = new ArrayList<>();
        if (mode.equals("pips")) {
            for (String field : List.of("full", "half", "empty")) images.add(parsed.resolve(text(art, field, null, label)));
        } else if (mode.equals("frames")) {
            Object raw = art.get("frames");
            if (!(raw instanceof List<?> frames) || frames.isEmpty() || frames.size() > 256) throw invalid(label, "art frames must contain between one and 256 glyph identifiers.");
            for (Object frame : frames) {
                if (!(frame instanceof String id)) throw invalid(label, "art frames must contain glyph identifiers.");
                images.add(parsed.resolve(id));
            }
        }
        return new HudArt(mode, parsed.slots, parsed.left, images, catalog, label);
    }

    public boolean custom() { return mode.equals("custom"); }
    public int fixedGlyphs() { return mode.equals("pips") ? slots : mode.equals("frames") ? 1 : 0; }

    public List<String> validateGlyphs(List<String> glyphs) {
        if (glyphs == null || glyphs.size() > 64) throw invalid(label, "custom art must contain at most 64 glyph identifiers.");
        List<String> canonical = new ArrayList<>(glyphs.size());
        for (String id : glyphs) canonical.add(resolve(id).id());
        return List.copyOf(canonical);
    }

    public Draw render(double value, double min, double max, List<String> customGlyphs) {
        if (!Double.isFinite(value) || !Double.isFinite(min) || !Double.isFinite(max) || max <= min) throw invalid(label, "art values need finite numbers and an increasing range.");
        double bounded = Math.clamp(value, min, max);
        List<Glyph> selected = new ArrayList<>();
        if (mode.equals("pips")) {
            double filled = MeterMath.progress(bounded, min, max) * slots;
            int complete = (int) Math.floor(filled);
            for (int slot = 0; slot < slots; slot++) selected.add(images.get(slot < complete ? 0 : slot == complete && filled > complete ? 1 : 2));
            if (left) Collections.reverse(selected);
        } else if (mode.equals("frames")) {
            double offset = bounded - min;
            int index = offset >= images.size() - 1 ? images.size() - 1 : (int) Math.floor(offset);
            selected.add(images.get(index));
        } else {
            for (String id : validateGlyphs(customGlyphs)) selected.add(resolve(id));
        }
        List<BaseComponent> components = new ArrayList<>(selected.size());
        int width = 0;
        for (Glyph glyph : selected) {
            TextComponent component = new TextComponent(glyph.character());
            component.setFont(glyph.font());
            component.setColor(ChatColor.WHITE);
            component.setBold(false);
            component.setItalic(false);
            component.setUnderlined(false);
            component.setStrikethrough(false);
            component.setObfuscated(false);
            components.add(component);
            width += glyph.advance();
        }
        return new Draw(components, width);
    }

    private Glyph resolve(String requested) {
        if (requested == null || requested.isBlank() || requested.length() > 256) throw invalid(label, "art needs a valid glyph identifier.");
        String id = requested;
        if (requested.contains(":")) ContentId.parse(requested);
        else {
            new ContentId("blocksadder", requested);
            List<String> matches = catalog.keySet().stream().filter(candidate -> candidate.endsWith(":" + requested)).limit(2).toList();
            if (matches.size() != 1) throw invalid(label, "glyph identifier is missing or ambiguous: " + requested);
            id = matches.getFirst();
        }
        Choice choice = catalog.get(id);
        if (choice == null) throw invalid(label, "unknown art glyph: " + requested);
        if (choice.failure() != null) throw invalid(label, choice.failure());
        return choice.glyph();
    }

    private static Glyph glyph(String id, ConfigurationSection definition) {
        String character = text(definition, "character", null, "Glyph " + id);
        int codepoint = character.codePointAt(0);
        if (character.codePointCount(0, character.length()) != 1 || codepoint >= 0xD800 && codepoint <= 0xDFFF
                || Character.isISOControl(codepoint) || codepoint == 0xA7) throw invalid("Glyph " + id, "HUD art needs one printable Unicode character.");
        String font = text(definition, "font", "minecraft:default", "Glyph " + id);
        ContentId.parse(font);
        if (font.equals(HudSpaceFont.FONT)) throw invalid("Glyph " + id, "the HUD layout font is reserved.");
        int advance = integer(definition, "advance", null, 0, 512, "Glyph " + id);
        return new Glyph(id, character, font, advance);
    }

    private static boolean direction(ConfigurationSection art, String label) {
        String direction = text(art, "direction", "right", label).toLowerCase(Locale.ROOT);
        if (!direction.equals("left") && !direction.equals("right")) throw invalid(label, "art direction must be left or right.");
        return direction.equals("left");
    }

    private static String text(ConfigurationSection section, String field, String fallback, String label) {
        Object raw = section.get(field);
        if (raw == null && fallback != null) return fallback;
        if (!(raw instanceof String value) || value.isEmpty() || value.length() > 256) throw invalid(label, field + " must be a nonempty string of at most 256 characters.");
        return value;
    }

    private static int integer(ConfigurationSection section, String field, Integer fallback, int min, int max, String label) {
        Object raw = section.get(field);
        if (raw == null && fallback != null) return fallback;
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() != Math.rint(number.doubleValue())
                || number.doubleValue() < min || number.doubleValue() > max) throw invalid(label, field + " must be an integer between " + min + " and " + max + ".");
        return number.intValue();
    }

    private static IllegalArgumentException invalid(String label, String message) { return new IllegalArgumentException(label + ": " + message); }

    private record Glyph(String id, String character, String font, int advance) {}
    private record Choice(Glyph glyph, String failure) {}

    public record Draw(List<BaseComponent> components, int width) {
        public Draw {
            if (components == null || width < 0) throw new IllegalArgumentException("HUD drawings need components and a nonnegative width.");
            components = components.stream().map(BaseComponent::duplicate).toList();
        }

        @Override public List<BaseComponent> components() { return components.stream().map(BaseComponent::duplicate).toList(); }
    }
}
