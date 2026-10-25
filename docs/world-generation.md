# Automatic world generation

`WorldGenerationService` adds ore/block population, surface decoration, cave floor/ceiling decoration and native tree population to newly generated chunks. Definitions are namespaced content sections. Work runs on the server thread through a bounded, round-robin queue; it never deliberately requests missing chunks or holds production chunk tickets.

## Reference evidence

The supplied `ItemsAdder.jar` has SHA-256 `cedc3ff88d57fb303e865b017d8e090da0f9f203d3922ff7887e27342f0c053d`. Its authenticated archive inventory is described in [reverse-engineering.md](reverse-engineering.md). Constant-pool inspection and focused CFR inspection of `itemsadder/m/wt`, `wv`, `ww`, `wy`, `wz` and their `xa`–`xd` population controllers establish these original behaviors:

- `blocks_populators`, with the older alias `worlds_populators`, accepts `block`, `worlds`, `vein_blocks`/`amount`, `chunk_veins`/`iterations`, `chunk_chance`/`chance`, `min_height`, `max_height`, `replaceable_blocks` and `biomes`.
- Block generation chooses a random cluster origin and a bounded number of offsets around it. The original chance comparison multiplies a random double by 100; these fields are percentages, and `-1` skips the chance test.
- `surface_decorators` accepts `block`, world/height/biome filters, `amount`, percentage `chance` and `bottom_blocks`. It uses the motion-blocking height map without leaves.
- `cave_decorators` adds `position`, with `SURFACE` for a floor and `CEILING` for an upper support.
- `trees_populators` accepts custom `log` and `leaves`, native Bukkit `tree_type`, `amount`, `iterations`, percentage `chance` and the same filters. The chance is evaluated for each tree iteration.
- A missing/empty biome or material restriction is unrestricted in the inspected base class. World names support wildcard matching. Original implementation uses Bukkit populators and compatibility internals; those implementation details are not copied.

These are static observations. The original plugin was not executed. The independent service preserves the feature families, filters and original key aliases, while using public APIs and separate persistence/scheduling.

## Block and ore population

```yaml
namespace: example
populators:
  ruby_ore:
    block: example:ruby_ore
    worlds: ['world', 'mining_*', '!mining_disabled']
    biomes: [minecraft:plains, minecraft:forest]
    min-height: -48
    max-height: 32
    vein-blocks: 8
    chunk-veins: 4
    chance: 35
    replaceable-blocks: [STONE, DEEPSLATE]
    seed-offset: 0
```

`populators`, `blocks_populators` and `worlds_populators` are aliases for this family. Hyphenated and original underscored field names are accepted. `amount` aliases `vein-blocks`; `iterations` aliases `chunk-veins`. A full custom item ID must reference an enabled custom block. A vanilla block material is also accepted. Targets must be non-air blocks without gravity.

`chance`/`chunk-chance`/`chunk_chance` are percentages from 0–100; `-1` means always. An explicit `probability: 0.35` provides the same chance in a 0–1 form. Zero never runs the rule. Height bounds are inclusive and clamped to the current world's build limits; minimum must be lower than maximum. Empty `replaceable-blocks` is unrestricted, matching the reference, so configure an explicit replacement list for normal ore rules. Existing indexed custom content is not replaced.

Each rule has a deterministic random stream derived from world seed, chunk coordinates, complete rule ID and `seed-offset`. Other rules do not perturb that stream. Rejected placements do not reroll a completed plan. Coordinate clustering stays within the originating chunk for ores.

## Surface and cave decorators

```yaml
namespace: example
decorators:
  flowers:
    type: surface
    block: example:flower
    worlds: ['world']
    min-height: 60
    max-height: 160
    amount: 6
    chance: 50
    bottom-blocks: [GRASS_BLOCK, MOSS_BLOCK]
  cave_growth:
    type: cave-floor
    block: example:crystal
    worlds: ['world']
    min-height: -48
    max-height: 20
    amount: 4
    bottom-blocks: [STONE, DEEPSLATE]
```

The original `surface_decorators` and `cave_decorators` sections are also accepted. For `cave_decorators`, use `position: SURFACE` or `position: CEILING`. Canonical `decorators.type` accepts `surface`, `cave-floor` and `cave-ceiling`.

Surface attempts inspect the height map and the immediately lower support. Cave planning scans the configured chunk volume incrementally and uses reservoir sampling over eligible air positions; this avoids retaining every cave air block in memory. Floors require a lower support; ceilings require an upper support. Biome, height and permitted support-material filters apply before a position is planned. A supplied liquid support material can be selected deliberately; absent a ground filter, support must be solid.

