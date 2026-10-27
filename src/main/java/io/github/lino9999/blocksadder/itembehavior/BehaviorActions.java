package io.github.lino9999.blocksadder.itembehavior;

import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.item.ItemKeys;
import io.github.lino9999.blocksadder.item.ItemService;
import io.github.lino9999.blocksadder.item.ItemWear;

import org.bukkit.Effect;
import org.bukkit.EntityEffect;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

final class BehaviorActions {
    static final List<String> FAMILIES =
            List.of(
                    "damage_entity_in_sight",
                    "target_remove_potion_effect",
                    "target_potion_effect",
                    "increment_player_stat",
                    "decrement_player_stat",
                    "increment_durability",
                    "decrement_durability",
                    "decrement_usages",
                    "increment_amount",
                    "decrement_amount",
                    "damage_near_entities",
                    "remove_potion_effect",
                    "play_totem_animation",
                    "replace_properties",
                    "world_change",
                    "world_sweep",
                    "execute_commands",
                    "shoot_particle",
                    "play_particle",
                    "play_effect",
                    "play_sound",
                    "stop_sound",
                    "potion_effect",
                    "damage_entity",
                    "explosion",
                    "give_item",
                    "drop_item",
                    "drop_exp",
                    "feed",
                    "cancel");
    private final ItemBehaviorService service;
    private final ItemService items;

    BehaviorActions(ItemBehaviorService service, ItemService items) {
        this.service = service;
        this.items = items;
    }

