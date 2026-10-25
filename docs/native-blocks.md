# Native custom block palettes

BlocksAdder can represent a custom block with a reserved vanilla block state instead of an item display. The server stores the real carrier block, and the generated resource pack associates that state with the configured model. Custom content identity, protection, drops and chunk persistence remain owned by `WorldContentService`.

## Configuration

```yaml
items:
  carved_stone:
    material: PAPER
    display-name: Carved Stone
    block:
      mode: REAL_NOTE
      texture: example:block/carved_stone
      directional-mode: FURNACE
      placed_model:
        rotx: 0
        roty: 0
```

`block.mode` takes precedence over `block.placed_model.type` and `placed_model.type`. The loader also normalizes original `behaviours.block` and `specific_properties.block` sections. The default mode is `ENTITY`, retaining the display-based block path.

| Mode | Carrier | Available states | Automatic global IDs |
| --- | --- | ---: | --- |
| `REAL` | Brown/red mushroom block and mushroom stem | 189 | 0–62, 64–126, 128–190 |
| `REAL_NOTE` | Note block | 750 | 256–1005 |
| `REAL_TRANSPARENT` | Chorus plant | 62 | 193–254 |
| `REAL_WIRE` | Tripwire | 127 | 1008–1134 |
| `ENTITY` | Existing configured collision plus display | No palette limit | None |
| `TILE` | Existing configured collision plus display fallback | No palette limit | None |

Aliases are `MUSHROOM`, `NOTE`/`NOTE_BLOCK`, `CHORUS`, `TRIPWIRE`, and `DISPLAY`. `FIRE` belongs to the separate environment service. `TILE` does not create an original-style spawner tile renderer.

`block.state-id` optionally reserves the first orientation's global ID; `block.id` is an alias. An explicit ID must belong to its palette and must not be reserved by any other current or historical allocation. Explicit reservations are considered before automatic allocation. This is an independent configuration extension; the inspected original uses its allocation caches.

Directional modes are `NONE` (one state), `FURNACE` (four horizontal directions), `ALL` (six directions), `LOG` (three axes), and `DROPPER` (six directions with dropper rotations). `block.directional: true` selects `FURNACE`. `block.placed_model.directional_mode` and `placed_model.directional_mode` are aliases for `block.directional-mode`. `rotx` and `roty` accept multiples of 90, normalized to 0–270. Each distinct resulting rotation consumes one palette state. Vertical selection is available through the public placement overload accepting a `BlockFace`.

`block.model` or `model-source` can reference an existing model instead of the generated `namespace:block/item_id` cube model. `block.texture` selects the generated cube texture, falling back to the item's `texture`, then `minecraft:block/stone`. Referenced models and textures must be supplied by the content author.

Weighted model alternatives can be defined as follows. These are random alternatives, rather than separate layers combined into one block.

```yaml
block:
  mode: REAL
  placed_model:
    custom_variants:
      first:
        model: example:block/carved_a
        weight: 3
        x: 0
        y: 0
        uvlock: false
      second:
        model: example:block/carved_b
        weight: 1
```

Each weight is between 1 and 1,000,000. Variant x/y rotations are added to the selected directional rotation. Missing model namespaces use the item's namespace.

## Stable storage and lifecycle

`plugins/BlocksAdder/native-block-ids.yml` stores schema-version 1 and a list of item, mode, orientation and slot allocations. Namespaced IDs containing dots and slashes are stored as scalar values, avoiding YAML path ambiguity. Back up this file with the world and content definitions.

Allocations are never automatically recycled. Removing an item, changing its mode or changing its rotation keeps its historical reservations. Adding unrelated items and reloads leave existing allocations stable. Deleted definitions do not produce active resource-pack models, but their slots remain reserved. Restoring the definition restores its previous allocation. A changed palette or orientation does not migrate already placed blocks: remove and place them through the content API when intentionally migrating definitions. Keep native modes and rotations stable while their blocks are in use.

Candidate validation reads and checks the complete history, explicit collisions, palette capacity, rotations, variants and server block-data parsing without changing files or publishing mappings. Reload validates again, writes the allocation file through an atomic replacement where supported, then publishes immutable mappings and cloned block-data cache values. Malformed histories, duplicate slots and invalid states fail explicitly. The compiler reads existing allocations and cannot allocate IDs from its worker thread; lifecycle order is native reload, world reload, then resource-pack compilation.

`NativeBlockService` implements `WorldBlockStates`. `WorldContentService` requests native data before placement, tracks only committed placements and exact saved states reconstructed from chunk PDC, removes displays for native modes, and untracks before removal or chunk unloading. `restore` runs before identity consistency checks. The persisted block state is retained exactly on reload and restart.

## State protection and vanilla behavior

