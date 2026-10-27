package io.github.lino9999.blocksadder.pack;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class HudSpaceFont {
    public static final String FONT = "blocksadder:layout";
    public static final String PATH = "assets/blocksadder/font/layout.json";
    public static final int MAX_SHIFT = 1_048_576;
    private static final int FIRST_CHARACTER = 0xF1000;
    private static final int POWERS = 21;

    private HudSpaceFont() {}

    public static BaseComponent shift(int pixels) {
        if (pixels < -MAX_SHIFT || pixels > MAX_SHIFT) throw new IllegalArgumentException("HUD pixel shifts must be between -1048576 and 1048576.");
        int remaining = Math.abs(pixels);
        StringBuilder text = new StringBuilder();
        for (int power = 0; power < POWERS; power++) {
            if ((remaining & (1 << power)) != 0) text.appendCodePoint(FIRST_CHARACTER + power + (pixels < 0 ? POWERS : 0));
        }
        TextComponent result = new TextComponent(text.toString());
        result.setFont(FONT);
        result.setColor(ChatColor.WHITE);
        result.setBold(false);
        result.setItalic(false);
        result.setUnderlined(false);
        result.setStrikethrough(false);
        result.setObfuscated(false);
        return result;
    }

    public static Map<String, Object> document() {
        Map<String, Integer> advances = new TreeMap<>();
        for (int power = 0; power < POWERS; power++) {
            advances.put(new String(Character.toChars(FIRST_CHARACTER + power)), 1 << power);
            advances.put(new String(Character.toChars(FIRST_CHARACTER + POWERS + power)), -(1 << power));
        }
        return Map.of("providers", List.of(Map.of("type", "space", "advances", Map.copyOf(advances))));
    }
}