    Action compile(ItemDefinition item, String family, ConfigurationSection c) {
        java.util.Set<String> fields =
                switch (family) {
                    case "world_change" -> java.util.Set.of("match", "into");
                    case "world_sweep" -> java.util.Set.of("region", "frame", "select");
                    case "cancel" -> java.util.Set.of();
                    case "feed" -> java.util.Set.of("amount", "saturation");
                    case "play_sound", "stop_sound" ->
                            java.util.Set.of(
                                    "name", "volume", "pitch", "category", "stop_previous");
                    case "potion_effect", "target_potion_effect" ->
                            java.util.Set.of(
                                    "type",
                                    "duration",
                                    "amplifier",
                                    "ambient",
                                    "particles",
                                    "icon");
                    case "remove_potion_effect", "target_remove_potion_effect" ->
                            java.util.Set.of("type");
                    case "increment_amount",
                            "decrement_amount",
                            "increment_durability",
                            "decrement_durability",
                            "decrement_usages" ->
                            java.util.Set.of("amount");
                    case "give_item" -> java.util.Set.of("item", "amount");
                    case "drop_item", "drop_exp" ->
                            java.util.Set.of("item", "min_amount", "max_amount", "chance");
                    case "increment_player_stat", "decrement_player_stat" ->
                            java.util.Set.of("name", "amount");
                    case "play_particle", "shoot_particle" ->
                            java.util.Set.of("name", "id", "distance", "payload", "emit");
                    case "play_effect" -> java.util.Set.of("name", "id");
                    case "play_totem_animation" -> java.util.Set.of("item");
                    case "damage_entity", "damage_entity_in_sight" ->
                            java.util.Set.of("damage", "distance");
                    case "damage_near_entities" ->
                            java.util.Set.of("damage", "range", "radius", "entity_groups");
                    case "explosion" -> java.util.Set.of("power", "fire", "break_blocks");
                    case "replace_properties" -> java.util.Set.of("custom_model_data");
                    default -> java.util.Set.of();
                };
        for (String key : c.getKeys(false))
            if (!fields.contains(key)
                    && !java.util.Set.of("permission", "delay", "flow").contains(key))
                throw error(item, "Unsupported action field: " + family + "/" + key);
        return switch (family) {
            case "world_sweep" -> {
                SweepRegion region = SweepRegion.parse(item, c, service.registry());
                String permission = c.getString("permission", "");
                yield context -> service.sweep(region, permission, context);
            }
            case "world_change" -> {
                var match = io.github.lino9999.blocksadder.world.WorldChoice.parse(service.registry(), item.id().namespace(), required(c, "match"));
                var into = io.github.lino9999.blocksadder.world.WorldChoice.parse(service.registry(), item.id().namespace(), required(c, "into"));
                if ((match.kind() == io.github.lino9999.blocksadder.world.ContentKind.FURNITURE || into.kind() == io.github.lino9999.blocksadder.world.ContentKind.FURNITURE)
                        && match.kind() != into.kind()) throw error(item, "World changes keep furnishings within the furnishing family.");
                yield context -> service.changeWorld(match, into, context);
            }
            case "cancel" -> context -> context.cancel();
            case "feed" -> {
                int amount = integer(c, "amount", 0, 0, 20);
                float saturation = (float) number(c, "saturation", 0, 0, 20);
                yield context -> {
                    var player = context.player();
                    player.setFoodLevel(Math.min(20, player.getFoodLevel() + amount));
                    player.setSaturation(
                            Math.min(player.getFoodLevel(), player.getSaturation() + saturation));
                    return true;
                };
            }
            case "play_sound", "stop_sound" -> {
                String name = ItemKeys.soundName(required(c, "name"));
                float volume = (float) number(c, "volume", 1, 0, 16);
                float pitch = (float) number(c, "pitch", 1, 0, 2);
                SoundCategory category =
                        SoundCategory.valueOf(
                                c.getString("category", "MASTER").toUpperCase(Locale.ROOT));
                boolean stop = c.getBoolean("stop_previous", true);
                yield context -> {
                    var player = context.player();
                    if (stop || family.equals("stop_sound")) player.stopSound(name, category);
                    if (family.equals("play_sound"))
                        player.playSound(context.location(), name, category, volume, pitch);
                    return true;
                };
            }
            case "potion_effect", "target_potion_effect" -> {
                var type = ItemKeys.effect(required(c, "type"));
                int duration = integer(c, "duration", 40, 1, 72000);
                int amplifier = integer(c, "amplifier", 1, 0, 255);
                PotionEffect effect =
                        new PotionEffect(
                                type,
                                duration,
                                amplifier,
                                c.getBoolean("ambient"),
                                c.getBoolean("particles", true),
                                c.getBoolean("icon", true));
                yield context -> {
                    LivingEntity target =
                            family.startsWith("target_") ? context.target() : context.player();
                    return target != null && target.addPotionEffect(effect);
                };
            }
            case "remove_potion_effect", "target_remove_potion_effect" -> {
                var type = ItemKeys.effect(required(c, "type"));
                yield context -> {
                    LivingEntity target =
                            family.startsWith("target_") ? context.target() : context.player();
                    if (target == null || !target.hasPotionEffect(type)) return false;
                    target.removePotionEffect(type);
                    return true;
                };
            }
            case "increment_amount", "decrement_amount" -> {
                int amount = integer(c, "amount", 0, 0, 99);
                boolean increase = family.startsWith("increment");
                yield context -> {
                    ItemStack stack = context.stack();
                    if (stack == null || stack.getType().isAir()) return false;
                    int next = increase ? stack.getAmount() + amount : stack.getAmount() - amount;
                    if (next > stack.getMaxStackSize()) return false;
                    stack.setAmount(Math.max(0, next));
                    context.set(stack);
                    return true;
                };
            }
            case "increment_durability", "decrement_durability" -> {
                int amount = integer(c, "amount", 0, 0, 10000);
                int delta = family.startsWith("increment") ? -amount : amount;
                yield context -> {
                    ItemStack stack = context.stack();
                    boolean success = ItemWear.durability(context.player(), items, stack, delta);
                    if (success) context.set(stack);
                    return success;
                };
            }
            case "decrement_usages" -> {
                int amount = integer(c, "amount", 0, 0, 10000);
                yield context -> {
                    if (context.consumeEvent()) context.cancel();
                    return ItemWear.usages(
                            context.player(), context.stack(), amount, context::set, context::give);
                };
            }
            case "give_item" -> {
                ItemStack template = reference(item, required(c, "item"));
                int amount = integer(c, "amount", 1, 1, 6400);
                yield context -> {
                    give(context, template, amount);
                    return true;
                };
            }
            case "drop_item", "drop_exp" -> {
                ItemStack template =
                        family.equals("drop_item") ? reference(item, required(c, "item")) : null;
                int min = integer(c, "min_amount", template == null ? 0 : 1, 0, 6400),
                        max = integer(c, "max_amount", template == null ? 0 : 1, min, 6400);
                double chance = number(c, "chance", template == null ? 0 : 100, 0, 100);
                yield context -> {
                    if (ThreadLocalRandom.current().nextDouble(100) >= chance) return false;
                    int count = ThreadLocalRandom.current().nextInt(min, max + 1);
                    if (template == null)
                        context.location()
                                .getWorld()
                                .spawn(context.location(), ExperienceOrb.class)
                                .setExperience(count);
                    else {
                        while (count > 0) {
                            ItemStack drop = template.clone();
                            drop.setAmount(Math.min(count, drop.getMaxStackSize()));
                            context.location()
                                    .getWorld()
                                    .dropItemNaturally(context.location(), drop);
                            count -= drop.getAmount();
                        }
                    }
                    return true;
                };
            }
            case "increment_player_stat", "decrement_player_stat" -> {
                String name = service.resolveStat(required(c, "name"), item.id().namespace());
                double amount =
                        number(c, "amount", 0, 0, 1000000000)
                                * (family.startsWith("decrement") ? -1 : 1);
                yield context -> {
                    double next = service.stats().get(context.player(), name) + amount;
                    if (!Double.isFinite(next)) return false;
                    service.stats().set(context.player(), name, next);
                    return true;
                };
            }
            case "play_particle", "shoot_particle" -> {
                Particle particle =
                        Particle.valueOf(
                                required(c, c.contains("name") ? "name" : "id")
                                        .replace("minecraft:", "")
                                        .toUpperCase(Locale.ROOT));
                yield ParticleEmission.compile(particle, family.equals("shoot_particle"), c, input -> reference(item, input));
            }
            case "play_effect" -> {
                Effect effect =
                        Effect.valueOf(
                                required(c, c.contains("name") ? "name" : "id")
                                        .toUpperCase(Locale.ROOT));
                if (effect.getData() != null)
                    throw error(item, "Effect " + effect + " requires typed effect data.");
                yield context -> {
                    context.location().getWorld().playEffect(context.location(), effect, null);
                    return true;
                };
            }
            case "play_totem_animation" -> {
                if (c.contains("item"))
                    throw error(
                            item,
                            "A custom totem animation item requires a client component adapter.");
                yield context -> {
                    context.player().playEffect(EntityEffect.TOTEM_RESURRECT);
                    return true;
                };
            }
            case "damage_entity", "damage_entity_in_sight", "damage_near_entities" -> {
                double damage = number(c, "damage", 1, 0, 10000);
                int distance = integer(c, "distance", 3, 1, 32);
                if (family.equals("damage_near_entities")) {
                    int range = integer(c, c.contains("range") ? "range" : "radius", 3, 1, 16);
                    java.util.Set<String> groups =
                            new java.util.HashSet<>(c.getStringList("entity_groups"));
                    for (String group : groups)
                        if (!java.util.Set.of("HOSTILE", "PASSIVE", "PLAYERS").contains(group))
                            throw error(item, "Unknown entity group: " + group);
                    yield context -> {
                        var nearby =
                                context.location()
                                        .getWorld()
                                        .getNearbyEntities(context.location(), range, range, range);
                        if (nearby.size() > 256) return false;
                        boolean applied = false;
                        for (var entity : nearby)
                            if (entity instanceof LivingEntity target
                                    && entity != context.player()
                                    && target.getLocation().distanceSquared(context.location())
                                            <= (double) range * range
                                    && (groups.isEmpty() || groups.contains(group(target)))) {
                                context.damage(target, damage);
                                applied = true;
                            }
                        return applied;
                    };
                }
                yield context -> {
                    LivingEntity target = context.target();
                    if (family.equals("damage_entity_in_sight")) {
                        var hit =
                                context.player()
                                        .getWorld()
                                        .rayTraceEntities(
                                                context.player().getEyeLocation(),
                                                context.player().getEyeLocation().getDirection(),
                                                distance,
                                                entity ->
                                                        entity instanceof LivingEntity
                                                                && entity != context.player());
                        target =
                                hit != null && hit.getHitEntity() instanceof LivingEntity living
                                        ? living
                                        : null;
                    }
                    if (target == null || !target.isValid() || target.isDead()) return false;
                    context.damage(target, damage);
                    return true;
                };
            }
            case "explosion" -> {
                float power = (float) number(c, "power", 1, 0, 16);
                boolean fire = c.getBoolean("fire"), blocks = c.getBoolean("break_blocks", true);
                yield context ->
                        context.location()
                                .getWorld()
                                .createExplosion(
                                        context.location(), power, fire, blocks, context.effectSource());
            }
            case "replace_properties" -> {
                ItemStack source = reference(item, required(c, "custom_model_data.copy_from_item"));
                var model = source.getItemMeta().getCustomModelDataComponent();
                var original =
                        items.create(item.id().toString(), 1)
                                .getItemMeta()
                                .getCustomModelDataComponent();
                boolean restorable = c.getBoolean("custom_model_data.restorable");
                ConfigurationSection properties = c.getConfigurationSection("custom_model_data");
                if (properties != null)
                    for (String key : properties.getKeys(false))
                        if (!java.util.Set.of("copy_from_item", "restorable").contains(key))
                            throw error(item, "Unknown custom model property: " + key);
                yield context -> {
                    ItemStack stack = context.stack();
                    if (stack == null || stack.getType().isAir()) return false;
                    var meta = stack.getItemMeta();
                    boolean matches = meta.getCustomModelDataComponent().equals(model);
                    if (matches && !restorable) return false;
                    meta.setCustomModelDataComponent(matches ? original : model);
                    stack.setItemMeta(meta);
                    context.set(stack);
                    return true;
                };
            }
            default -> throw error(item, "Unsupported action family: " + family);
        };
    }