## Native custom tree population

```yaml
namespace: example
tree_populators:
  ruby_trees:
    worlds: ['world']
    biomes: [minecraft:forest]
    min-height: 60
    max-height: 160
    amount: 1
    iterations: 2
    chance: 40
    log: example:ruby_log
    leaves: example:ruby_leaves
    tree_type: BIRCH
    bottom_blocks: [GRASS_BLOCK]
```

`tree_populators` and `trees_populators` are aliases. Inline `log`/`leaves` can reference custom blocks or vanilla blocks. Alternatively use `tree: example:configured_tree` to reference an existing `trees` definition with `trunk`, `leaves` and `tree-type`/`tree_type`. Native type names are validated against the running server's Bukkit `TreeType`, so newer tree types require a server that supplies them.

Native shapes come from the public `RegionAccessor.generateTree(Location, Random, TreeType, Predicate<BlockState>)` API with a seeded `Random`. The predicate records proposed states and always returns false, keeping capture separate from mutation. Proposed logs and leaves are replaced with the configured content; other native tree states retain their vanilla material. Vanilla leaves are made persistent so custom log backing blocks do not cause unintended leaf decay. A cancellable `StructureGrowEvent` exposes the proposed mapped shape before it is committed.

Manual tree definitions can opt into the same native pipeline:

```yaml
trees:
  old_oak:
    native-type: BIG_TREE
    seed: 12345
    trunk: example:oak_log
    leaves: OAK_LEAVES
```

`generateNativeTree(location, definitionId)` returns synchronously after the protected transaction succeeds or fails. Without an explicit seed, world seed, tree ID and origin coordinates determine the seed. Native soil changes and vanilla log axes are retained. Custom log orientations use the source axis/facing and requested yaw through the world placement provider, so native palette blocks receive the correct carrier state. Tree definitions without `native-type` retain the existing height/radius manual tree path. Native definitions share shape mapping, events and rollback with automatic tree generation.

## Owned light as virtual air

Generation receives `setVirtualAirPredicate(Predicate<Block>)` and `setVirtualAirCandidates(Function<Chunk, Collection<Location>>)`. Main wires the block behavior service's ownership queries. Only generated `LIGHT` whose recorded baseline was air, cave air or void air is virtual air. An existing player light that was temporarily brightened by a custom source remains protected and is excluded from candidate enumeration.

Ore material filters, floor/ceiling decoration and frozen-plan execution treat verified virtual air as air. External `LIGHT` remains protected even when a saved plan recorded its exact state. Plans store logical air for helper-light targets, while transaction rollback stores the actual live block data and restores the exact light state if a later placement fails.

Native generators read the real world before proposing states, so the native planner temporarily masks only enumerated, predicate-confirmed helper lights inside its already loaded 5×5 region. It restores every masked state in `finally`, including rejected/capped captures and generator exceptions, before returning a shape or firing the mapped structure event. It neither scans all blocks in that region nor loads its chunks. Masking is bounded to 16,384 cells and 65,536 candidate inspections per capture; exceeding either cap rejects the attempt without applying a tree. This bounded synchronous operation can exceed the scheduler's soft time deadline. Capture-no-write assertions refer to the observable state after this temporary masking transaction has been restored.

The native planner waits until a 5×5 neighborhood of chunks is already resident. It does not load that neighborhood itself. A pending chunk can therefore wait near an unloaded edge until normal server activity supplies the needed chunks. Captured states are checked again for loaded targets, build limits and a 4,096-state tree cap. A tree commits as one bounded transaction, is charged for all its states against the queue's work budget, and rolls back its own custom/vanilla mutations if a staged placement fails. This atomic tree operation can exceed the soft millisecond deadline, which is checked between operations.

On actual Spigot 1.21.11, seeded OAK and BIRCH captures produced real native shapes, repeated seeds produced identical states, rejected/capped captures left the world unchanged, and the tested captures triggered no additional chunk-load events. That is bounded evidence for those shapes/build; it does not prove that every native generator implementation on every server version reads within the preloaded margin.

## Persistence and scheduling

```yaml
generation:
  enabled: true
  work-per-tick: 512
  max-millis-per-tick: 4
```

`work-per-tick` ranges from 1–16,384. `max-millis-per-tick` ranges from 1–40 and is a soft deadline between work units. Each normal planning scan or block mutation is a work unit. One bounded native tree capture/transaction is atomic.

