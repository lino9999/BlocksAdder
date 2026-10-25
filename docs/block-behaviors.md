# Block light and explosion behavior

`BlockBehaviorService` supplies custom block lighting and explosion behavior through the public Bukkit API. It implements the `WorldBlockBehavior` lifecycle contract; world identity, protection events, item drops and persistent placements remain in `WorldContentService`.

```yaml
block:
  light-level: 12
  explosion-proof: false
  hardness: 0.5
  blast-resistance: -1
```

Original aliases are `light_level`, `no_explosion` and `blast_resistance`. Light levels are -1–15, with -1 and 0 creating no light. Resistance -1 derives `(hardness + 0.2) * 3`. Instant hardness -1 is supported; its derived negative resistance does not protect against explosions. Explicit resistance 0 disables resistance protection. `explosion-proof: true` always protects a listed custom block.

## Original light behavior and owned lights

The inspected original does not change arbitrary block emission in a custom light engine. Its `bay` helper delegates through `bfd`/`bfe` to the selected version adapter. The inspected `ack.c(Block,int)` adapter places vanilla `LIGHT` blocks at all six adjacent cells, ordered down, east, north, south, up and west. It replaces only air or existing light blocks, clamps positive levels to 15, and removes neighboring air/light cells for level 0. The fallback implementation `bff` does nothing. `abl.ba` removes light by delegating to level 0. The original constructor clamps its nominal -1 default to 0, which can delete neighboring unrelated lights.

BlocksAdder independently reproduces six-neighbor vanilla light placement with additional ownership checks:

- Solid blocks and fluids are preserved. A completely enclosed block does not emit through its solid neighbors, matching the original helper approach.
- Each target light records its previous block data. Removing the final source restores that data, including an existing `LIGHT` level or air variant.
- Multiple sources share a target using the highest level. An existing brighter light is never dimmed.
- External solid replacements are preserved. External changes to an owned light become its new restoration baseline; waterlogged light data is retained.
- Unlit blocks do not remove unrelated lights.

`block-behavior.light-updates-per-tick` limits round-robin target updates, default 256 and range 1–16384. Targets in unloaded chunks wait for chunk loading. Sources may remain in unloaded chunks, and no neighboring chunk is loaded by lighting. Chunk callbacks reconcile stale sources after the world index reconstructs placements. Public placement accepts replaceable `LIGHT` cells so neighboring custom blocks remain placeable.

`plugins/BlocksAdder/block-lights.log` stores source and ownership records. Incremental writes run on one worker thread; shutdown compacts the current snapshot through an atomic file replacement where available and waits for pending storage. World operations remain on the server thread. Back up this ownership file with the world and plugin content. Invalid stored records fail explicitly; storage failures are logged instead of deleting unknown lights. This normal reload/restart persistence is not a promise of atomic world-and-file recovery after a process or filesystem failure.

## Explosions

For entity explosions, the service remembers the radius observed in `ExplosionPrimeEvent`. Without a remembered radius it uses 1, as does the inspected original, including block explosions. Each custom block present in the vanilla explosion candidate list independently survives resistance when:

```text
radius * (0.7 + random * 0.6) <= (resistance + 0.3) * 0.3
```

The resistance test runs only for positive resistance. Preserved custom cells also shield candidate cells whose center ray crosses them. Grid traversal handles negative coordinates and simultaneous axis crossings without loading or scanning world chunks. Only the event's candidates are considered: a carrier that Minecraft excludes entirely, such as `BARRIER`, cannot be made destructible through this post-selection rule. Legacy imported `fallback_vanilla_block` maps to the display carrier, whose original default is gray concrete.

At `HIGHEST`, managed custom cells and shielded cells are reserved out of vanilla destruction. Custom destruction runs one task later, checks the final event cancellation state, verifies the chunk is still loaded, and checks placement identity and revision. A block removed and replaced with the same ID during that interval is not destroyed. Actual removal calls the normal world break API, honoring `CustomContentBreakEvent` cancellation and its drop setting. Neither explosion lists nor world blocks are mutated from `MONITOR` handlers.

Explosion destruction calls `WorldContentService.breakExplosion`, which uses the shared `ContentLootService` with the explosion cause. Normal and explosion removals share protection and persistence while retaining their different base-drop rules. Directional native variants retain one content ID and therefore naturally drop the base item. The original creates directional clones and maps those clones back to the base identity.

The inspected original `lh` drop flags have these semantics: `drop_when_mined: true` enables the ordinary custom drop. With ordinary drops disabled, `drop_on_silk_touch: true` or `drop_on_shears: true` can enable the corresponding tool-specific drop. Both overrides default false. They are not filters that disable ordinary drops for those tools.

