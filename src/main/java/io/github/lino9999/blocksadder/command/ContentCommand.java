package io.github.lino9999.blocksadder.command;

import io.github.lino9999.blocksadder.BlocksAdderPlugin;
import io.github.lino9999.blocksadder.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ContentCommand implements TabExecutor {
    private static final Map<String, String> SHORTCUTS = Map.of("baget", "get", "bagive", "give", "bapack", "pack", "babrowse", "browse", "bareload", "reload", "barecipe", "recipe", "bainspect", "inspect");
    private static final List<String> SUBCOMMANDS = List.of("help", "info", "browse", "get", "give", "drop", "remove", "inspect", "modeldata", "repair", "rename", "durability", "color", "reload", "validate", "pack", "block", "recipe", "glyph", "stat", "hud", "tree", "entity", "emote", "sound", "totem", "model", "disguise", "undisguise", "liquid", "fire", "vehicle", "config", "plants");
    private final BlocksAdderPlugin plugin;
    private final CatalogMenu menus;
    private final AdvancedContentCommands advanced;
    private final ConfigurationCommands configuration;

    public ContentCommand(BlocksAdderPlugin plugin, CatalogMenu menus) {
        this.plugin = plugin;
        this.menus = menus;
        this.advanced = new AdvancedContentCommands(plugin);
        this.configuration = new ConfigurationCommands(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] input) {
        String[] args = normalize(command.getName(), input);
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            if (!SUBCOMMANDS.contains(action)) throw new IllegalArgumentException("Unknown command. Use /ba help.");
            require(sender, action);
            switch (action) {
                case "help" -> help(sender);
                case "info" -> Text.send(sender, "BlocksAdder " + plugin.getDescription().getVersion() + " by Lino | " + plugin.content().items().size() + " items | " + plugin.worldContent().count() + " loaded placements | " + Bukkit.getBukkitVersion());
                case "browse" -> menus.open(args.length > 2 ? online(args[2], sender) : player(sender), args.length > 1 ? args[1] : "", 0);
                case "get" -> {
                    usage(args, 2, "get <item> [amount]");
                    plugin.items().give(player(sender), args[1], args.length > 2 ? amount(args[2]) : 1);
                    Text.send(sender, "Received " + args[1] + ".");
                }
                case "give" -> {
                    usage(args, 3, "give <player> <item> [amount] [silent]");
                    Player target = target(args[1]);
                    int count = args.length > 3 ? amount(args[3]) : 1;
                    plugin.items().give(target, args[2], count);
                    if (args.length < 5 || !args[4].equalsIgnoreCase("silent")) Text.send(target, "Received " + count + " x " + args[2] + ".");
                    Text.send(sender, "Gave " + count + " x " + args[2] + " to " + target.getName() + ".");
                }
                case "remove" -> {
                    usage(args, 3, "remove <player> <item> [amount] [silent]");
                    Player target = target(args[1]);
                    int removed = plugin.items().remove(target, args[2], args.length > 3 ? amount(args[3]) : 1);
                    if (args.length < 5 || !args[4].equalsIgnoreCase("silent")) Text.send(target, "Removed " + removed + " x " + args[2] + ".");
                    Text.send(sender, "Removed " + removed + " item(s) from " + target.getName() + ".");
                }
                case "drop" -> drop(sender, args);
                case "inspect" -> inspect(sender);
                case "modeldata" -> {
                    var meta = held(sender).getItemMeta();
                    Text.send(sender, "Item model: " + meta.getItemModel() + " | Model data: " + meta.getCustomModelDataComponent().getFloats());
                }
                case "repair" -> damage(sender, 0, false);
                case "durability" -> {
                    usage(args, 2, "durability <remaining durability>");
                    damage(sender, integer(args[1], 0, Integer.MAX_VALUE), true);
                }
                case "rename" -> {
                    usage(args, 2, "rename <name>");
                    ItemStack held = held(sender);
                    var meta = held.getItemMeta();
                    meta.setDisplayName(Text.color(plugin.gameplay().expand(String.join(" ", Arrays.copyOfRange(args, 1, args.length)))));
                    held.setItemMeta(meta);
                    Text.send(sender, "Item renamed.");
                }
                case "color" -> {
                    usage(args, 2, "color <#RRGGBB>");
                    ItemStack held = held(sender);
                    if (!(held.getItemMeta() instanceof LeatherArmorMeta meta)) throw new IllegalArgumentException("Hold a leather armor item.");
                    meta.setColor(io.github.lino9999.blocksadder.item.ItemService.parseColor(args[1]));
                    held.setItemMeta(meta);
                    Text.send(sender, "Item color updated.");
                }
                case "reload" -> {
                    plugin.reloadContent();
                    Text.send(sender, "Configuration and content reloaded. Run /ba pack build after changing assets.");
                }
                case "validate" -> {
                    var candidate = plugin.validateContent();
                    Text.send(sender, "Validation passed: " + candidate.items().size() + " item definitions.");
                }
                case "pack" -> pack(sender, args);
                case "block" -> {
                    Player actor = player(sender);
                    var block = actor.getTargetBlockExact(8);
                    if (block == null) throw new IllegalArgumentException("Look at a block within eight blocks.");
                    Text.send(sender, plugin.worldContent().inspect(block.getLocation()));
                }
                case "recipe" -> {
                    usage(args, 2, "recipe <item> [player]");
                    menus.recipe(args.length > 2 ? online(args[2], sender) : player(sender), args[1]);
                }
                case "glyph" -> {
                    if (args.length > 1) Text.send(sender, plugin.gameplay().glyph(args[1]).orElseThrow(() -> new IllegalArgumentException("Unknown glyph: " + args[1])));
                    else Text.send(sender, "Font images: " + String.join(", ", plugin.content().sections("font_images").keySet().stream().sorted().toList()));
                }
                case "stat" -> stat(sender, args);
                case "plants" -> {
                    usage(args, 2, "plants <auto|full|hide|status> [player]");
                    Player actor = args.length > 2 ? online(args[2], sender) : player(sender);
                    var mode = switch (args[1].toLowerCase(Locale.ROOT)) {
                        case "auto" -> io.github.lino9999.blocksadder.crop.CropViewService.Mode.AUTO;
                        case "full" -> io.github.lino9999.blocksadder.crop.CropViewService.Mode.FULL;
                        case "hide" -> io.github.lino9999.blocksadder.crop.CropViewService.Mode.HIDDEN;
                        case "status" -> plugin.plantView(actor);
                        default -> throw new IllegalArgumentException("Use plants auto, full, hide or status.");
                    };
                    if (!args[1].equalsIgnoreCase("status")) plugin.plantView(actor, mode);
                    Text.send(sender, actor.getName() + " plant view: " + mode.name().toLowerCase(Locale.ROOT) + ".");
                }
                case "hud" -> {
                    usage(args, 3, "hud <show|hide> <hud> [player]");
                    if (!args[1].equalsIgnoreCase("show") && !args[1].equalsIgnoreCase("hide")) throw new IllegalArgumentException("Use show or hide.");
                    plugin.gameplay().hud(args.length > 3 ? online(args[3], sender) : player(sender), args[2], args[1].equalsIgnoreCase("show"));
                    Text.send(sender, "HUD visibility updated.");
                }
                case "tree" -> {
                    usage(args, 2, "tree <tree>");
                    if (!plugin.worldContent().spawnTree(player(sender).getLocation(), args[1])) throw new IllegalArgumentException("Tree cannot be placed here.");
                    Text.send(sender, "Tree generated.");
                }
                case "entity" -> entity(sender, args);
                case "model" -> advanced.model(sender, args);
                case "disguise" -> advanced.disguise(sender, args, false);
                case "undisguise" -> advanced.disguise(sender, args, true);
                case "liquid" -> advanced.environment(sender, args, false);
                case "fire" -> advanced.environment(sender, args, true);
                case "vehicle" -> advanced.vehicle(sender, args);
                case "config" -> configuration.execute(sender, args);
                case "emote" -> {
                    usage(args, 2, "emote <emote|list|info|stop|stop-now> [player]");
                    if (args[1].equalsIgnoreCase("list")) {
                        Text.send(sender, "Emotes: " + String.join(", ", plugin.content().sections("emotes").keySet().stream().sorted().toList()));
                        break;
                    }
                    Player actor = args.length > 2 ? online(args[2], sender) : player(sender);
                    if (args[1].equalsIgnoreCase("info")) { Text.send(sender, plugin.emoteState(actor)); break; }
                    if (!plugin.playEmote(actor, args[1])) throw new IllegalArgumentException("Unknown or unavailable emote: " + args[1]);
                    Text.send(sender, "Emote updated.");
                }
                case "sound" -> {
                    usage(args, 3, "sound <namespaced sound> <player> [volume] [pitch]");
                    Player target = target(args[2]);
                    var key = org.bukkit.NamespacedKey.fromString(args[1]);
                    if (key == null) throw new IllegalArgumentException("Use a namespaced sound.");
                    float volume = args.length > 3 ? (float) decimal(args[3], 0, 16) : 1;
                    float pitch = args.length > 4 ? (float) decimal(args[4], 0.01, 2) : 1;
                    target.playSound(target.getLocation(), key.toString(), volume, pitch);
                    Text.send(sender, "Sound sent.");
                }
                case "totem" -> {
                    usage(args, 2, "totem <player>");
                    target(args[1]).playEffect(org.bukkit.EntityEffect.TOTEM_RESURRECT);
                    Text.send(sender, "Totem animation sent.");
                }
                default -> throw new IllegalArgumentException("Use /ba help.");
            }
        } catch (IllegalArgumentException exception) {
            Text.send(sender, "&c" + exception.getMessage());
        } catch (Exception exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Command failed: " + action, exception);
            Text.send(sender, "&cOperation failed: " + exception.getMessage());
        }
        return true;
    }

    private void entity(CommandSender sender, String[] args) {
        usage(args, 3, "entity <spawn|remove> <entity ID|UUID> [player]");
        if (args[1].equalsIgnoreCase("spawn")) {
            Player actor = args.length > 3 ? target(args[3]) : player(sender);
            var entity = plugin.worldContent().spawnEntity(actor.getLocation(), args[2]);
            Text.send(sender, "Spawned " + args[2] + " (" + entity.getUniqueId() + ").");
        } else if (args[1].equalsIgnoreCase("remove")) {
            var entity = Bukkit.getEntity(java.util.UUID.fromString(args[2]));
            if (entity == null || !entity.getPersistentDataContainer().has(new org.bukkit.NamespacedKey(plugin, "entity_id"), org.bukkit.persistence.PersistentDataType.STRING)) throw new IllegalArgumentException("No loaded BlocksAdder entity with that UUID.");
            entity.remove();
            Text.send(sender, "Entity removed.");
        } else throw new IllegalArgumentException("Use entity spawn or entity remove.");
    }

    private void inspect(CommandSender sender) {
        ItemStack held = held(sender);
        Text.send(sender, "ID: " + plugin.items().identify(held).orElse("vanilla") + " | Material: " + held.getType() + " | Stack: " + held.getAmount() + "/" + held.getMaxStackSize());
    }

    private void stat(CommandSender sender, String[] args) {
        usage(args, 4, "stat <read|write|add|subtract> <player> <stat> [value]");
        Player target = target(args[2]);
        String action = args[1].toLowerCase(Locale.ROOT);
        double value = plugin.gameplay().stat(target, args[3]);
        if (!action.equals("read")) {
            usage(args, 5, "stat <write|add|subtract> <player> <stat> <value>");
            double input = decimal(args[4], -1.0e12, 1.0e12);
            value = switch (action) {
                case "write" -> input;
                case "add" -> value + input;
                case "subtract" -> value - input;
                default -> throw new IllegalArgumentException("Use read, write, add or subtract.");
            };
            value = plugin.gameplay().stat(target, args[3], value);
        }
        Text.send(sender, target.getName() + " | " + args[3] + " = " + value);
    }

    private void drop(CommandSender sender, String[] args) {
        usage(args, 3, "drop <item> <player|x y z world> [amount]");
        Location location;
        int count;
        if (args.length >= 6) {
            var world = Bukkit.getWorld(args[5]);
            if (world == null) throw new IllegalArgumentException("Unknown world: " + args[5]);
            location = new Location(world, decimal(args[2], -29999984, 29999984), decimal(args[3], world.getMinHeight(), world.getMaxHeight() - 1), decimal(args[4], -29999984, 29999984));
            count = args.length > 6 ? amount(args[6]) : 1;
        } else {
            location = target(args[2]).getLocation();
            count = args.length > 3 ? amount(args[3]) : 1;
        }
        ItemStack template = plugin.items().create(args[1], 1);
        if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) throw new IllegalArgumentException("The target chunk must be loaded.");
        for (int remaining = count; remaining > 0; remaining -= template.getMaxStackSize()) {
            var stack = template.clone();
            stack.setAmount(Math.min(remaining, template.getMaxStackSize()));
            location.getWorld().dropItemNaturally(location, stack);
        }
        Text.send(sender, "Dropped " + count + " x " + args[1] + ".");
    }

    private void pack(CommandSender sender, String[] args) {
        usage(args, 2, "pack <build|send|hash> [player|all]");
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "build" -> {
                if (!sender.hasPermission("blocksadder.pack.build")) throw new IllegalArgumentException("Missing permission: blocksadder.pack.build");
                Text.send(sender, "Building the resource pack...");
                plugin.packs().build().whenComplete((artifact, error) -> {
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null) Text.send(sender, "&cResource pack build failed: " + (error.getCause() == null ? error.getMessage() : error.getCause().getMessage()));
                        else Text.send(sender, "Resource pack ready: " + artifact.entries() + " files, " + artifact.bytes() + " bytes. SHA-1: " + artifact.sha1());
                    });
                });
            }
            case "hash" -> {
                var artifact = plugin.packs().artifact();
                if (artifact == null) throw new IllegalArgumentException("Build the resource pack first.");
                Text.send(sender, "SHA-1: " + artifact.sha1() + " | SHA-256: " + artifact.sha256());
            }
            case "send" -> {
                if (args.length > 2) {
                    if (!sender.hasPermission("blocksadder.others")) throw new IllegalArgumentException("Missing permission: blocksadder.others");
                    if (args[2].equalsIgnoreCase("all")) Bukkit.getOnlinePlayers().forEach(plugin.packs()::apply);
                    else plugin.packs().apply(target(args[2]));
                } else plugin.packs().apply(player(sender));
                Text.send(sender, "Resource pack request sent.");
            }
            default -> throw new IllegalArgumentException("Use pack build, pack send or pack hash.");
        }
    }

    private void damage(CommandSender sender, int value, boolean remaining) {
        ItemStack held = held(sender);
        if (!(held.getItemMeta() instanceof Damageable meta)) throw new IllegalArgumentException("Hold an item with durability.");
        int maximum = meta.hasMaxDamage() ? meta.getMaxDamage() : held.getType().getMaxDurability();
        if (maximum < 1) throw new IllegalArgumentException("The held item has no durability.");
        meta.setDamage(remaining ? maximum - Math.min(maximum, value) : value);
        held.setItemMeta(meta);
        Text.send(sender, "Item durability updated.");
    }

    private static String[] normalize(String command, String[] args) {
        String action = SHORTCUTS.get(command.toLowerCase(Locale.ROOT));
        if (action == null) return args;
        String[] result = new String[args.length + 1];
        result[0] = action;
        System.arraycopy(args, 0, result, 1, args.length);
        return result;
    }

    private static void require(CommandSender sender, String action) {
        if (!sender.hasPermission("blocksadder." + action)) throw new IllegalArgumentException("Missing permission: blocksadder." + action);
    }

    private static Player online(String name, CommandSender sender) {
        Player result = target(name);
        if (!result.equals(sender) && !sender.hasPermission("blocksadder.others")) throw new IllegalArgumentException("Missing permission: blocksadder.others");
        return result;
    }

    private static Player player(CommandSender sender) {
        if (sender instanceof Player player) return player;
        throw new IllegalArgumentException("This command requires a player.");
    }

    private static Player target(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) throw new IllegalArgumentException("Player is offline: " + name);
        return player;
    }

    private static ItemStack held(CommandSender sender) {
        ItemStack result = player(sender).getInventory().getItemInMainHand();
        if (result.getType().isAir()) throw new IllegalArgumentException("Hold an item in your main hand.");
        return result;
    }

    private static void usage(String[] args, int length, String usage) {
        if (args.length < length) throw new IllegalArgumentException("Usage: /ba " + usage);
    }

    private static int amount(String value) {
        return integer(value, 1, 6400);
    }

    private static int integer(String value, int min, int max) {
        try {
            int result = Integer.parseInt(value);
            if (result < min || result > max) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Use an integer between " + min + " and " + max + ".");
        }
    }

    private static double decimal(String value, double min, double max) {
        try {
            double result = Double.parseDouble(value);
            if (!Double.isFinite(result) || result < min || result > max) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Use a finite number between " + min + " and " + max + ".");
        }
    }

    private void help(CommandSender sender) {
        Text.send(sender, "&bBlocksAdder &7by Lino");
        Text.send(sender, "/ba browse [category] [player] | recipe <item> [player] | glyph [id]");
        if (sender.hasPermission("blocksadder.plants")) Text.send(sender, "/ba plants <auto|full|hide|status> [player]");
        if (!sender.hasPermission("blocksadder.get")) return;
        Text.send(sender, "/ba get <item> [amount] | give <player> <item> [amount] [silent]");
        Text.send(sender, "/ba remove <player> <item> [amount] [silent] | drop <item> <player|x y z world> [amount]");
        Text.send(sender, "/ba inspect | modeldata | block | repair | rename <name> | durability <remaining> | color <#RRGGBB>");
        Text.send(sender, "/ba pack <build|send|hash> [player|all] | reload | validate | info");
        Text.send(sender, "/ba stat <read|write|add|subtract> <player> <stat> [value] | hud <show|hide> <hud> [player]");
        Text.send(sender, "/ba tree <id> | entity <spawn|remove> <id|UUID> [player] | emote <id|list|info|stop|stop-now> [player]");
        Text.send(sender, "/ba sound <sound> <player> [volume] [pitch] | totem <player>");
        Text.send(sender, "/ba model <list|attach|play|detach|info|bones|bone|color|glow|mount|lock|camera|variables|variable|skin> | disguise <model> [player] | undisguise [player]");
        Text.send(sender, "/ba vehicle <list|spawn|info|mount|dismount|remove|fuel>");
        Text.send(sender, "/ba config <item|namespace|recipes> <disable|delete> <identifier>");
        Text.send(sender, "/ba liquid <inspect|place|remove> | fire <inspect|place|remove|age>");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] input) {
        String[] args = normalize(command.getName(), input);
        List<String> choices = new ArrayList<>();
        if (args.length == 1) choices.addAll(SUBCOMMANDS.stream().filter(action -> sender.hasPermission("blocksadder." + action)).toList());
        else if (args.length > 1) {
            String action = args[0].toLowerCase(Locale.ROOT);
            if (!sender.hasPermission("blocksadder." + action)) return List.of();
            switch (action) {
                case "get", "recipe", "drop" -> { if (args.length == 2) choices.addAll(plugin.content().items().stream().map(item -> item.id().toString()).toList()); }
                case "give", "remove" -> {
                    if (args.length == 2) choices.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                    if (args.length == 3) choices.addAll(plugin.content().items().stream().map(item -> item.id().toString()).toList());
                }
                case "browse" -> { if (args.length == 2) choices.addAll(plugin.content().items().stream().map(item -> item.category()).distinct().toList()); }
                case "pack" -> { if (args.length == 2) choices.addAll(List.of("build", "send", "hash")); else choices.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()); }
                case "stat" -> {
                    if (args.length == 2) choices.addAll(List.of("read", "write", "add", "subtract"));
                    if (args.length == 3) choices.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                    if (args.length == 4) choices.addAll(plugin.content().sections("stats").keySet());
                }
                case "hud" -> { if (args.length == 2) choices.addAll(List.of("show", "hide")); if (args.length == 3) choices.addAll(plugin.content().sections("huds").keySet()); }
                case "plants" -> { if (args.length == 2) choices.addAll(List.of("auto", "full", "hide", "status")); if (args.length == 3) choices.addAll(Bukkit.getOnlinePlayers().stream().filter(actor -> actor.equals(sender) || sender.hasPermission("blocksadder.others")).map(Player::getName).toList()); }
                case "tree" -> choices.addAll(plugin.content().sections("trees").keySet());
                case "glyph" -> choices.addAll(plugin.content().sections("font_images").keySet());
                case "emote" -> { choices.addAll(List.of("list", "info", "stop", "stop-now")); choices.addAll(plugin.content().sections("emotes").keySet()); }
                case "entity" -> { if (args.length == 2) choices.addAll(List.of("spawn", "remove")); if (args.length == 3 && args[1].equals("spawn")) choices.addAll(plugin.content().sections("entities").keySet()); }
                case "model" -> { if (args.length == 2) choices.addAll(List.of("list", "attach", "play", "detach", "info", "bones", "bone", "color", "glow", "mount", "lock", "camera", "variables", "variable", "skin")); if (args.length == 3 && args[1].equals("attach")) choices.addAll(plugin.models().models()); }
                case "vehicle" -> { if (args.length == 2) choices.addAll(List.of("list", "spawn", "info", "mount", "dismount", "remove", "fuel")); if (args.length == 3 && args[1].equals("spawn")) choices.addAll(plugin.vehicles().definitions()); }
                case "config" -> { if (args.length == 2) choices.addAll(List.of("item", "namespace", "recipes")); if (args.length == 3) choices.addAll(List.of("disable", "delete")); if (args.length == 4) choices.addAll(plugin.content().items().stream().filter(item -> !item.options().getBoolean("hidden", false)).map(item -> args[1].equals("namespace") ? item.id().namespace() : item.id().toString()).distinct().toList()); }
                case "disguise" -> { if (args.length == 2) choices.addAll(plugin.models().models()); else choices.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()); }
                case "undisguise" -> choices.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                case "liquid", "fire" -> { if (args.length == 2) choices.addAll(action.equals("fire") ? List.of("inspect", "place", "remove", "age") : List.of("inspect", "place", "remove")); if (args.length == 3 && args[1].equals("place")) choices.addAll(plugin.content().sections(action.equals("fire") ? "fires" : "liquids").keySet()); }
                case "sound" -> { if (args.length == 2) choices.addAll(plugin.content().sections("sounds").keySet()); else choices.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()); }
                case "totem" -> choices.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                default -> { }
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().distinct().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
