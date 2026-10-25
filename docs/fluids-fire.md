# Custom liquids and fire

This module implements liquid identity, source/flow levels, bounded updates, bucket bindings, fire ages, protection events and chunk persistence. Custom WATER colors use generated biome definitions registered by the server and applied through public Bukkit APIs. Headless probes verify the registry and world biome state; actual client appearance remains user verification. Custom FIRE variants now have an independently generated native resource-pack palette.

## Observed reference behavior

The supplied artifact is SHA-256 `cedc3ff88d57fb303e865b017d8e090da0f9f203d3922ff7887e27342f0c053d`. Its archive was authenticated by REA and the selected classes below were statically inspected with CFR 0.152. The original plugin was not run, and no original implementation or assets were copied.

- `itemsadder/m/sv` loads `liquids.<id>.color`; a namespaced liquid registry is separate from item definitions.
- `itemsadder/m/mu` binds `behaviours.liquid_bucket.name` and `behaviours.liquid_analyzer` to items.
- `itemsadder/m/ks` places real WATER, records identity and the original biome, applies custom biome color data, preserves the ID during selected flow operations, supplies the configured custom bucket on pickup, blocks ordinary bottles and custom bucket drinking, and restricts incompatible liquid colors in neighboring chunks.
- `itemsadder/m/si` recognizes `placed_model.type: FIRE` and obtains a rendering variant limited to 0–15.
- `itemsadder/m/qw` requires solid support, writes native FIRE age as the rendering variant, and stores the public logical fire age separately in its region data. The public fire API exposes placement, removal, lookup and age get/set.
- `itemsadder/m/azh` assigns custom renderer ages 1-15 and preserves unused ages; `azk` generates ten fire model families bound to the first configured texture.
- `itemsadder/m/mb` handles separate logical age, persistent support, spread, burn, ordinary age-zero rendering and native fade metadata cleanup. Its physics-driven approach was used as evidence, not copied as an implementation.

These observations identify the required public surface and data boundaries. They do not establish every original runtime interaction or prove client appearance. BlocksAdder uses independent source/flow processing rather than copying the original's engine hooks or its neighboring-chunk color restriction.

## Definition schema

```yaml
namespace: example
liquids:
  blue_water:
    color: '#55AAFF'
    material: WATER
    bucket-item: example:blue_bucket
    flow-ticks: 5
    max-distance: 7
    infinite-sources: false
items:
  blue_bucket:
    material: WATER_BUCKET
    display-name: '&bBlue Water Bucket'
    liquid-bucket: example:blue_water
  liquid_meter:
    material: STICK
    display-name: '&eLiquid Analyzer'
    liquid-analyzer: true
```

`bucket-item` references an existing custom item. A matching `liquid-bucket` binding can also infer the bucket when it uses the full ID and there is one matching item. Legacy `behaviours.liquid_bucket.name` and `behaviours.liquid_analyzer` are recognized. Modern `liquid-bucket` may be a string or a section containing `name`. The renderer and integrations can query `liquidColor(id)` and `liquidLocations(chunk)`. Native WATER tinting uses the registered biome palette described below.

Physical liquid materials are WATER or LAVA. Flow delays range from 1–1,200 server ticks; defaults are 5 for water and 30 for lava. Horizontal distance ranges from 1–7; defaults are 7 for water and 3 for lava. Sources have level zero, horizontal flow attenuates by one, and falling columns have level eight. Downward flow has priority. Flow crosses already loaded chunk boundaries without loading additional chunks. When a possible feeding neighbor chunk is unavailable, dependent decay waits rather than erasing potentially supplied flow. Optional infinite-source conversion requires two neighboring custom sources and solid support. Different liquid identities do not overwrite each other.

Sources can be collected only into their registered bucket. Flowing cells never produce a source bucket, preventing duplication by filling from a thin stream. Custom buckets cannot be drunk as milk or other vanilla consumables, and ordinary glass bottles/potions cannot extract ordinary water from indexed custom liquid. Survival placement consumes one custom bucket and returns an empty bucket; inventory overflow drops at the player. Creative placement retains the bucket. Empty-bucket pickup replaces one held bucket with the configured custom item.

```yaml
namespace: example
fires:
  blue_flame:
    material: FIRE
    variant: 11
    age: 0
    max-age: 120
    age-ticks: 20
    permanent: false
    rain-extinguishes: true
    spread-chance: 0.05
    burn-blocks: false
    damage: 1.0
items:
  blue_flame_item:
    material: PAPER
    display-name: '&bBlue Flame'
    fire-igniter: example:blue_flame
```