    private static String group(LivingEntity entity) {
        if (entity instanceof org.bukkit.entity.Monster
                || entity instanceof org.bukkit.entity.ComplexLivingEntity
                || entity instanceof org.bukkit.entity.Flying
                || entity instanceof org.bukkit.entity.Slime) return "HOSTILE";
        if (entity instanceof org.bukkit.entity.Player) return "PLAYERS";
        if (entity instanceof org.bukkit.entity.Animals
                || entity instanceof org.bukkit.entity.NPC
                || entity instanceof org.bukkit.entity.Snowman
                || entity instanceof org.bukkit.entity.WaterMob
                || entity instanceof org.bukkit.entity.Ambient) return "PASSIVE";
        return "";
    }

    List<ItemBehaviorService.Step> commands(
            ItemDefinition item, String name, ConfigurationSection group) {
        List<ItemBehaviorService.Step> result = new ArrayList<>();
        for (String id : group.getKeys(false)) {
            if (List.of("flow", "permission", "delay").contains(id)) continue;
            ConfigurationSection config = group.getConfigurationSection(id);
            if (config == null)
                throw error(item, "Command entries must be sections: " + name + "/" + id);
            for (String key : config.getKeys(false))
                if (!java.util.Set.of(
                                "command",
                                "as_console",
                                "silent",
                                "flow_success_if_message_contains",
                                "delay")
                        .contains(key)) throw error(item, "Unsupported command field: " + key);
            String command = required(config, "command").trim();
            if (command.startsWith("/")) command = command.substring(1);
            service.validatePlaceholders(command);
            boolean console = config.getBoolean("as_console");
            boolean silent = config.getBoolean("silent", true);
            String expected = config.getString("flow_success_if_message_contains");
            if (console && (silent || expected != null) && !CommandOutput.supported(command))
                throw error(
                        item,
                        "Silent console dispatch and output conditions require a registered Bukkit"
                            + " plugin command. Native commands require silent: false without an"
                            + " output condition.");
            if (!console && expected != null)
                throw error(
                        item,
                        "Player command output cannot be captured through the public command API;"
                                + " output conditions require as_console.");
            String executable = command;
            Action action =
                    context ->
                            console
                                    ? CommandOutput.dispatch(
                                            service.expand(executable, context), silent, expected)
                                    : context.player()
                                            .performCommand(service.expand(executable, context));
            result.add(
                    service.step(
                            item,
                            name,
                            group,
                            integer(
                                    config,
                                    "delay",
                                    integer(group, "delay", 0, -1, 72000),
                                    -1,
                                    72000),
                            action));
        }
        if (result.isEmpty()) throw error(item, "An execute_commands group must contain commands.");
        return result;
    }

