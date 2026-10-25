package io.github.lino9999.blocksadder.command;

import io.github.lino9999.blocksadder.BlocksAdderPlugin;
import io.github.lino9999.blocksadder.content.ContentConfigurationEditor;
import io.github.lino9999.blocksadder.util.Text;
import org.bukkit.command.CommandSender;
import java.util.Map;
import java.util.WeakHashMap;

final class ConfigurationCommands {
    private record Pending(String request, ContentConfigurationEditor.Plan plan, long expires) {}
    private final ContentConfigurationEditor editor;
    private final Map<CommandSender, Pending> pending = new WeakHashMap<>();

    ConfigurationCommands(BlocksAdderPlugin plugin) { editor = new ContentConfigurationEditor(plugin); }

    void execute(CommandSender sender, String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Usage: /ba config <item|namespace|recipes> <disable|delete> <identifier>");
        String area = args[1].toLowerCase(java.util.Locale.ROOT);
        String action = args[2].toLowerCase(java.util.Locale.ROOT);
        if (!java.util.List.of("item", "namespace", "recipes").contains(area) || !java.util.List.of("disable", "delete").contains(action)) throw new IllegalArgumentException("Use item, namespace or recipes with disable or delete.");
        String permission = "blocksadder.config." + area + "." + action;
        if (!sender.hasPermission(permission)) throw new IllegalArgumentException("Missing permission: " + permission);
        long now = System.nanoTime();
        pending.entrySet().removeIf(entry -> now - entry.getValue().expires >= 0);
        String request = area + " " + action + " " + args[3];
        Pending previous = pending.remove(sender);
        if (previous != null && previous.request.equals(request)) {
            editor.apply(previous.plan);
            Text.send(sender, "Configuration updated and reloaded: " + previous.plan.definitions() + " definitions, " + previous.plan.recipes() + " recipes in " + previous.plan.files().size() + " files.");
            return;
        }
        var plan = editor.plan(area, action, args[3]);
        pending.put(sender, new Pending(request, plan, now + 30_000_000_000L));
        Text.send(sender, "Preview: " + action + " " + plan.definitions() + " definitions and " + plan.recipes() + " recipes in " + plan.files().size() + " files for " + args[3] + ".");
        Text.send(sender, "Run the same command again within 30 seconds to apply this preview. Invalid edits restore the previous files and content.");
    }
}