An item may instead contain an embedded `fire` section, using its own item ID as the fire ID. A `fire.name` reference selects a separately declared fire definition; `fire.enabled: false` disables the embedded binding. The physical material can be FIRE or SOUL_FIRE. Normal fire requires a solid supporting block; soul fire requires soul sand or soul soil.

`variant` reserves the native FIRE age (0–15) for resource-pack rendering. Logical `age` is stored separately, so `setFireAge` does not change the selected rendering variant. `max-age` ranges from 1–1,000,000; nonpermanent fire advances at `age-ticks` intervals and expires at its maximum. Permanent fire retains its logical age. Rain can extinguish eligible exposed fire when enabled. Spreading probability ranges from 0–1; burning actual neighboring blocks requires explicit `burn-blocks: true`. Native uncontrolled spread and aging are suppressed for indexed fire. Per-definition damage adjusts damage originating directly from that fire block.

The engine honors `minecraft:fire_spread_radius_around_player`: zero pauses updates, -1 permits updates without players, and positive values require a nonspectator player within that radius. The rule is resolved by namespaced `GameRule.getByName` through a cached public reflection adapter. Spigot 1.21.11 defines GameRule as an interface while Paper 26.3 defines it as a class; direct static calls compiled for either declaration fail on the other runtime. Minecraft documented the replacement of the older rules in [snapshot 25w44a](https://www.minecraft.net/en-us/article/minecraft-snapshot-25w44a).

## Original FIRE items and native renderer

The original item paths `specific_properties.block.placed_model.type: FIRE` and `behaviours.block.placed_model.type: FIRE` correspond to embedded `fire` definitions. The loader integration maps these to `auto: true`, `material: FIRE`, `lifecycle: native` and removes ordinary block placement. The environment item listener places these fires through `placeFire`, preserving their item ID and consuming one held item in survival mode. The separate public API routes through `isFireDefinition(id)`.

```yaml
namespace: example
items:
  blue_fire:
    material: PAPER
    display-name: Blue Fire
    texture: example:block/blue_fire
    fire:
      auto: true
      material: FIRE
      lifecycle: native
```

`fire-variant-ids.yml` permanently associates IDs with physical FIRE ages. Automatic assignments use 1-15; age zero stays vanilla. Candidate validation plans allocations without writing files. Successful reload saves the allocation history atomically. Removing a definition reserves its old age permanently; capacity exhaustion, conflicting explicit assignments and attempts to change an existing assignment are rejected. Back up this allocation file with the world. An explicit modern `variant: 0` deliberately overrides the otherwise vanilla age and receives a complete renderer family; it is never selected automatically. SOUL_FIRE has no compatible age palette and rejects automatic allocation.

The FIRE compiler generates ten model families per ID: floor0/1, side0/1, side_alt0/1, up0/1 and up_alt0/1. Generated models refer to their corresponding vanilla Minecraft model parents and override the `fire` texture; geometry and textures are not copied from the original plugin or from Mojang. The texture resolves from `fire.texture`, then the item's normalized first texture, otherwise `minecraft:block/fire_0`. Model prefixes resolve from `fire.model-prefix`, then `model-source`, otherwise `<namespace>:block/<id>`. User-provided model files can override the generated models during asset collection. A conflicting custom `assets/minecraft/blockstates/fire.json` is rejected.

`FireAssetCompiler.appendModels` runs before asset collection and `appendBlockstates` afterward. The generated multipart definition routes each allocated age through its own model family, preserving vanilla face conditions, rotations and equally weighted choices. Every unallocated age retains the vanilla branch behavior. The compiler reads already persisted IDs and does not allocate while building a pack.

### Native lifecycle and ordinary fire

Imported FIRE items use a separate native-style lifecycle. Their visible FIRE age is a stable renderer ID, while logical age is stored independently. The original `mb` listener advanced logical age on physics events and reset ordinary FIRE's visible age to zero. Directly reproducing that approach made both ordinary and custom stone fire immortal in an actual endpoint test. BlocksAdder therefore uses an independent bounded aging clock rather than reproducing the bug or imposing an arbitrary 15-second lifetime.

The aging clock uses a configurable default delay of 30 ticks plus 0-9 random ticks and probabilistic logical aging. Native spread, burn, weather and fade events still govern the corresponding native interactions. Neighbor updates do not accelerate age. No nearby fuel eventually extinguishes older fire; maximum-age fire can also expire on nonflammable support despite remaining neighboring fuel. Imported fires retain the observed persistent netherrack, magma and lava support policy, while ordinary fire uses the world's public infiniburn tag. The game rule radius pauses the aging clock appropriately. Water flow can extinguish indexed fire and clears its identity after the native replacement succeeds.

Ordinary FIRE ages are persisted separately in chunk data `blocksadder:native_fire_ages_v1`, allowing its renderer to remain age zero without losing aging state. Tracking is bounded by `environment.max-native-fire-cells`. When full, additional ordinary fire retains native behavior and may use a reserved age visually; it is never silently made immortal. The per-tick update budget is shared fairly between indexed content and tracked ordinary fire, including a budget of one. Unloaded chunks are not forced to stay resident. The default native aging delay can be adjusted explicitly for servers with different fire scheduling settings.

The implementation preserves public-event cancellation. Exact native fire simulation in every dimension, arbitrary third-party plugins that mutate FIRE without observable events, every fuel/TNT/weather combination and any momentary renderer update during native state changes still require acceptance. The user owns the rendered appearance check.

## Native liquid biome palette

WATER color variants live in the world-level `datapacks/blocksadder_liquids` directory. The plugin owns only that directory, verifies an ownership marker, writes atomically, and never replaces an unrelated pack. Paper 26.3 stores dimensions below the world root; the palette resolves the enclosing `level.dat` directory rather than writing into the dimension folder.

A new color, changed base biome template or updated data pack requires a clean server restart: biome registries load before plugins. Strict placement remains disabled until the exact generated biome key is registered. `environment.require-color-registry: false` permits uncolored diagnostic simulation and is unsuitable as proof of rendering support. `pendingColorBiomes()` reports missing registered variants; `colorReady(location,id)` checks the relevant base biome.

Each generated variant clones the active biome JSON and changes only `effects.water_color`. Climate, fog, spawn settings, features, environmental attributes and every other JSON field remain intact. The key contains the color, original biome ID and a template digest. Old generated IDs are retained because saved chunks may still reference them. WATER fog is preserved; the original artifact's deprecated datapack path also changed underwater fog, but the current original path has not established that requirement.

The resolver inventories enabled packs using public APIs on Spigot and Paper. It reads enabled folder and ZIP sources, including matching format overlays, before using runtime vanilla resources. Unsupported custom templates are refused rather than replaced with plains. If several enabled packs override the same biome, authoritative priority is not yet resolved: coloring for that biome is unavailable with a diagnostic. Existing packs with filter rules or built-in server/plugin resource sources also need further acceptance; the resolver does not promise complete Minecraft resource-manager emulation.

Biome resolution is one 4×4×4 quart. A quart can contain only one custom liquid color at a time; incompatible placement/spread is refused. Original biome IDs and tint membership are persisted per chunk. Removing the final custom cell restores the original quart biome, including after restart. Chunk refreshes are batched when viewers are present. Other ordinary water in the same quart necessarily shares the native biome tint. LAVA has its normal appearance; biome water tint does not recolor lava.

## Persistence and bounded work

```yaml
environment:
  max-cells-per-chunk: 2048
  max-loaded-cells: 65536
  updates-per-tick: 256
  require-color-registry: true
  max-color-biome-variants: 8192
  max-native-fire-cells: 65536
  native-fire-tick-delay: 30
```

The per-chunk index is `blocksadder:environment_v1`, stored in the chunk persistent data container. It records namespaced ID, kind, source status and logical level/age. Block appearance is never used as the custom identity. Changes are batched into one persistence write per affected chunk per update cycle; direct API/player changes flush immediately. Only resident chunks are held in the update queue. Unload saves their records and removes queued work; load restores and schedules work. Missing definitions retain their persisted identity records for a later content-pack restoration.

Successful external block placement or falling-block replacement forgets the replaced custom identity only after the native event succeeds. Cancelled replacements preserve it. Unexpected backing material changes prune stale metadata without producing custom loot. Pistons, explosions and direct entity changes respect indexed content and protection events. The engine does not scan whole worlds every tick or keep all content chunks loaded.

## Integration API

The plugin owns one `LiquidFireService` accessible through `BlocksAdderPlugin.environment()`. Integrate it once, register it as a Bukkit listener, validate candidates before registry publication, reload after world content, and close before world content. Every world operation requires the server thread.

- `placeLiquid(location, id, player)` and `placeFire(...)` return whether the requested placement succeeded. `player` may be null for an authoritative server/API operation.
- `remove(location, player)` removes a placed environment cell after applicable cancellation/permission checks.
- `identify(block)`, `kind(block)`, `level(block)` and `isSource(block)` inspect current consistent state. For fire, `level` is the logical age.
- `setFireAge(block, age)` changes the persisted logical age within the definition's bounds.
- `inspect(location)`, `count()`, `liquidColor(id)` and `liquidLocations(chunk)` support diagnostics/rendering integration.
- `colorReady(location,id)`, `pendingColorBiomes()` and `biomeDatapackDirectory()` expose palette preparation and registration state.
- `EnvironmentChangeEvent` is cancellable and exposes cloned location, content ID, kind, cause and nullable player. Causes distinguish placement, flow, level, decay, pickup, extinguish, age, spread, burn and removal.

Player placement additionally fires synthetic Bukkit placement/bucket/ignite events as appropriate. Flow invokes cancellable `BlockFromToEvent`; fluid level changes, fire spread, burning and fading invoke their matching Bukkit events. Respecting these events supplies an integration surface, but named region-protection adapters still require their own acceptance tests.

Player permission nodes are `blocksadder.liquid.place`, `blocksadder.liquid.pickup`, `blocksadder.liquid.break`, `blocksadder.fire.place` and `blocksadder.fire.break`. Administrator diagnostics and server API authorization are handled by their callers.

## Verification status

The module compiles with Java 21 against Spigot 1.21.11. An independently assembled module candidate is exercised on real isolated Spigot 1.21.11/Java 21 and Paper 26.3/Java 25 servers. The candidate replaces only this module in a frozen integrated plugin JAR; it is not evidence for unrelated later changes or a newly packaged release. Candidate SHA-256 `b2f400bf41b604641a3a0f8a7c2e261d56273e4f628527dc9b2bc3abe8558d01` passed 63/63 assertions on each of three consecutive starts per endpoint (six successful server starts, 378 passing assertions). Earlier preparation runs verified strict rejection before registration; these six final starts all reported registered palettes. The final combined Maven artifact still requires its own integration regression.

The probe checks real scheduled horizontal/falling flow, orphan decay, native-flow suppression, source limits, separate fire age/render variant, expiry, permanent/rain behavior, cancellation, reload and complete source persistence. Palette checks compare every generated JSON field with the authoritative template except the requested water color, test native registry membership and actual World.setBiome state after restart, and verify final-cell quart restoration. Active folder biome overrides and ZIP format overlays are included.

Paper 26.3 returned a warm Block.getTemperature value at a block whose getBiome was snowy_plains. Rain detection therefore additionally checks the authoritative biome temperature before treating precipitation as rain; the snowy-fire regression is covered on both endpoints. Exact precipitation boundaries for temperature-modifier biomes still need acceptance.

The native FIRE extension adds 14 pure allocator checks, including disk-write-free planning, stable assignments, retained tombstones, capacity rejection, deliberate age-zero compilation and conflicting blockstate rejection. An exhaustive comparator checks all 512 FIRE age/face combinations against each installed Mojang client resource definition from 1.21.11 and 26.2: 1,024 equivalent state selections and 40 vanilla parent-model checks. This verifies generated JSON/model references, not rendered appearance or direct inspection of a 26.3 client asset archive.

Default-delay candidate `5ccda9d2359f69f06dd11f4f73db9761ff5fefad2a78343ac0202517436448aa` passed four real-server lifetime/restart starts: 17 then 18 assertions on Spigot 1.21.11, and 18 on both Paper 26.3 starts. Each run observed 1,200 ticks, verifying ordinary/custom stone-fire extinction and ordinary/custom netherrack persistence, identity, logical age and stable renderer IDs across reload/restart. A previous candidate that left both stone fires immortal was rejected and corrected.

Final extension candidate `53ce2f7ec18906d0d27cbc7691242812121809f3e7c4a88a4249f6794708dbf4` passed 26/26 assertions on each of two consecutive starts on both Spigot 1.21.11 and Paper 26.3 (four successful starts, 104 assertions). It additionally covers maximum-age expiration with protected neighboring fuel, prevention of age acceleration by neighbor physics, water-flow extinguishing and prevention of premature renderer-driven fade. These accelerated-delay tests supplement the default-delay runs rather than replacing their lifetime evidence. The synthetic spread fixture is supported by netherrack so a deliberately old propagated fire does not expire before its identity assertion.

Player bucket inventory interactions, actual colored-fluid and fire client appearance, vanilla liquid chemistry, all replaceable-block/drop combinations and named protection adapters are not certified by the headless probe. A separate isolated decoder check copied the generated 1.21.11 palette into a fresh Paper 26.3 world. Paper explicitly enabled the copied pack and completed startup successfully, proving that these generated format-94.1 definitions did not block the format-121 registry bootstrap. This is narrower than a saved-world upgrade: existing chunks, old-ID climate/spawn semantics and subsequent template migration were not certified. Cross-version saved-world upgrades and multiple-pack priority remain explicit compatibility gates. Full original-plugin parity remains open until those behaviors have authoritative verification.