Only indexed, successfully tracked custom blocks receive native state protection. Their note sounds, note tuning, tripwire physical interactions, redstone state changes, growth, spread, fade, form and physics updates are intercepted through public events. A round-robin audit repairs property drift for loaded tracked blocks, bounded by `native-blocks.repairs-per-tick` (default 256; range 1–16384). Neither tracking nor repair loads an unloaded chunk. Chunk unloads remove tracking; world reconstruction restores it.

Repair is restricted to the same carrier material. Replacing a custom carrier with air or a different material untracks it and allows the world index to prune its old metadata. An unrelated plugin's material replacement is never recreated by the repair loop. Directly editing properties on an indexed carrier is treated as drift; remove it through the BlocksAdder API when intentionally replacing it with ordinary vanilla content of the same material.

Untracked vanilla blocks are untouched by these listeners. Resource-pack state reservation necessarily changes the appearance of any ordinary block with an allocated state, as in the original palette approach. The compiler preserves vanilla models for all unallocated states, including the default all-face mushroom states, default chorus plant, harp note blocks and disconnected unpowered tripwire. Vanilla connections, note instruments and redstone behavior are not globally disabled to avoid reserved states.

Generated blockstates use full-state custom multipart conditions and vanilla fallback parts that exclude precisely those allocations. The fallback references installed vanilla model names rather than embedding Mojang textures or models. Mushroom face rotations, chorus weighted alternatives/UV locking, tripwire connection shapes and the ordinary note cube are reproduced. Negated property terms and AND/OR conditions are supported by the inspected Minecraft 1.21.11 and 26.2 client parsers. Actual client appearance is reserved for the user's visual acceptance checks.

Carrier physics, collision, transparency and light behavior come from Minecraft. This implementation does not add arbitrary block light levels, change server collision geometry, eliminate all vanilla palette interactions, supply missing content assets, or implement original `TILE` rendering internals. Those boundaries must not be reported as full native-block parity.

## Static evidence and independent reconstruction

The inspected original JAR has SHA-256 `cedc3ff88d57fb303e865b017d8e090da0f9f203d3922ff7887e27342f0c053d`. It was inspected statically and was not executed. Relevant classes are `itemsadder.m.rz` (state encoding), `itemsadder.m.si` and `sm` (placed model and directional definitions), `dev.lone.itemsadder.resourcepack.f` (allocation caches), and `dev.lone.itemsadder.resourcepack.azh` (blockstate/model output).

Observed mushroom and chorus bits, from least significant to most significant, are west, up, south, north, east and down. Note IDs encode 15 instruments from basedrum through pling, 25 notes and two powered states, reserving the harp instrument. Tripwire bits encode powered, west, disarmed, south, north, east and attached.

The original automatic tripwire range includes 1135, which wraps its seven encoded bits to the same all-false state as reserved ID 1007. BlocksAdder deliberately rejects 1135 and offers 127 unique tripwire states. Original log messages also disagree with the actual mushroom/chorus loop capacities; the table above reports the verified unique states instead.

The implementation, cache schema and compiler are independently authored. Original implementations and packaged assets are not copied. `.work/blockstate-*` contains ignored static inspection and disposable verification material, never release sources.

## Verification

Isolated probes on 2026-10-25 completed with these results:

| Endpoint | Initial process | Restarted process | Failed assertions |
| --- | ---: | ---: | ---: |
| Spigot 1.21.11, Java 21 | 56 passed | 56 passed | 0 |
| Paper 26.3 build 218, Java 25 | 56 passed | 56 passed | 0 |

Checks cover 1,128 unique server-valid native states, actual indexed placements for all four modes without display entities, exact-state persistence across a process restart, same-material repair before identity checks, real neighbor updates, bounded audit repair, synthetic cancellation checks, untouched ordinary vanilla listeners, external material replacement, normal removal lifecycle, historical reservations, malformed caches, capacity and explicit collisions, and worker asset compilation. The final blockstate pass restores managed mappings after asset overrides while preserving user-supplied model geometry.

Both generated fixtures were independently evaluated across 1,534 carrier state combinations against the installed vanilla 1.21.11 definitions: the 14 allocated directional fixture states selected exactly their custom model group, and every unallocated state retained vanilla model references, rotations, UV locking, weights and weighted ordering. No missing vanilla model reference was found. This includes default states that are deliberately not allocated.

The verification candidates contain freshly compiled `blockstate` classes over the integrated development JAR, including the real world placement/tracking hooks. A final project build still needs the root artifact's overall regression checks. Concise hashes and log paths are retained in ignored `.work/blockstate-results.json`. Paper emitted an existing OSHI Windows performance-counter warning outside the plugin; no plugin assertion or linkage failure remained. Earlier failed attempts identified a fixed nullable-ID constructor error and a corrected probe lifecycle sequence; only the final complete runs are reported above. Server and structural checks do not substitute for the user's visual checks.
