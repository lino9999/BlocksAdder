package io.github.lino9999.blocksadder.generation;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.world.WorldContentService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Predicate;
import java.util.function.Function;
import java.util.Collection;

public final class WorldGenerationService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private final WorldContentService content;
    private final NamespacedKey stateKey;
    private final NamespacedKey planKey;
    private final NamespacedKey cursorKey;
    private final Map<ChunkKey, Job> pending = new LinkedHashMap<>();
    private final ArrayDeque<ChunkKey> queue = new ArrayDeque<>();
    private List<GenerationRule> rules = List.of();
    private BukkitTask task;
    private boolean closed = true;
    private long completed;
    private Predicate<Block> virtualAir = block -> false;
    private Function<Chunk, Collection<Location>> virtualAirCandidates = chunk -> List.of();

    public WorldGenerationService(JavaPlugin plugin, Supplier<ContentRegistry> registry, WorldContentService content) {
        this.plugin = plugin;
        this.registry = registry;
        this.content = content;
        stateKey = new NamespacedKey(plugin, "generation_state_v1");
        planKey = new NamespacedKey(plugin, "generation_plan_v1");
        cursorKey = new NamespacedKey(plugin, "generation_cursor_v1");
    }

    public void validate(ContentRegistry candidate) {
        requireThread();
        GenerationRule.load(candidate);
        candidate.sections("trees").forEach((id, config) -> NativeTreeDefinition.read(candidate, id, config));
    }

    public void setVirtualAirPredicate(Predicate<Block> predicate) {
        requireThread();
        virtualAir = java.util.Objects.requireNonNull(predicate);
    }

    public void setVirtualAirCandidates(Function<Chunk, Collection<Location>> candidates) {
        requireThread();
        virtualAirCandidates = java.util.Objects.requireNonNull(candidates);
    }

    public NativeTreePlanner.Result planNativeTree(Location origin, org.bukkit.TreeType type, long seed, int maxBlocks) {
        requireThread();
        return NativeTreePlanner.plan(origin, type, seed, maxBlocks, this::virtualAir, virtualAirCandidates);
    }

    public boolean generateNativeTree(Location origin, String id) {
        requireThread();
        if (closed || origin.getWorld() == null || !Float.isFinite(origin.getYaw())) return false;
        var definitions = registry.get().sections("trees");
        var config = definitions.get(id);
        String canonical = id;
        if (config == null && !id.contains(":")) {
            var matches = definitions.entrySet().stream().filter(entry -> entry.getKey().endsWith(":" + id)).limit(2).toList();
            if (matches.size() == 1) { canonical = matches.getFirst().getKey(); config = matches.getFirst().getValue(); }
        }
        if (config == null) throw new IllegalArgumentException("Unknown or ambiguous native tree definition: " + id);
        if (!config.getBoolean("enabled", true)) return false;
        NativeTreeDefinition definition = NativeTreeDefinition.read(registry.get(), canonical, config);
        if (definition == null) return false;
        long selectedSeed = definition.seed() == null ? seed(origin.getWorld().getSeed(), origin.getBlockX(), origin.getBlockZ(), canonical, origin.getBlockY()) : definition.seed().longValue();
        NativeTreePlanner.Result captured = planNativeTree(origin, definition.type(), selectedSeed, 4096);
        if (captured.status() != NativeTreePlanner.Status.READY) return false;
        List<GenerationPlan.Mutation> changes = mapTree(origin, captured, definition.type(), definition.trunk(), definition.leaves(), canonical, canonical);
        return changes != null && !changes.isEmpty() && applyBatch(origin.getWorld(), changes);
    }

    private boolean virtualAir(Block block) {
        return block.getType() == Material.LIGHT && block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4) && virtualAir.test(block);
    }

    private boolean air(Block block) {
        return block.getType().isAir() || virtualAir(block);
    }

    private boolean matchesBefore(Block block, String before) {
        if (block.getType() == Material.LIGHT && !virtualAir(block)) return false;
        if (block.getBlockData().getAsString().equals(before)) return true;
        return Bukkit.createBlockData(before).getMaterial().isAir() && air(block);
    }

    public void reload() {
        requireThread();
        validate(registry.get());
        List<GenerationRule> next = GenerationRule.load(registry.get());
        rules = next;
        closed = false;
        for (World world : Bukkit.getWorlds()) for (Chunk chunk : world.getLoadedChunks()) {
            if (state(chunk).equals("pending") && !pending.containsKey(ChunkKey.of(chunk))) resume(chunk);
        }
        schedule();
    }

    public int pendingChunks() {
        requireThread();
        return pending.size();
    }

    public long completedChunks() {
        requireThread();
        return completed;
    }

    public Map<String, String> pendingStates() {
        requireThread();
        Map<String, String> result = new LinkedHashMap<>();
        pending.forEach((key, job) -> result.put(key.world() + ":" + key.x() + ":" + key.z(), job.plan == null
                ? "planning " + job.ruleIndex + "/" + job.definitions.size() + (job.planner == null ? "" : " " + job.planner.rule.id() + " attempt=" + job.planner.attempt + " scan=" + job.planner.caveCursor)
                : "executing " + job.cursor + "/" + job.plan.mutations().size()));
        return Map.copyOf(result);
    }

    public String state(Chunk chunk) {
        requireThread();
        return chunk.getPersistentDataContainer().getOrDefault(stateKey, PersistentDataType.STRING, "unseen");
    }

    public boolean generate(Chunk chunk) {
        requireThread();
        if (closed || !plugin.getConfig().getBoolean("generation.enabled", true) || !chunk.isLoaded() || !state(chunk).equals("unseen")) return false;
        List<GenerationRule> selected = rules.stream().filter(rule -> rule.matches(chunk.getWorld())).toList();
        GenerationChunkEvent event = new GenerationChunkEvent(chunk, selected.stream().map(GenerationRule::id).toList());
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            chunk.getPersistentDataContainer().set(stateKey, PersistentDataType.STRING, "cancelled");
            return false;
        }
        chunk.getPersistentDataContainer().set(stateKey, PersistentDataType.STRING, "pending");
        enqueue(new Job(chunk, selected, null, 0));
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (closed) return;
        if (event.isNewChunk()) generate(event.getChunk());
        else if (state(event.getChunk()).equals("pending")) resume(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        ChunkKey key = ChunkKey.of(event.getChunk());
        pending.remove(key);
        queue.remove(key);
    }


    private void resume(Chunk chunk) {
        try {
            String encoded = chunk.getPersistentDataContainer().get(planKey, PersistentDataType.STRING);
            GenerationPlan plan = encoded == null ? null : GenerationPlan.decode(encoded);
            int cursor = chunk.getPersistentDataContainer().getOrDefault(cursorKey, PersistentDataType.INTEGER, 0);
            if (cursor < 0 || plan != null && cursor > plan.mutations().size()) throw new IllegalArgumentException("Invalid saved generation cursor.");
            enqueue(new Job(chunk, rules.stream().filter(rule -> rule.matches(chunk.getWorld())).toList(), plan, cursor));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Generation data for chunk " + chunk.getX() + ", " + chunk.getZ() + " was preserved but not resumed: " + exception.getMessage());
        }
    }

    private void enqueue(Job job) {
        ChunkKey key = ChunkKey.of(job.chunk);
        if (pending.putIfAbsent(key, job) != null) return;
        queue.addLast(key);
        schedule();
    }

    private void schedule() {
        if (!closed && task == null && !pending.isEmpty()) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        int budget = Math.clamp(plugin.getConfig().getInt("generation.work-per-tick", 512), 1, 16384);
        long deadline = System.nanoTime() + Math.clamp(plugin.getConfig().getLong("generation.max-millis-per-tick", 4), 1, 40) * 1000000;
        int waiting = 0;
        while (budget > 0 && !queue.isEmpty() && System.nanoTime() < deadline) {
            ChunkKey key = queue.removeFirst();
            Job job = pending.get(key);
            if (job == null) continue;
            if (!job.chunk.isLoaded()) {
                pending.remove(key);
                continue;
            }
            int used;
            try { used = job.step(); }
            catch (RuntimeException exception) {
                pending.remove(key);
                plugin.getLogger().warning("Generation stopped for chunk " + key.x() + ", " + key.z() + ": " + exception.getMessage());
                continue;
            }
            if (job.done) {
                complete(job.chunk);
                pending.remove(key);
                waiting = 0;
            } else queue.addLast(key);
            if (used == 0) {
                if (++waiting >= pending.size()) break;
            } else {
                budget -= used;
                waiting = 0;
            }
        }
        if (pending.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    private void complete(Chunk chunk) {
        chunk.getPersistentDataContainer().set(stateKey, PersistentDataType.STRING, "complete");
        chunk.getPersistentDataContainer().remove(planKey);
        chunk.getPersistentDataContainer().remove(cursorKey);
        completed++;
    }

    @Override
    public void close() {
        requireThread();
        closed = true;
        if (task != null) task.cancel();
        task = null;
        pending.clear();
        queue.clear();
    }

    private final class Job {
        private final Chunk chunk;
        private final List<GenerationRule> definitions;
        private final List<GenerationPlan.Mutation> planned = new ArrayList<>();
        private final Set<String> occupied = new HashSet<>();
        private GenerationPlan plan;
        private int cursor;
        private int ruleIndex;
        private Planner planner;
        private boolean done;

        private Job(Chunk chunk, List<GenerationRule> definitions, GenerationPlan plan, int cursor) {
            this.chunk = chunk;
            this.definitions = definitions;
            this.plan = plan;
            this.cursor = cursor;
        }

        private int step() {
            if (plan == null) return planStep();
            if (cursor >= plan.mutations().size()) {
                done = true;
                return 1;
            }
            GenerationPlan.Mutation mutation = plan.mutations().get(cursor);
            int end = cursor + 1;
            if (!mutation.group().isEmpty()) while (end < plan.mutations().size() && plan.mutations().get(end).group().equals(mutation.group())) end++;
            List<GenerationPlan.Mutation> batch = plan.mutations().subList(cursor, end);
            for (var change : batch) if (!chunk.getWorld().isChunkLoaded(change.x() >> 4, change.z() >> 4)
                    || change.custom() && registry.get().find(change.target()).isEmpty()) return 0;
            applyBatch(chunk.getWorld(), batch);
            int cost = end - cursor;
            cursor = end;
            chunk.getPersistentDataContainer().set(cursorKey, PersistentDataType.INTEGER, cursor);
            return cost;
        }

        private int planStep() {
            if (planner == null) {
                if (ruleIndex >= definitions.size()) {
                    plan = new GenerationPlan(planned);
                    chunk.getPersistentDataContainer().set(planKey, PersistentDataType.STRING, plan.encode());
                    chunk.getPersistentDataContainer().set(cursorKey, PersistentDataType.INTEGER, 0);
                    return 1;
                }
                planner = new Planner(definitions.get(ruleIndex));
            }
            int used = planner.step();
            if (planner.done) {
                planner = null;
                ruleIndex++;
            }
            return used;
        }

        private void add(Block block, String target, BlockData after, String group, String rule) {
            add(block, target, after, group, rule, BlockFace.UP, 0);
        }

        private void add(Block block, String target, BlockData after, String group, String rule, BlockFace face, float yaw) {
            String key = block.getX() + "," + block.getY() + "," + block.getZ();
            if (planned.size() >= 16384) throw new IllegalArgumentException("Generation plan exceeds 16384 changes.");
            if (!occupied.add(key)) return;
            ItemDefinition custom = registry.get().find(target).orElse(null);
            String canonical = custom == null ? Material.matchMaterial(target).name() : custom.id().toString();
            String before = virtualAir(block) ? Material.AIR.createBlockData().getAsString() : block.getBlockData().getAsString();
            planned.add(new GenerationPlan.Mutation(block.getX(), block.getY(), block.getZ(), canonical, before, after.getAsString(), group, rule, custom != null, face, yaw));
        }

        private final class Planner {
            private final GenerationRule rule;
            private final Random random;
            private int attempt;
            private int vein;
            private int centerX;
            private int centerY;
            private int centerZ;
            private int caveCursor;
            private int eligible;
            private final List<Block> caveSample = new ArrayList<>();
            private Block waitingTreeBase;
            private int treeCluster = -1;
            private boolean done;

            private Planner(GenerationRule rule) {
                this.rule = rule;
                random = new Random(seed(chunk.getWorld().getSeed(), chunk.getX(), chunk.getZ(), rule.id(), rule.seedOffset()));
                if (rule.kind() != GenerationKind.TREE && random.nextDouble() >= rule.probability()) done = true;
            }

            private int step() {
                if (done) return 1;
                return switch (rule.kind()) {
                    case ORE -> ore();
                    case SURFACE -> surface(false);
                    case TREE -> surface(true);
                    case CAVE_FLOOR, CAVE_CEILING -> cave();
                };
            }

            private int ore() {
                int min = Math.max(chunk.getWorld().getMinHeight(), rule.minHeight());
                int max = Math.min(chunk.getWorld().getMaxHeight() - 1, rule.maxHeight());
                if (min > max || vein >= rule.iterations()) { done = true; return 1; }
                if (attempt == 0) {
                    centerX = random.nextInt(16);
                    centerY = min + random.nextInt(max - min + 1);
                    centerZ = random.nextInt(16);
                }
                int horizontal = Math.max(rule.amount() / 2, 2);
                int vertical = Math.max(rule.amount() / 4, 2);
                int x = Math.floorMod(centerX + random.nextInt(horizontal) - rule.amount() / 4, 16);
                int y = Math.clamp(centerY + random.nextInt(vertical) - rule.amount() / 8, min, max);
                int z = Math.floorMod(centerZ + random.nextInt(horizontal) - rule.amount() / 4, 16);
                Block block = chunk.getBlock(x, y, z);
                boolean virtual = virtualAir(block);
                if ((block.getType() != Material.LIGHT || virtual) && (rule.replaceable().isEmpty() || rule.replaceable().contains(virtual ? Material.AIR : block.getType())) && rule.biome(block) && content.identify(block).isEmpty()) add(block, rule.target(), targetData(rule.target(), null), "", rule.id());
                if (++attempt >= rule.amount()) { attempt = 0; vein++; }
                return 1;
            }

            private int surface(boolean tree) {
                if (attempt >= rule.amount()) { done = true; return 1; }
                if (tree) {
                    int clusterSize = rule.amount() / rule.iterations();
                    int cluster = attempt / clusterSize;
                    if (treeCluster != cluster) {
                        treeCluster = cluster;
                        if (random.nextDouble() >= rule.probability()) {
                            attempt = Math.min(rule.amount(), (cluster + 1) * clusterSize);
                            return 1;
                        }
                    }
                }
                int x = waitingTreeBase == null ? random.nextInt(16) : waitingTreeBase.getX() & 15;
                int z = waitingTreeBase == null ? random.nextInt(16) : waitingTreeBase.getZ() & 15;
                int absoluteX = (chunk.getX() << 4) + x;
                int absoluteZ = (chunk.getZ() << 4) + z;
                int y = waitingTreeBase == null ? chunk.getWorld().getHighestBlockYAt(absoluteX, absoluteZ, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1 : waitingTreeBase.getY();
                if (y < rule.minHeight() || y > rule.maxHeight() || y >= chunk.getWorld().getMaxHeight()) { attempt++; return 1; }
                Block block = chunk.getBlock(x, y, z);
                if (!suitableAir(block, BlockFace.DOWN)) { waitingTreeBase = null; attempt++; return 1; }
                if (!tree) {
                    add(block, rule.target(), targetData(rule.target(), null), "", rule.id());
                    attempt++;
                    return 1;
                }
                Location origin = block.getLocation();
                waitingTreeBase = block;
                NativeTreePlanner.Result nativePlan = planNativeTree(origin, rule.tree().type(), seed(chunk.getWorld().getSeed(), absoluteX, absoluteZ, rule.id(), attempt + rule.seedOffset()), 4096);
                if (nativePlan.status() == NativeTreePlanner.Status.WAITING_FOR_CHUNKS) return 0;
                waitingTreeBase = null;
                int treeAttempt = attempt++;
                if (nativePlan.status() != NativeTreePlanner.Status.READY) return 1;
                String group = rule.id() + "/" + treeAttempt;
                List<GenerationPlan.Mutation> mapped = mapTree(origin, nativePlan, rule.tree().type(), rule.tree().trunk(), rule.tree().leaves(), group, rule.id());
                if (mapped == null) return 1;
                for (var change : mapped) add(chunk.getWorld().getBlockAt(change.x(), change.y(), change.z()), change.target(), Bukkit.createBlockData(change.after()), group, rule.id(), change.face(), change.yaw());
                return Math.max(1, mapped.size());
            }

            private int cave() {
                int min = Math.max(chunk.getWorld().getMinHeight() + 1, rule.minHeight());
                int max = Math.min(chunk.getWorld().getMaxHeight() - 2, rule.maxHeight());
                int height = max - min + 1;
                if (height <= 0) { done = true; return 1; }
                int volume = height * 256;
                if (caveCursor >= volume) {
                    for (Block block : caveSample) add(block, rule.target(), targetData(rule.target(), null), "", rule.id());
                    done = true;
                    return Math.max(1, caveSample.size());
                }
                int y = min + caveCursor / 256;
                int column = caveCursor++ % 256;
                Block block = chunk.getBlock(column & 15, y, column >> 4);
                BlockFace support = rule.kind() == GenerationKind.CAVE_CEILING ? BlockFace.UP : BlockFace.DOWN;
                if (suitableAir(block, support)) {
                    int selected = random.nextInt(++eligible);
                    if (caveSample.size() < rule.amount()) caveSample.add(block);
                    else if (selected < rule.amount()) caveSample.set(selected, block);
                }
                return 1;
            }

            private boolean suitableAir(Block block, BlockFace support) {
                if (!air(block) || content.identify(block).isPresent() || !rule.biome(block)) return false;
                Block backing = block.getRelative(support);
                if (air(backing) || !backing.getType().isSolid() && !rule.ground().contains(backing.getType())) return false;
                return rule.ground().isEmpty() || rule.ground().contains(backing.getType());
            }
        }
    }

    private BlockData targetData(String id, BlockData nativeData) {
        return targetData(id, nativeData, face(nativeData), 0);
    }

    private BlockData targetData(String id, BlockData nativeData, BlockFace face, float yaw) {
        ItemDefinition definition = registry.get().find(id).orElse(null);
        if (definition != null) {
            return content.placementData(definition.id().toString(), face, yaw);
        }
        Material material = Material.matchMaterial(id);
        if (material == null || !material.isBlock()) throw new IllegalArgumentException("Unknown generation block: " + id);
        BlockData data = material.createBlockData();
        if (nativeData != null && nativeData.getMaterial() == material) data = nativeData.clone();
        else if (data instanceof Orientable oriented && nativeData instanceof Orientable source && oriented.getAxes().contains(source.getAxis())) oriented.setAxis(source.getAxis());
        if (data instanceof Leaves leaves) leaves.setPersistent(true);
        return data;
    }

    private boolean applyBatch(World world, List<GenerationPlan.Mutation> mutations) {
        for (var mutation : mutations) {
            if (!world.isChunkLoaded(mutation.x() >> 4, mutation.z() >> 4)) return false;
            Block block = world.getBlockAt(mutation.x(), mutation.y(), mutation.z());
            if (!matchesBefore(block, mutation.before()) || content.identify(block).isPresent()) return false;
            if (mutation.custom() && !content.placementData(mutation.target(), mutation.face(), mutation.yaw()).getAsString().equals(mutation.after())) return false;
            GenerationBlockEvent custom = new GenerationBlockEvent(block, mutation.rule(), mutation.target());
            Bukkit.getPluginManager().callEvent(custom);
            if (custom.isCancelled()) return false;
            BlockState state = block.getState();
            state.setBlockData(Bukkit.createBlockData(mutation.after()));
            BlockFormEvent formed = new BlockFormEvent(block, state);
            Bukkit.getPluginManager().callEvent(formed);
            if (formed.isCancelled() || !formed.getNewState().getBlockData().getAsString().equals(mutation.after())) return false;
        }
        List<GenerationPlan.Mutation> applied = new ArrayList<>();
        Map<GenerationPlan.Mutation, BlockData> observed = new HashMap<>();
        try {
            for (var mutation : mutations) {
                if (!world.isChunkLoaded(mutation.x() >> 4, mutation.z() >> 4)) throw new IllegalStateException("Generation target chunk is no longer loaded.");
                Block block = world.getBlockAt(mutation.x(), mutation.y(), mutation.z());
                if (!matchesBefore(block, mutation.before()) || content.identify(block).isPresent()) throw new IllegalStateException("Generation target changed during events.");
                observed.put(mutation, block.getBlockData().clone());
                boolean custom = mutation.custom();
                if (custom) {
                    block.setType(Material.AIR, false);
                    Location placement = block.getLocation();
                    placement.setYaw(mutation.yaw());
                    if (!content.place(placement, mutation.target(), null, mutation.face())) {
                        block.setBlockData(observed.get(mutation), false);
                        throw new IllegalStateException("Custom generation placement was rejected.");
                    }
                } else block.setBlockData(Bukkit.createBlockData(mutation.after()), false);
                applied.add(mutation);
            }
            return true;
        } catch (RuntimeException exception) {
            for (int index = applied.size() - 1; index >= 0; index--) {
                var mutation = applied.get(index);
                if (!world.isChunkLoaded(mutation.x() >> 4, mutation.z() >> 4)) continue;
                Block block = world.getBlockAt(mutation.x(), mutation.y(), mutation.z());
                if (mutation.custom()) content.rollbackPlacement(block.getLocation(), mutation.target());
                if (content.identify(block).isEmpty()) block.setBlockData(observed.get(mutation), false);
            }
            plugin.getLogger().fine("Generation transaction rolled back: " + exception.getMessage());
            return false;
        }
    }

    private List<GenerationPlan.Mutation> mapTree(Location origin, NativeTreePlanner.Result captured, org.bukkit.TreeType type, String trunk, String leaves, String group, String rule) {
        World world = origin.getWorld();
        List<BlockState> states = new ArrayList<>();
        Map<String, TreeTarget> targets = new HashMap<>();
        for (var generated : captured.blocks()) {
            if (!world.isChunkLoaded(generated.x() >> 4, generated.z() >> 4)) return null;
            Block block = world.getBlockAt(generated.x(), generated.y(), generated.z());
            if (content.identify(block).isPresent() || block.getType() == Material.LIGHT && !virtualAir(block)) return null;
            Material material = generated.data().getMaterial();
            String selected = Tag.LOGS.isTagged(material) ? trunk : Tag.LEAVES.isTagged(material) ? leaves : material.name();
            BlockFace face = face(generated.data());
            BlockState state = block.getState();
            state.setBlockData(targetData(selected, generated.data(), face, origin.getYaw()));
            states.add(state);
            targets.put(position(block), new TreeTarget(selected, face));
        }
        StructureGrowEvent event = new StructureGrowEvent(origin.clone(), type, false, null, states);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return null;
        if (event.getBlocks().size() > 4096) throw new IllegalArgumentException("A tree event exceeded the 4096-block limit.");
        List<GenerationPlan.Mutation> changes = new ArrayList<>();
        Set<String> accepted = new HashSet<>();
        for (BlockState state : event.getBlocks()) {
            if (!state.getWorld().equals(world) || !world.isChunkLoaded(state.getX() >> 4, state.getZ() >> 4)) return null;
            Block block = state.getBlock();
            String key = position(block);
            TreeTarget target = targets.get(key);
            if (target == null || !accepted.add(key) || content.identify(block).isPresent()) return null;
            ItemDefinition custom = registry.get().find(target.id).orElse(null);
            if (custom != null && !content.placementData(custom.id().toString(), target.face, origin.getYaw()).equals(state.getBlockData())) return null;
            String canonical = custom == null ? Material.matchMaterial(target.id).name() : custom.id().toString();
            String before = virtualAir(block) ? Material.AIR.createBlockData().getAsString() : block.getBlockData().getAsString();
            changes.add(new GenerationPlan.Mutation(block.getX(), block.getY(), block.getZ(), canonical, before, state.getBlockData().getAsString(), group, rule, custom != null, target.face, origin.getYaw()));
        }
        return changes;
    }

    private static BlockFace face(BlockData data) {
        if (data instanceof Orientable oriented) return switch (oriented.getAxis()) { case X -> BlockFace.EAST; case Y -> BlockFace.UP; case Z -> BlockFace.NORTH; };
        if (data instanceof org.bukkit.block.data.Directional directional) return directional.getFacing();
        return BlockFace.UP;
    }

    private record TreeTarget(String id, BlockFace face) {}

    private static String position(Block block) {
        return block.getX() + "," + block.getY() + "," + block.getZ();
    }

    private static long seed(long worldSeed, int x, int z, String rule, long offset) {
        long value = worldSeed ^ (long) x * 341873128712L ^ (long) z * 132897987541L ^ offset;
        for (int index = 0; index < rule.length(); index++) value = (value ^ rule.charAt(index)) * 1099511628211L;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        return value;
    }

    private static void requireThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("World generation must run on the server thread.");
    }

    private record ChunkKey(UUID world, int x, int z) {
        private static ChunkKey of(Chunk chunk) { return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()); }
    }
}