Only `ChunkLoadEvent.isNewChunk()` automatically starts generation. Existing unmarked chunks are not silently populated after installing/reloading content. An explicit server-thread `generate(chunk)` API is available for controlled tooling and tests; it still refuses completed/cancelled chunks. Per-rule world filters support `*`, `?` and `!` exclusions, with exclusions taking precedence. Missing world filters default to all worlds; an explicit empty world list selects none.

Chunk persistent data stores `generation_state_v1`, the frozen `generation_plan_v1` and `generation_cursor_v1`. States are `unseen`, `pending`, `complete` and `cancelled`. No mutations occur until planning has completed. An interrupted planning pass can be reconstructed; an executing pass resumes its saved plan/cursor. Completed chunks are not rerolled on reload, restart or definition changes. Work stops when its owning chunk unloads and resumes when that marked chunk loads again. Plan/cursor data is removed after completion.

The plan payload records whether each target is custom or vanilla and canonicalizes parsed block-state strings. A temporarily missing custom definition leaves its frozen job pending; it cannot silently become an untagged vanilla backing block. Restoring the definition resumes the same plan. Short custom references are canonicalized before storage and rollback. Payload version 3 also stores placement face and yaw; readers retain compatibility with development formats 1 and 2.

Plans retain exact target positions and before/after block data. Each mutation rechecks its backing state and current custom identity, so a changed target is skipped rather than blindly overwritten. Each transaction also fires `GenerationBlockEvent` and Bukkit `BlockFormEvent`; cancellation prevents that transaction. `GenerationChunkEvent` can cancel an entire newly queued chunk before planning. These are natural-generation hooks with no player actor, rather than impersonated player placement events.

Custom event block data comes from the same world placement provider used to commit native palette blocks. Mapped custom state edits that disagree with that provider are rejected. Native transactions check chunk residency before each stage and never force a chunk to load. Public Spigot chunk unload events are not cancellable; another plugin forcibly unloading a target during a synchronous callback can prevent immediate rollback there, so cross-plugin forced unloads during these callbacks are not certified as atomic.

The plan cap is 16,384 states per chunk. Validation bounds rule counts, percentages, heights, biome names, block references, tree types and material restrictions. Native shape expansion is capped again during planning. A malformed saved plan is preserved and logged rather than erased or repeatedly applied. Services expose `pendingChunks()`, `completedChunks()` and `state(chunk)` for diagnostics.

## Verification boundary

The generation module candidate passed **44 first-process assertions and 4 restart assertions on each of Spigot 1.21.11 and Paper 26.3**, with zero failures in the final endpoint runs. This includes ore mutation, idempotence and deterministic replay; chunk/block cancellation; candidate validation; surface/cave decorators; native mapped trees; full-tree cancellation and rollback after a staged custom-block rejection; malformed saved-plan preservation; missing custom-target definition recovery; and a saved positive execution cursor with actual ore already placed before process exit. The second process resumes that partially applied plan and preserves the completed marker.

Final temporary logs are `.work/generation-server-stable21/console-{1,2}.log` and `.work/generation-server-paper263-final/console-{1,2}.log`; `.work/generation-results.json` records their checksums and the tested candidate class/JAR payloads. The module test candidate overlays the freshly compiled production generation classes on the integrated project build, avoiding concurrent build interference. The parent verification report must separately record final uniform release-JAR/build results. Test fixtures explicitly hold/release their own resident chunk tickets; production does not.

The later owned-light/manual-native candidate passed **42 first-process assertions and 4 restart assertions on each endpoint**, including unchanged seeded native shapes with helper lights in the canopy, exact restoration after a deliberate public tree-API exception, external light protection, bounded candidate rejection, no additional chunk loads, live-light rollback, actual cave decorators, BIG_TREE horizontal log axes mapped to native note-block variants, native soil/leaves, structure cancellation, staged rollback, and format-3 face/yaw persistence. The earlier 44+4 regression suite was also rerun successfully on that same candidate on both endpoints. `.work/generation-bridge-results.json` records exact logs and hashes; native/bridge fixtures are `.work/generation-bridge-native-server-{spigot21,paper263}-first/`, with regression fixtures under `.work/generation-bridge-regression-server-{spigot21,paper263}-first/`. These are server and protocol-state checks; the user owns visual acceptance.

No real client, full biome distribution study, multi-plugin region-protection matrix or every native tree type has been certified by these headless tests. Native implementation reads outside the preloaded margin are not generally disproven by the tested OAK/BIRCH cases. Existing generated chunks remain untouched unless explicitly enqueued by an operator/integration.
