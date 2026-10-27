package io.github.lino9999.blocksadder.integration.placeholder;

import java.util.function.BooleanSupplier;
import java.util.function.Function;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class CommandPlaceholderResolver {
    private CommandPlaceholderResolver() {}

    public static Function<String, String> open(
            JavaPlugin owner, Player player, BooleanSupplier admitted) {
        try {
            if (!Bukkit.isPrimaryThread() || owner == null || player == null || admitted == null)
                return null;
            org.bukkit.plugin.Plugin actual =
                    Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
            PlaceholderAPIPlugin provider = PlaceholderAPIPlugin.getInstance();
            if (provider == null || actual != provider || !ready(owner, provider, admitted))
                return null;
            return token -> {
                try {
                    if (!Bukkit.isPrimaryThread()
                            || token == null
                            || !ready(owner, provider, admitted)) return null;
                    String result = PlaceholderAPI.setPlaceholders(player, token);
                    if (!ready(owner, provider, admitted)
                            || result == null
                            || token.equals(result)) return null;
                    return result;
                } catch (RuntimeException | LinkageError exception) {
                    return null;
                }
            };
        } catch (RuntimeException | LinkageError exception) {
            return null;
        }
    }

    private static boolean ready(
            JavaPlugin owner, PlaceholderAPIPlugin provider, BooleanSupplier admitted) {
        if (!current(owner, provider) || !admitted.getAsBoolean()) return false;
        return current(owner, provider);
    }

    private static boolean current(JavaPlugin owner, PlaceholderAPIPlugin provider) {
        return Bukkit.isPrimaryThread()
                && owner.isEnabled()
                && Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == provider
                && provider.isEnabled()
                && PlaceholderAPIPlugin.getInstance() == provider;
    }
}
