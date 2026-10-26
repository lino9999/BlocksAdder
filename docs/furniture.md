# Furniture development

This module is under active development. The source is independently implemented; the following original observations do not imply that every behavior has completed runtime verification.

## Shipped behavior recovered

The supplied original JAR SHA-256 is `cedc3ff88d57fb303e865b017d8e090da0f9f203d3922ff7887e27342f0c053d`. Static classes `mu`, `jl`, `js`, `jx`, `kn`, `jw`, `kg`, `kz`, `jn` and `jp` establish these behaviors:

- Furniture carriers are armor stands, item frames, glowing item frames and item displays. Rotation can be continuous or snapped, with an opposite direction option and separate wall, floor and ceiling permissions.
- Hitboxes have length, width, height and offsets on all axes. The parser limits dimensions to four and offsets to five; solid furniture fills selected entity-bound cells with barriers. The old cleanup deletes barriers in that region without ownership information. The reconstruction retains exact prior states and restores only its own current barrier cells.
- Item displays accept translation, scale, left/right quaternion or axis-angle rotations, item display transform, render bounds and three legacy transform presets. Ceiling placement flips the display; wall placement changes position, yaw and pitch.
- `furniture_sit` carries seat height, opposite direction, all-solid-cell seating, front-view and body-rotation options. Native pre-unsit cancellation exists. `sit_all_solid_blocks` is marked deprecated in the original configuration class.
- `furniture_consumable` uses an ordered `furniture_list`. Interacting advances to the next stage or removes the last, then adds hunger and saturation, each capped at twenty. Stage replacement must be an atomic protected transaction, rather than separate independent break/place calls.
- Furniture and block trade machines use a Bukkit merchant with one required input and an optional second input. Recipes permit ten thousand uses, reward experience and assign one thousand villager experience. Custom ingredients compare namespaced identity; vanilla inputs must not carry custom identity. Eligible ingredient enchantments transfer to the output, respecting `blocked_enchants` and its `all` value. Optional title, permission and paired GUI glyphs are supported by the old configuration.
- Complex furniture references a custom entity and overrides static state, maximum per chunk, light, solidity, dropping, fixed rotation, fixed position, opposite direction, permissions, sounds and face-specific placement offsets. Part of its original spawn method failed structured decompilation and still requires bytecode-level recovery before exact claims.

No original implementation, models, textures, sounds or other assets are copied. The original JAR is never executed.

## Service boundary

`FurnitureService` implements `WorldFurniture`. The root world service retains anchor records, native protection events, drops and owned helper cleanup. The module receives placement/load/unload/removal callbacks and a helper registration consumer. Every carrier, interaction entity and seat is registered with that same owner. Explicit helper removal updates the owner's indexes before removing entities.

Solid footprint transactions preflight every affected resident chunk and protection event before anchor commit, then compare exact snapshots again before writing. Footprints never load neighboring chunks. `furniture-cells.log` records exact AIR or LIGHT baselines and ownership, including removals whose target chunks are currently unavailable. Deferred cleanup runs when those chunks load. External replacement blocks and unrelated barriers are preserved. Durable claims and removal tombstones use bounded checksummed transaction frames on a writer thread; placement/removal wait for the forced batch before changing owned blocks. Background cleanup records are ordered before subsequent transactions. An incomplete final frame is discarded on restart; completed checksum failures stop loading safely. Graceful close compacts an atomic snapshot. This protects module footprint writes; the root anchor staging gap remains an explicit open whole-transaction crash concern.

Rectangular interaction regions use a bounded union of overlapping native square Interaction strips. This preserves the rectangle without changing an entity's private bounding box. Existing single-anchor furniture remains supported. Bukkit world/entity operations remain on the server thread.

The light service supplies the pre-illumination baseline for covered LIGHT cells. A temporarily raised player light restores its original level, while AIR-baseline temporary lights disappear when their source is gone. External edits are preserved. Footprint restoration queues a resident-only refresh for sources that remain active. The new integration passed [64 targeted checks](light-furniture-verification.json), with the same candidate also passing the nine-build bundle matrix; this is separate from the older furniture phase results below.

## Current acceptance work

The following basic-phase checks completed on Java 25 with Java 21 bytecode:

| Endpoint | Scope | Passed | Failed |
| --- | --- | ---: | ---: |
| Spigot 1.21.11 | Two real protocol clients: seats and native merchant transactions | 24 | 0 |
| Paper 1.21.11 build 132 | Same complete JAR payload and client fixture | 24 | 0 |
| Paper 26.3 build 218 | Placement/protection/rollback/carriers and strict chunk boundaries | 33 | 0 |
| Paper 26.3 build 218, restart | Ownership, exact LIGHT baselines, orphan claims and incomplete WAL tail | 10 | 0 |

The two client candidates have identical complete member hashes, canonical payload SHA-256 `dd50183792dd2002f16a26e48afa084b3d9a1f26db1ee0daf46d906f0a9a33eb`. The Paper 26.3 baseline used an earlier separate integrated candidate, so these results are scoped individually. Exact manifests and logs are in ignored `.work/furniture-results.json`. Spigot's first boundary fixture retained the target through its source plugin ticket; removing it allowed the source itself to unload and exposed an internal server reload error. Its boundary fixture is being corrected; Paper's clean boundary result is not asserted for Spigot.

Client checks cover separate occupied seats, native mounting, cancellable interaction/sit/unsit, removal ejecting both passengers, permission/pre-open cancellation, custom identity guards, swapped inputs, vanilla/custom substitutions, native result quantities, shift batches, replayed clicks, enchant inheritance and blocking, full inventory and actual block-machine right click. Two real public API differences were fixed: armor stands reject Mob-only equipment drop-chance setters, and Spigot players reject Entity.setRotation; front-view adjustment now uses a cancellable teleport before mounting.

Atomic consumable stage transactions and deferred rollback repairs compile and await the root adapter's tests. Moving gravity/complex entity anchors, body-rotation behavior, GUI glyph composition and original complex spawn details remain open. Visual acceptance belongs to the user and does not block implementation or server verification.
