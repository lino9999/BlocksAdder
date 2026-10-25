package io.github.lino9999.blocksadder.util;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

public final class Text {
    private Text() {}

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    public static void send(CommandSender sender, String text) {
        sender.sendMessage(color("&8[&bBlocksAdder&8] &7" + text));
    }

    public static String plain(String text) {
        return ChatColor.stripColor(color(text));
    }
}