    private void give(ItemBehaviorService.Context context, ItemStack template, int amount) {
        while (amount > 0) {
            ItemStack stack = template.clone();
            int count = Math.min(amount, stack.getMaxStackSize());
            stack.setAmount(count);
            context.give(stack);
            amount -= count;
        }
    }

    private ItemStack reference(ItemDefinition item, String input) {
        String key = input.contains(":") ? input : item.id().namespace() + ":" + input;
        try {
            return items.create(key, 1);
        } catch (IllegalArgumentException exception) {
            Material material = Material.matchMaterial(input);
            if (material == null
                    || material.isAir()
                    || input.contains(":") && !input.startsWith("minecraft:"))
                throw error(item, "Unknown action item: " + input);
            return new ItemStack(material);
        }
    }

    static String required(ConfigurationSection config, String key) {
        String value = config.getString(key);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Missing action field: " + key);
        return value;
    }

    static int integer(ConfigurationSection config, String key, int fallback, int min, int max) {
        if (config.contains(key) && !config.isInt(key))
            throw new IllegalArgumentException("Action field must be an integer: " + key);
        int value = config.getInt(key, fallback);
        if (value < min || value > max)
            throw new IllegalArgumentException("Action field out of range: " + key);
        return value;
    }

    static double number(
            ConfigurationSection config, String key, double fallback, double min, double max) {
        Object raw = config.get(key);
        if (raw != null && !(raw instanceof Number))
            throw new IllegalArgumentException("Action field must be numeric: " + key);
        double value = config.getDouble(key, fallback);
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException("Action field out of range: " + key);
        return value;
    }

    static IllegalArgumentException error(ItemDefinition item, String message) {
        return new IllegalArgumentException("Item " + item.id() + ": " + message);
    }

    @FunctionalInterface
    interface Action {
        boolean run(ItemBehaviorService.Context context);
    }
}
