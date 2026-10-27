package io.github.lino9999.blocksadder.loot;

import io.github.lino9999.blocksadder.content.ContentId;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.item.ItemService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public final class ContentLootService {
    public enum Trigger { BREAK, INTERACT, TRAMPLE, WATER, LAVA, FARMLAND_DRY }
    public record Command(String command, boolean console) {}
    public record Plan(boolean configured, boolean matched, int stage, List<ItemStack> drops, int experience, List<Command> commands) {
        public Plan { drops = drops.stream().map(ItemStack::clone).toList(); commands = List.copyOf(commands); }
        public boolean successful() { return !drops.isEmpty() || experience > 0 || !commands.isEmpty(); }
    }
    private record Drop(String item, int minimum, int maximum, double chance, boolean fortune) {}
    private record Experience(int minimum, int maximum, double chance) {}
    private record CommandGroup(List<Command> commands, double chance) {}
    private record Rule(List<Trigger> triggers, int minimumAge, int maximumAge, Integer decrease, List<String> worlds, List<String> biomes, List<String> tools, boolean first, List<Drop> drops, List<Experience> experience, List<CommandGroup> commands, List<io.github.lino9999.blocksadder.integration.BlockNbtConditions.Condition> conditions) {}
    private record Source(String key, ContentId id, ConfigurationSection config) {}
    private final Supplier<ContentRegistry> registry;
    private final ItemService items;
    private Map<String, List<Rule>> rules = Map.of();
    private io.github.lino9999.blocksadder.world.BreakRewardRouting rewardRouting;
    public void setRewardRouting(io.github.lino9999.blocksadder.world.BreakRewardRouting routing) { rewardRouting = java.util.Objects.requireNonNull(routing); }

    public ContentLootService(Supplier<ContentRegistry> registry, ItemService items) { this.registry = registry; this.items = items; }
    public void validate(ContentRegistry candidate) { load(candidate); }
    public void reload() { rules = load(registry.get()); }

    private Map<String, List<Rule>> load(ContentRegistry content) {
        Map<String, List<Rule>> result = new TreeMap<>();
        for (Source item : sources(content)) {
            ConfigurationSection config = item.config();
            List<Rule> parsed = new ArrayList<>();
            for (String name : config.getKeys(false)) {
                ConfigurationSection group = config.getConfigurationSection(name);
                if (group == null) throw new IllegalArgumentException("Crop loot must be a section: " + item.id() + "/" + name);
                List<Trigger> triggers = group.getStringList("triggers").stream().map(value -> Trigger.valueOf(value.toUpperCase(Locale.ROOT))).toList();
                if (triggers.isEmpty()) triggers = List.of(Trigger.valueOf(group.getString("trigger", "BREAK").toUpperCase(Locale.ROOT)));
                int min = group.getInt("age", group.getInt("age_range.min", 0));
                int max = group.getInt("age", group.getInt("age_range.max", 127));
                if (min < 0 || max < min || max > 127) throw new IllegalArgumentException("Invalid crop loot age range: " + item.id());
                Integer decrease = group.contains("decrease_age") ? Integer.valueOf(group.getInt("decrease_age")) : null;
                if (decrease != null && (decrease < 0 || decrease > 127)) throw new IllegalArgumentException("Crop loot decrease_age must be between 0 and 127: " + item.id());
                List<Drop> drops = new ArrayList<>();
                for (ConfigurationSection drop : children(group, "item_drop", "item_drops")) {
                    String reference = reference(content, item.id(), drop.getString("item"));
                    int[] range = range(drop);
                    drops.add(new Drop(reference, range[0], range[1], chance(drop), !drop.getBoolean("ignore_fortune", false)));
                }
                List<Experience> experience = new ArrayList<>();
                if (group.isInt("exp_drop")) {
                    int value = group.getInt("exp_drop");
                    if (value < 0 || value > 1000000) throw new IllegalArgumentException("Crop experience must be between 0 and 1000000: " + item.id());
                    experience.add(new Experience(value, value, 100));
                }
                for (ConfigurationSection exp : children(group, "exp_drop", "exp_drops")) {
                    int[] range = range(exp);
                    experience.add(new Experience(range[0], range[1], chance(exp)));
                }
                List<CommandGroup> commands = new ArrayList<>();
                ConfigurationSection groups = group.getConfigurationSection("commands");
                if (groups != null) for (String key : groups.getKeys(false)) {
                    ConfigurationSection commandGroup = groups.getConfigurationSection(key);
                    if (commandGroup == null) throw new IllegalArgumentException("Crop loot command group must be a section: " + key);
                    List<Command> values = new ArrayList<>();
                    for (String action : commandGroup.getKeys(false)) {
                        if (action.equals("chance")) continue;
                        String command = commandGroup.getString(action + ".command");
                        if (command == null || command.isBlank() || command.length() > 2048 || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) throw new IllegalArgumentException("Invalid crop loot command: " + item.id());
                        values.add(new Command(command.startsWith("/") ? command.substring(1) : command, commandGroup.getBoolean(action + ".as_console", true)));
                    }
                    commands.add(new CommandGroup(List.copyOf(values), chance(commandGroup)));
                }
                parsed.add(new Rule(triggers, min, max, decrease, patterns(group, "worlds"), patterns(group, "biomes"), patterns(group, "tools"), group.getBoolean("stop_on_first_success", group.getBoolean("drop_only_first", false)), List.copyOf(drops), List.copyOf(experience), List.copyOf(commands), io.github.lino9999.blocksadder.integration.BlockNbtConditions.parse(group.getConfigurationSection("nbt"))));
            }
            if (parsed.size() > 256) throw new IllegalArgumentException("Too many crop loot groups: " + item.id());
            result.computeIfAbsent(item.key(), ignored -> new ArrayList<>()).addAll(parsed);
        }
        result.replaceAll((key, value) -> List.copyOf(value));
        return Map.copyOf(result);
    }

    public Plan plan(String id, int stage, Trigger trigger, Location location, Player player) {
        return evaluate("crop/" + id, stage, trigger, location, player);
    }

    public Plan block(String id, Location location, Player player) { return evaluate("block/" + id, 0, Trigger.BREAK, location, player); }

    private Plan evaluate(String id, int stage, Trigger trigger, Location location, Player player) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Crop loot requires the server thread.");
        List<Rule> configured = rules.get(id);
        if (configured == null) return new Plan(false, false, stage, List.of(), 0, List.of());
        List<ItemStack> drops = new ArrayList<>();
        List<Command> commands = new ArrayList<>();
        int experience = 0;
        boolean matched = false;
        ItemStack tool = player == null ? new ItemStack(Material.AIR) : player.getInventory().getItemInMainHand();
        String toolId = items.identify(tool).orElse("minecraft:" + tool.getType().name().toLowerCase(Locale.ROOT));
        var fortune = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("fortune"));
        int enchantment = fortune == null ? 0 : Math.min(255, tool.getEnchantmentLevel(fortune));
        var random = ThreadLocalRandom.current();
        io.github.lino9999.blocksadder.integration.BlockNbtConditions.Snapshot snapshot = null;
        for (Rule rule : configured) {
            if (!rule.triggers.contains(trigger) || stage < rule.minimumAge || stage > rule.maximumAge || !matches(rule.worlds, location.getWorld().getName()) || !matches(rule.biomes, location.getBlock().getBiome().getKey().toString()) || !matches(rule.tools, toolId)) continue;
            if (!rule.conditions.isEmpty()) {
                if (snapshot == null) snapshot = io.github.lino9999.blocksadder.integration.BlockNbtConditions.snapshot(location.getBlock());
                if (!rule.conditions.stream().allMatch(snapshot::matches)) continue;
            }
            matched = true;
            if (rule.decrease != null) stage = Math.max(0, stage - rule.decrease);
            for (Drop drop : rule.drops) {
                if (!roll(drop.chance)) continue;
                int amount = random.nextInt(drop.minimum, drop.maximum + 1);
                if (drop.fortune && enchantment > 0) amount = (int) Math.min(4096L, (long) amount * (Math.max(0, random.nextInt(enchantment + 2) - 1) + 1));
                if (amount == 0) continue;
                ItemStack prototype = stack(drop.item);
                while (amount > 0) {
                    ItemStack stack = prototype.clone();
                    int count = Math.min(amount, stack.getMaxStackSize());
                    stack.setAmount(count);
                    drops.add(stack);
                    amount -= count;
                }
                if (rule.first) break;
            }
            for (Experience exp : rule.experience) if (roll(exp.chance)) experience = Math.min(1000000, experience + random.nextInt(exp.minimum, exp.maximum + 1));
            for (CommandGroup group : rule.commands) if (roll(group.chance)) commands.addAll(group.commands);
        }
        return new Plan(true, matched, stage, drops, experience, commands);
    }

    private static List<Source> sources(ContentRegistry content) {
        List<Source> result = new ArrayList<>();
        for (ItemDefinition item : content.items()) {
            ConfigurationSection crop = item.options().getConfigurationSection("crop.loots");
            if (crop != null) result.add(new Source("crop/" + item.id(), item.id(), crop));
        }
        for (var entry : new TreeMap<>(content.sections("block_loot")).entrySet()) {
            ContentId context = ContentId.parse(entry.getKey());
            ConfigurationSection config = entry.getValue();
            String selector = config.getString("type");
            if (selector == null) throw new IllegalArgumentException("Block loot requires a type selector: " + context);
            String requested = selector.contains(":") ? selector : context.namespace() + ":" + selector;
            var custom = content.find(requested);
            if (custom.isPresent()) {
                ConfigurationSection block = custom.get().options().getConfigurationSection("block");
                if (block == null || !block.getBoolean("enabled", true)) throw new IllegalArgumentException("Loot selector must be an enabled custom block: " + requested);
                selector = custom.get().id().toString();
            } else {
                Material material = Material.matchMaterial(selector);
                if (material == null || !material.isBlock() || material.isAir()) throw new IllegalArgumentException("Unknown block loot selector: " + selector);
                selector = "minecraft:" + material.name().toLowerCase(Locale.ROOT);
            }
            ConfigurationSection groups = new org.bukkit.configuration.MemoryConfiguration();
            ConfigurationSection group = groups.createSection("table");
            copy(config, group);
            ConfigurationSection drops = config.getConfigurationSection("items");
            if (drops != null) copy(drops, group.createSection("item_drops"));
            ConfigurationSection experience = config.getConfigurationSection("exp");
            if (experience != null) copy(experience, group.createSection("exp_drops"));
            result.add(new Source("block/" + selector, context, groups));
        }
        return List.copyOf(result);
    }

    private static void copy(ConfigurationSection source, ConfigurationSection target) {
        source.getValues(false).forEach((key, value) -> {
            if (value instanceof ConfigurationSection child) copy(child, target.createSection(key));
            else target.set(key, value);
        });
    }

    public void execute(Plan plan, Location location, Player player) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Crop loot requires the server thread.");
        Location origin = location.clone().add(0.5, 0.5, 0.5);
        if (rewardRouting != null) {
            if (!rewardRouting.allows(location.getBlock(), player)) return;
            origin = rewardRouting.destination(origin, player);
        }
        execute(plan, location, player, origin);
    }

    public void execute(Plan plan, Location location, Player player, Location destination) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Crop loot requires the server thread.");
        Location origin = destination.clone();
        if (origin.getWorld() == null || !origin.getWorld().isChunkLoaded(origin.getBlockX() >> 4, origin.getBlockZ() >> 4)) return;
        for (ItemStack drop : plan.drops) origin.getWorld().dropItemNaturally(origin, drop.clone());
        if (plan.experience > 0) origin.getWorld().spawn(origin, ExperienceOrb.class, orb -> orb.setExperience(plan.experience));
        for (Command action : plan.commands) {
            if (player == null && (!action.console || action.command.contains("{player}") || action.command.contains("%player%"))) continue;
            String command = action.command.replace("{player}", player == null ? "" : player.getName()).replace("%player%", player == null ? "" : player.getName());
            Bukkit.dispatchCommand(action.console ? Bukkit.getConsoleSender() : player, command);
        }
    }

    private ItemStack stack(String id) {
        return registry.get().find(id).isPresent() ? items.create(id, 1) : new ItemStack(Material.matchMaterial(id));
    }

    private static String reference(ContentRegistry content, ContentId context, String value) {
        if (value == null) throw new IllegalArgumentException("Crop loot requires an item: " + context);
        String namespaced = value.contains(":") ? value : context.namespace() + ":" + value;
        if (content.find(namespaced).isPresent()) return namespaced;
        Material material = Material.matchMaterial(value);
        if (material == null || material.isAir() || !material.isItem()) throw new IllegalArgumentException("Unknown crop loot item: " + value);
        return "minecraft:" + material.name().toLowerCase(Locale.ROOT);
    }

    private static List<ConfigurationSection> children(ConfigurationSection source, String singular, String plural) {
        List<ConfigurationSection> result = new ArrayList<>();
        ConfigurationSection single = source.getConfigurationSection(singular);
        if (single != null) result.add(single);
        ConfigurationSection group = source.getConfigurationSection(plural);
        if (group != null) for (String key : group.getKeys(false)) {
            ConfigurationSection child = group.getConfigurationSection(key);
            if (child == null) throw new IllegalArgumentException("Crop loot entry must be a section: " + plural + "/" + key);
            result.add(child);
        }
        return result;
    }

    private static int[] range(ConfigurationSection source) {
        int min = source.getInt("min_amount", 1);
        int max = source.getInt("max_amount", min);
        if (min < 0 || max < min || max > 4096) throw new IllegalArgumentException("Crop loot amounts must satisfy 0 <= min_amount <= max_amount <= 4096.");
        return new int[]{min, max};
    }

    private static double chance(ConfigurationSection source) {
        double value = source.getDouble("chance", 100);
        if (!Double.isFinite(value) || value < 0 || value > 100) throw new IllegalArgumentException("Crop loot chance must be between 0 and 100.");
        return value;
    }

    private static List<String> patterns(ConfigurationSection source, String key) {
        List<String> values = source.getStringList(key);
        if (values.size() > 128 || values.stream().anyMatch(value -> value.length() > 256)) throw new IllegalArgumentException("Crop loot filter exceeds its bounds: " + key);
        return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
    }

    private static boolean matches(List<String> patterns, String value) {
        if (patterns.isEmpty()) return true;
        String actual = value.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            if (glob(pattern, actual) || !pattern.contains(":") && actual.contains(":") && glob(pattern, actual.substring(actual.indexOf(':') + 1))) return true;
        }
        return false;
    }

    private static boolean glob(String pattern, String value) {
        int symbol = 0;
        int character = 0;
        int star = -1;
        int retry = 0;
        while (character < value.length()) {
            if (symbol < pattern.length() && pattern.charAt(symbol) == value.charAt(character)) { symbol++; character++; }
            else if (symbol < pattern.length() && pattern.charAt(symbol) == '*') { star = symbol++; retry = character; }
            else if (star >= 0) { symbol = star + 1; character = ++retry; }
            else return false;
        }
        while (symbol < pattern.length() && pattern.charAt(symbol) == '*') symbol++;
        return symbol == pattern.length();
    }

    private static boolean roll(double chance) { return chance >= 100 || chance > 0 && ThreadLocalRandom.current().nextDouble(100) < chance; }
}
