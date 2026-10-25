# Original feature requirements

## Current implementation status

| Area | BlocksAdder 1.0.0 implementation |
| --- | --- |
| Items, namespaces and administration | Implemented with independent YAML schemas, persistent IDs and renamed commands/permissions. |
| Blocks | Display-backed full collision blocks; one protected click to remove. Original reserved block-state modes, custom mining duration and tool-tier logic are absent. |
| Furniture and crops | Single-anchor display furniture, seats, interaction hitboxes and staged crops. Multi-block vehicles, animated multipart furniture, bone meal and density-dependent viewer rendering are absent. |
| Recipes | Nine types: shaped, shapeless, furnace, blasting, smoking, campfire, stonecutting, smithing transform and fixed-result anvil recipes. |
| Fonts, stats, HUDs and audio | Bitmap font providers, chat/sign/book aliases, bounded persistent stats, regeneration, bossbar/actionbar HUDs and custom sound definitions. Original scoreboard text-effect pipelines and shader-specific armor effects are absent. |
| Loot, entities and trees | Block/entity/fishing loot, configured vanilla living entities and manually requested custom trees. Automatic biome/ore population, custom AI/bones and packet models are absent. |
| Emotes and player models | Simple swing/particle/sound timelines. Full-body skeletal emotes and disguises are absent. |
| Liquids and custom fire | Unimplemented; no bucket/flow/fire simulation is registered. |
| Resource packs | Modern item/model/font/sound generation, explicit asset merging, reproducible ZIPs/hashes and optional exact-route HTTP serving. Original shader conversion, audio transcoding, hosted upload services and pack obfuscation are absent. |
| Third-party integrations | Public BlocksAdder Bukkit service and cancellable content/Bukkit events. Dedicated adapters, original configuration compatibility and original binary API compatibility are absent. |

The independent implementation is an initial release, not a completed replacement for every feature in the reference. The requirement table below remains the migration acceptance checklist. [Verification](verification.md) distinguishes actual server checks from player/client behavior still requiring interactive tests.

This is an investigation checklist, not a promise that every original feature is implemented. Each requirement is based on the supplied 4.0.18 artifact described in [the investigation](reverse-engineering.md). See the current README and configuration guide for the supported BlocksAdder surface. A feature must have implementation and validation evidence before being advertised as supported.

| Family | Original behavior to account for | Minimum acceptance evidence |
| --- | --- | --- |
| Namespaces/content | Load namespaced items, reject duplicates, category organization, content reload | Invalid content keeps prior registry; ID resolution remains unambiguous |
| Custom items | Model data, names/lore, usages, durability, enchants, attributes, colors, equipment | Round trip through inventories/restart; vanilla items do not inherit custom behavior |
| Custom blocks | Place/break/interact, block modes, tools/loot, sound/light/directional state | Persistence across chunk unload/restart; cancellation/protection, pistons, explosions and fluids cannot duplicate or orphan blocks |
| Furniture | Place/break/interact, transformations, hitboxes, seats and lighting | Protected interaction, cleanup, chunk reload and passenger disconnect/restart cases |
| Complex furniture | Animated furniture and vehicle passengers/control/fuel | Correct passenger lifecycle and synchronized model state |
| Crops | Seed placement, age stages, growth, harvest and density/visibility | Persisted age, correct mature/immature drops, protected placement and no unloaded chunk growth surprises |
| Liquids/fire | Registration, bucket interaction, analyzer, custom fire age | Correct flow/bucket behavior and protection cancellations without vanilla-state corruption |
| Crafting | Shaped/shapeless and custom identity-aware ingredients, permissions | Vanilla lookalikes cannot craft custom recipes; shift craft and automated crafter are correct |
| Cooking | Furnace/blast furnace/smoker/campfire recipes | Custom identity survives input/result matching, hopper automation and reload |
| Anvil/smithing | Repair/transform recipes with exact ingredients | Rename cost, consumption and cancellation verified |
| Loot | Block/mob/fishing loot and tool-sensitive drops | Chance/amount bounds, permission checks and no duplicate drops |
| World generation | Block populators, surface decorators and custom trees | New-chunk boundaries, biome/height constraints and bounded generation work |
| Entity models | Spawn/convert/remove, bones, animations, colors and viewers | Model geometry, damage/death, unload/reload and viewer lifecycle |
| Player models/emotes | Disguises, skins, animation play/stop and mounts | Per-viewer rendering and proper cleanup on teleport/death/quit |
| Fonts/emojis | Bitmap glyphs and replacements in enabled text surfaces | Correct atlas/provider output, unique codepoints and permission-aware substitution |
| HUD/stats | Show/hide, quantity updates, stat read/write/increment/decrement | Persistent finite bounded values, disconnect cleanup and correct bypass policy |
| Sounds/effects | Custom music/sounds, totem animation | Correct namespaced pack entries and audible/visible client result |
| Resource-pack build | Models, textures, fonts, sounds, overlays, merging and ZIP | Deterministic valid ZIP, stable IDs, safe paths, working visuals for each client version |
| Resource-pack delivery | Manual/self/external hosting, apply/all, SHA-1 and status handling | Correct hash, join prompts/status and graceful download failure |
| Catalog/recipe GUI | Categories, pages/search and recipe display | Cancel drag/shift/number-key/creative exploits; recipe access does not grant items |
| Item administration | Get/give/remove/drop, rename/repair/color/durability, inspect/update | Per-action permissions, bounded quantities, exact matching and full-inventory behavior |
| Content administration | Reload/build, config disable/delete, cache cleanup and diagnostics | Atomic failure, actionable English errors, no live-ID recycling |
| Integrations | Protection, placeholders, scripting, mobs/NPCs, permissions/economy | Each named integration needs explicit adapter tests; soft dependency declaration alone is insufficient |
| Developer API/events | Item/block/furniture/entity/font/HUD interfaces and lifecycle events | New API documented; cancellable events honored; no claim of original binary compatibility |

Remote activation/revocation, original telemetry/update endpoints, executable/dependency downloads and archival product branding are deliberately excluded from the independent implementation. This exclusion does not affect the gameplay requirements above.

## Verification boundaries

Compilation proves API linkage against the selected build dependency. A headless server start proves plugin loading and the executed administrative checks. Neither establishes client visuals or every interactive/protection/economy integration. Describe the exact tested server versions and cases in release notes, and retain remaining gaps explicitly instead of using “full parity” or “fully tested” language.

Headless world checks hold temporary, probe-owned plugin chunk tickets for their fixture chunks to model resident chunks when no player is connected. Immediate helper assertions inspect the authoritative chunk entity collection. Deferred assertions additionally require exactly one expected helper, a valid entity, global UUID resolution and spatial-query visibility. Tickets are released after the checks; production does not force chunks to remain active. A separate marker chunk retains its identity/collision data across complete server restarts. These checks establish server-side entity lifecycle and persistence only, not client rendering.
