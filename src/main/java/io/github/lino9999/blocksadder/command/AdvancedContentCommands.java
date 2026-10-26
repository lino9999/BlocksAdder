package io.github.lino9999.blocksadder.command;

import io.github.lino9999.blocksadder.BlocksAdderPlugin;
import io.github.lino9999.blocksadder.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.Arrays;
import java.util.UUID;

final class AdvancedContentCommands {
    private final BlocksAdderPlugin plugin;

    AdvancedContentCommands(BlocksAdderPlugin plugin) { this.plugin = plugin; }

    void vehicle(CommandSender sender, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            Text.send(sender, "Vehicles: " + String.join(", ", plugin.vehicles().definitions()));
            return;
        }
        switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
            case "spawn" -> {
                usage(args, 3, "vehicle spawn <id> [x y z world]");
                var entity = plugin.vehicles().spawn(location(sender, args, 3), args[2], sender instanceof Player actor ? actor : null);
                Text.send(sender, "Vehicle spawned: " + entity.getUniqueId());
            }
            case "info" -> {
                usage(args, 3, "vehicle info <vehicle UUID>");
                Text.send(sender, plugin.vehicles().inspect(UUID.fromString(args[2])));
            }
            case "mount" -> {
                usage(args, 3, "vehicle mount <vehicle UUID> [player]");
                Player actor = args.length > 3 ? Bukkit.getPlayerExact(args[3]) : player(sender);
                if (actor == null) throw new IllegalArgumentException("The player is offline.");
                if (!actor.equals(sender) && !sender.hasPermission("blocksadder.others")) throw new IllegalArgumentException("Missing permission: blocksadder.others");
                if (!plugin.vehicles().mount(actor, UUID.fromString(args[2]))) throw new IllegalArgumentException("The vehicle is occupied or unavailable.");
                Text.send(sender, "Player mounted.");
            }
            case "dismount" -> {
                Player actor = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : player(sender);
                if (actor == null) throw new IllegalArgumentException("The player is offline.");
                if (!actor.equals(sender) && !sender.hasPermission("blocksadder.others")) throw new IllegalArgumentException("Missing permission: blocksadder.others");
                if (!plugin.vehicles().dismount(actor)) throw new IllegalArgumentException("The player is not riding a managed vehicle.");
                Text.send(sender, "Player dismounted.");
            }
            case "remove" -> {
                usage(args, 3, "vehicle remove <vehicle UUID>");
                if (!plugin.vehicles().remove(UUID.fromString(args[2]))) throw new IllegalArgumentException("The vehicle is unavailable.");
                Text.send(sender, "Vehicle removed.");
            }
            case "fuel" -> {
                usage(args, 3, "vehicle fuel <vehicle UUID> [amount]");
                UUID id = UUID.fromString(args[2]);
                double fuel = args.length > 3 ? plugin.vehicles().fuel(id, Double.parseDouble(args[3])) : plugin.vehicles().fuel(id);
                Text.send(sender, "Vehicle fuel: " + fuel);
            }
            default -> throw new IllegalArgumentException("Use /ba vehicle list|spawn|info|mount|dismount|remove|fuel.");
        }
    }

    void model(CommandSender sender, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            Text.send(sender, "Models: " + String.join(", ", plugin.models().models().stream().sorted().toList()));
            return;
        }
        switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
            case "attach" -> {
                usage(args, 4, "model attach <model> <entity UUID>");
                var entity = Bukkit.getEntity(UUID.fromString(args[3]));
                if (entity == null) throw new IllegalArgumentException("The target entity is not loaded.");
                UUID rig = plugin.models().attach(entity, args[2]);
                Text.send(sender, "Model attached: " + rig);
            }
            case "play" -> {
                usage(args, 4, "model play <rig UUID> <animation|stop> [loop]");
                if (!plugin.models().play(UUID.fromString(args[2]), args[3], args.length > 4 && booleanValue(args[4]))) throw new IllegalArgumentException("Unknown rig or animation.");
                Text.send(sender, "Model animation updated.");
            }
            case "detach" -> {
                usage(args, 3, "model detach <rig UUID>");
                plugin.models().detach(UUID.fromString(args[2]));
                Text.send(sender, "Model detached.");
            }
            case "info" -> {
                usage(args, 3, "model info <rig UUID>");
                Text.send(sender, plugin.models().inspect(UUID.fromString(args[2])));
            }
            case "bones" -> {
                usage(args, 3, "model bones <rig UUID>");
                Text.send(sender, "Bones: " + String.join(", ", plugin.models().bones(UUID.fromString(args[2]))));
            }
            case "bone" -> {
                usage(args, 4, "model bone <rig UUID> <bone>");
                Location location = plugin.models().boneLocation(UUID.fromString(args[2]), args[3]).orElseThrow(() -> new IllegalArgumentException("Unknown rig or bone."));
                Text.send(sender, location.getWorld().getName() + " " + location.getX() + " " + location.getY() + " " + location.getZ());
            }
            case "color" -> {
                usage(args, 5, "model color <rig UUID> <bone|all> <#RRGGBB>");
                plugin.models().color(UUID.fromString(args[2]), args[3], io.github.lino9999.blocksadder.item.ItemService.parseColor(args[4]).asRGB());
                Text.send(sender, "Model color updated.");
            }
            case "glow" -> {
                usage(args, 5, "model glow <rig UUID> <bone|all> <true|false>");
                plugin.models().enchanted(UUID.fromString(args[2]), args[3], booleanValue(args[4]));
                Text.send(sender, "Model enchantment effect updated.");
            }
            case "mount" -> {
                usage(args, 5, "model mount <rig UUID> <bone> <player>");
                Player passenger = Bukkit.getPlayerExact(args[4]);
                if (passenger == null) throw new IllegalArgumentException("The player is offline.");
                if (!passenger.equals(sender) && !sender.hasPermission("blocksadder.others")) throw new IllegalArgumentException("Missing permission: blocksadder.others");
                if (!plugin.models().mount(UUID.fromString(args[2]), args[3], passenger)) throw new IllegalArgumentException("The model seat is occupied or unavailable.");
                Text.send(sender, "Player mounted.");
            }
            case "lock" -> {
                usage(args, 5, "model lock <rig UUID> <bone> <true|false>");
                plugin.models().lockMount(UUID.fromString(args[2]), args[3], booleanValue(args[4]));
                Text.send(sender, "Mount lock updated.");
            }
            case "camera" -> {
                usage(args, 4, "model camera <rig UUID> <true|false|distance> [player]");
                Player viewer = args.length > 4 ? Bukkit.getPlayerExact(args[4]) : player(sender);
                if (viewer == null) throw new IllegalArgumentException("The player is offline.");
                if (!viewer.equals(sender) && !sender.hasPermission("blocksadder.others")) throw new IllegalArgumentException("Missing permission: blocksadder.others");
                boolean stop = args[3].equalsIgnoreCase("false");
                if (!stop && !plugin.models().cameraAvailable()) throw new IllegalArgumentException(plugin.models().cameraReason());
                boolean success = args[3].equalsIgnoreCase("true") || stop ? plugin.models().camera(UUID.fromString(args[2]), viewer, !stop) : plugin.models().camera(UUID.fromString(args[2]), viewer, Double.parseDouble(args[3]));
                if (!success) throw new IllegalArgumentException("The model camera could not be updated for this player.");
                Text.send(sender, stop ? "Model camera stopped." : "Model camera started.");
            }
            case "variables" -> {
                usage(args, 3, "model variables <rig UUID>");
                Text.send(sender, "Model variables: " + plugin.models().variables(UUID.fromString(args[2])));
            }
            case "skin" -> {
                usage(args, 4, "model skin <rig UUID> <player|texture|reset>");
                UUID rig = UUID.fromString(args[2]);
                Player source = Bukkit.getPlayerExact(args[3]);
                boolean success = args[3].equalsIgnoreCase("reset") ? plugin.models().resetSkin(rig) : source != null ? plugin.models().skin(rig, source) : plugin.models().skin(rig, args[3]);
                if (!success) throw new IllegalArgumentException("The model has no available player-skin binding.");
                Text.send(sender, "Model skin updated.");
            }
            case "variable" -> {
                usage(args, 5, "model variable <rig UUID> <name> <value>");
                double value = plugin.models().variable(UUID.fromString(args[2]), args[3], Double.parseDouble(args[4]));
                Text.send(sender, "Model variable updated: " + args[3] + " = " + value);
            }
            default -> throw new IllegalArgumentException("Use /ba model list|attach|play|detach|info|bones|bone|color|glow|mount|lock|camera|variables|variable|skin.");
        }
    }

    void disguise(CommandSender sender, String[] args, boolean remove) {
        if (!remove) usage(args, 2, "disguise <model> [player]");
        int targetIndex = remove ? 1 : 2;
        Player actor = args.length > targetIndex ? Bukkit.getPlayerExact(args[targetIndex]) : sender instanceof Player player ? player : null;
        if (actor == null) throw new IllegalArgumentException("Specify an online player.");
        if (!actor.equals(sender) && !sender.hasPermission("blocksadder.others")) throw new IllegalArgumentException("Missing permission: blocksadder.others");
        if (remove) plugin.models().undisguise(actor);
        else if (!plugin.models().disguise(actor, args[1])) throw new IllegalArgumentException("Unknown model: " + args[1]);
        Text.send(sender, remove ? "Player disguise removed." : "Player disguise applied.");
    }

    void environment(CommandSender sender, String[] args, boolean fire) {
        String command = fire ? "fire" : "liquid";
        if (args.length == 1 || args[1].equalsIgnoreCase("inspect")) {
            var target = player(sender).getTargetBlockExact(8, FluidCollisionMode.ALWAYS);
            if (target == null) throw new IllegalArgumentException("Look at a block or liquid within eight blocks.");
            Text.send(sender, plugin.environment().inspect(target.getLocation()));
            return;
        }
        if (args[1].equalsIgnoreCase("place")) {
            usage(args, 3, command + " place <id> [x y z world]");
            Location location = location(sender, args, 3);
            boolean success = fire ? plugin.environment().placeFire(location, args[2], sender instanceof Player actor ? actor : null)
                    : plugin.environment().placeLiquid(location, args[2], sender instanceof Player actor ? actor : null);
            if (!success) throw new IllegalArgumentException("The custom " + command + " could not be placed here.");
            Text.send(sender, "Custom " + command + " placed.");
        } else if (args[1].equalsIgnoreCase("remove")) {
            Location location = location(sender, args, 2);
            if (!plugin.environment().remove(location, sender instanceof Player actor ? actor : null)) throw new IllegalArgumentException("No removable custom liquid or fire at this location.");
            Text.send(sender, "Custom environment content removed.");
        } else if (fire && args[1].equalsIgnoreCase("age")) {
            usage(args, 3, "fire age <age> [x y z world]");
            if (!plugin.environment().setFireAge(location(sender, args, 3).getBlock(), Integer.parseInt(args[2]))) throw new IllegalArgumentException("No custom fire at this location.");
            Text.send(sender, "Fire age updated.");
        } else throw new IllegalArgumentException("Use /ba " + command + " inspect|place|remove" + (fire ? "|age" : "") + ".");
    }

    private static Location location(CommandSender sender, String[] args, int offset) {
        if (args.length == offset) return player(sender).getLocation().getBlock().getLocation();
        usage(args, offset + 4, args[0] + " " + args[1] + " " + String.join(" ", Arrays.copyOfRange(args, 2, offset)) + " <x> <y> <z> <world>");
        var world = Bukkit.getWorld(args[offset + 3]);
        if (world == null) throw new IllegalArgumentException("Unknown world: " + args[offset + 3]);
        int x = Integer.parseInt(args[offset]);
        int y = Integer.parseInt(args[offset + 1]);
        int z = Integer.parseInt(args[offset + 2]);
        if (Math.abs((long) x) > 29999984 || Math.abs((long) z) > 29999984 || y < world.getMinHeight() || y >= world.getMaxHeight()) throw new IllegalArgumentException("Coordinates are outside the world bounds.");
        if (!world.isChunkLoaded(x >> 4, z >> 4)) throw new IllegalArgumentException("The target chunk must be loaded.");
        return new Location(world, x, y, z);
    }

    private static boolean booleanValue(String value) {
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("loop")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException("Use true or false.");
    }

    private static Player player(CommandSender sender) {
        if (sender instanceof Player player) return player;
        throw new IllegalArgumentException("This command requires a player or explicit coordinates.");
    }

    private static void usage(String[] args, int length, String usage) {
        if (args.length < length) throw new IllegalArgumentException("Usage: /ba " + usage);
    }
}