## Block-specific loot evidence

The original selector path is `loots.blocks.<table_name>.type`. A selector can name a vanilla material or a custom block ID. `ty` registers custom tables by their resolved block definition and vanilla tables by material; `biomes` and `worlds` filter tables before their outputs are rolled.

```yaml
loots:
  blocks:
    carved_stone:
      enabled: true
      type: example:carved_stone
      worlds: [world]
      items:
        gem:
          item: example:gem
          min_amount: 1
          max_amount: 2
          chance: 100
          ignore_fortune: false
      exp:
        mining:
          min_amount: 1
          max_amount: 3
          chance: 100
      stop_on_first_success: false
```

Original command groups use `commands.<group>.chance` and `commands.<group>.<action>.command`, `as_console` (default true), and `silent` (default false). `drop_only_first` aliases `stop_on_first_success`. The stop flag affects item entries after the first successful positive item drop; XP entries and command groups still execute. This block-table parser has no crop age/decrease rules or block-table-specific `tools` list.

The cause-specific rules are distinct:

| Cause | Table behavior | Base item behavior |
| --- | --- | --- |
| Ordinary custom block break | Add matching table outputs, subject to tool exclusions | Ordinary configured base drop remains additive |
| Silk/shears override with ordinary base drops disabled | Skip table outputs when the special base override succeeds | Emit the enabled special base drop |
| Explosion | Execute every matching table, including with `drop_when_mined: false` | Matching tables suppress the base drop even when all their chances fail; without a matching table, `drop_when_mined` controls the fallback |
| Public item-only loot preview | Roll matching table item entries without XP/commands | Optionally append the original item when requested and ordinary drops are enabled |
| Vanilla block break | Add eligible matching table outputs | Preserve vanilla drops |

For ordinary custom breaks, `tx` excludes loot for Silk Touch and for shears on leaf carriers. The vanilla path additionally excludes string/tripwire shears cases. The original has a configurable spawner exception. Vanilla ageable blocks must be fully mature, and optional tile NBT conditions are checked before a vanilla table runs. A cross-version implementation must state unsupported arbitrary NBT predicates explicitly instead of silently accepting them.

The original Fortune amount expression expands its random amount range by one for enchanted tools. The reconstruction intentionally should not retain that off-by-one defect; any corrected distribution must be documented rather than described as numerically identical. A matched table is a selection result, not proof that an item, XP orb or command was emitted. Shared drop providers must keep those concepts separate to avoid duplicate base drops.

## Evidence and verification

Static evidence comes from the supplied JAR with SHA-256 `cedc3ff88d57fb303e865b017d8e090da0f9f203d3922ff7887e27342f0c053d`: `si` configuration parsing, `ql` fields/defaults, `bcy.p` clamping, `bay`, `bfd`, `bfe`, `bff`, `abl` and `ack` lighting, `mc` explosion handling/grid shielding, and `lh` tool-specific drops. The JAR was not executed, and its implementation and assets were not copied.

The isolated integrated candidates completed on 2026-10-25 with these results:

| Endpoint | Initial process | Restarted process | Failed assertions |
| --- | ---: | ---: | ---: |
| Spigot 1.21.11, Java 21 | 70 passed | 14 passed | 0 |
| Paper 26.3 build 218, Java 25 | 70 passed | 14 passed | 0 |

The complete candidate JAR member hashes match between endpoints; ZIP container hashes differ only because of container metadata. Checks include actual server block-light propagation, source overlap and restoration, strict unloaded-chunk boundaries, ownership persistence, real explosion damage and custom drops, resistance/proof shielding, late cancellation, custom break cancellation and same-ID replacement protection. Exact output checks prove normal table drops remain additive, explosion table drops replace the base item, matched zero-chance explosion tables suppress the fallback, and explosion tables execute with ordinary base drops disabled. Earlier probe failures were caused by fixture tickets retaining the target chunk and by interpreting an already-unloaded chunk's `unloadChunk` return value as a failure; final checks assert actual residency before and after operations. Production never adds those fixture tickets.

Hashes and exact logs are retained in ignored `.work/blockbehavior-results.json`. The base integrated snapshot was `9b0d29d2f12f291e33091a813f7ba071b460ba981f0658ad89c62668f688e98f`, with only freshly compiled behavior classes overlaid before testing. Later unrelated root changes still require the final artifact's overall regression. Paper emitted its existing OSHI Windows performance-counter warning outside the plugin. Visual checks belong to the user.
