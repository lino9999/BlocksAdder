# Crop growth and fertilizing

Development version: 1.1.0-SNAPSHOT. This behavior is additional to the historical 1.0.0 preview.

Crop item definitions support `growth-seconds`, `stages`, `min-light`, `bonemeal` and `bonemeal-multiplier`. A successful application advances `ceil(maximumStage * multiplier)` stages, clamped to maturity. The default multiplier is 0.2. Set `bonemeal: false` or a zero multiplier to disable fertilizing.

```yaml
crop:
  stages: [farm:sprout, farm:young, farm:flower, farm:ripe]
  growth-seconds: 240
  requires-farmland: true
  min-light: 9
  bonemeal: true
  bonemeal-multiplier: 0.2
  drops: ['farm:produce 2']
```

Right-clicking a crop's interaction entity or managed collision block with bone meal attempts growth. Survival players consume one item only after successful growth. Creative players retain the item. Mature crops, denied permissions and cancelled growth consume nothing. Light limits apply to natural growth; bone meal and explicit API growth bypass that limit. Fertilizing requires `blocksadder.crop.fertilize`, enabled by default. Adventure and spectator players cannot fertilize.

Fertilizing emits a cancellable Bukkit `BlockFertilizeEvent`; natural growth emits a cancellable Bukkit `BlockGrowEvent`. Both also emit `CustomCropGrowEvent`, with previous/next stage, cause and optional player. Display stages do not use vanilla age block data. Integrations should use the custom event when they need the exact custom stage. `BlocksAdderApi.growCrop(location, stages, player)` emits the custom event with cause `API` and clamps the increment to maturity.

Natural growth visits the bounded loaded-crop queue. It does not load chunks to grow crops. Display regeneration and stage persistence happen together; failed regeneration restores the previous stage. Protected mature harvesting resets the crop to stage zero.

## Evidence and remaining work

Static inspection of the supplied archive's `co` and `sk` classes established bone-meal advancement based on maximum age times a multiplier, creative-item preservation, stage models and light limits. This implementation was written independently. `avg_seconds_per_stage`, `min_light` and `multipliers.bonemeal` aliases work inside an item crop section.

The original top-level `crops` schema supports separate seed/crop identities, `models_by_items`, `models_by_textures`, generated hidden stages, maximum age, original bone-meal/light/timing aliases and vanilla/custom soil matching. Texture stages generate their declared model parent and texture slot.

Original-schema natural growth samples approximately once per second near players, within one chunk in each horizontal direction. Its probability follows `avg_seconds_per_stage`, the world's public random-tick game rule, farmland moisture and `multipliers.max_moisture`, `rain`, `thunder` and `snow`. Defaults match the inspected reference. Zero random ticks prevents growth. Bounded queue delays accumulate probability for active loaded crops; chunk loading does not replay offline growth. Light is checked above the crop. Cold-biome classification preserves the underlying template for generated liquid-color biomes.

`required_spacing` rejects other crops at the same height whose horizontal Chebyshev distance is smaller than the configured spacing. Values from 0 to 32 are accepted. `crops.max-in-area` defaults to 1000 and `crops.area-radius` to 32 blocks, rounded outward to chunks as in the reference. Cached counts cover resident managed crops without loading neighboring chunks. Removal, chunk unload and reload update those counts. Per-chunk and global content limits also apply.

`default_hitbox.width`, `length` and `height`, `hitboxes.<stage>` overrides and `hitbox_auto_height_step` produce stage-specific interaction geometry. Dimensions range from 0.1 to 4 blocks. Automatic height is clamped to 0.1–1. Explicit stage sections retain default dimensions for omitted fields. Rectangular footprints use a bounded union of native square interactions that exactly covers the requested bounds. All helper entities share the crop's lifecycle and protection checks.

## Destruction and conditional rewards

Original-schema crops enable natural destruction automatically. Canonical crop definitions may opt in with `natural-destruction: true`; their earlier support-block protection remains the default otherwise.

Water and lava flow, drying farmland, trampling, piston extension, supporting-block break/burn and explosions can remove these crops. The corresponding original loot triggers are `WATER`, `LAVA`, `TRAMPLE`, `FARMLAND_DRY` and `BREAK`. The inspected reference routes destruction of a support by explosion through `FARMLAND_DRY`; that mapping is retained. `CustomCropDestroyEvent` separately exposes the actual cause, stage, ID, location and optional player, so integrations can distinguish an explosion from drying.

Preparation emits both the crop-destruction event and the existing cancellable content-break event. Native cancellation, late cancellation and drop suppression are honored. Removal runs only after the source operation actually changes its block, retaining the replacement water, lava or pushed block. A pending crop remains identifiable while additional growth/harvesting/break attempts are rejected; the committed operation removes helpers and persistence once. Canceled operations release that lock. Reload drains confirmed changes and discards canceled or unchanged plans.

Managed supporting blocks propagate crop protection and drop suppression through the same removal plan. Rewards are emitted after the backing data and placement record are committed. Original crops without any configured loot table do not invent a default seed reward; where a table exists, the original seed-fallback flag still applies. Lava may subsequently burn non-fireproof dropped items through ordinary Minecraft behavior.

Density-dependent viewer rendering and billboard variants remain parity requirements. Moving furniture footprints, all combinations of stacked custom supports and reentrant source-event reloads still require dedicated acceptance.

Actual protocol-player scenarios on Spigot 1.21.11 and Paper 1.21.11 passed 141 assertions each, including the earlier gameplay cases, crop weather/random-tick/player-proximity behavior, rectangular stage hitboxes, spacing, density, reload and count release. Exact candidate hashes and scope are recorded in [development-verification.json](development-verification.json). Rendered appearance is reserved for the user's own checks.

Destruction candidate `630d9838f1fc6d3ed43a313e5d7c7eb2f3f900a486a2970cdc5d3fbd986a013e` passed 68 assertions on Spigot 1.21.11 and 68 on Paper 26.3, both Java 25 and without ProtocolLib. Actual scheduled water flow, powered piston movement and native explosions destroyed managed crops, preserved replacement blocks and emitted exact configured rewards. Additional public-event cases cover lava, drying, entity trampling, cancellation, drop suppression, pending-operation locks and custom supporting-block removal. Reload completes a confirmed pending removal and does not duplicate rewards. Lava rewards are counted at uncancelled item spawning, before normal lava burning. Fixture chunks are held resident; pre-existing saved reward entities are cleared after authoritative chunk entity loading. These tests do not certify native player trampling or every named protection integration.
