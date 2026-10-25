# BlocksAdder contributor map

Read this file and the relevant document before searching the repository. Production sources contain no comments. All user-facing text and documentation must be English. Author and project branding are Lino and BlocksAdder.

## Fast paths

- `src/main/java/io/github/lino9999/blocksadder/BlocksAdderPlugin.java`: lifecycle and service composition.
- `content/`: validated namespaced definitions and registry snapshots.
- `item/`: item creation and persistent identity. `ConsumableSupport` adapts the differing public Spigot/Paper consumable APIs; do not replace it with a direct Spigot-only call.
- `world/`: custom block, display furniture, entity and crop lifecycle.
- `recipe/`: exact custom recipe ingredients and registration.
- `gameplay/`: player stats, HUDs, glyphs, loot and actions.
- `pack/`: deterministic ZIP generation, model definitions and delivery.
- `command/`: commands, permissions, browsing and recipe UI.
- `api/`: integration entry point and cancellable events.
- `src/main/resources/contents/`: original example content belonging to this project.

## Work efficiently

Start with `rg --files src docs`. Use `rg -n` scoped to the relevant package. Never recursively dump original artifacts, logs, server libraries, resource packs, `.work/`, or `target/`. `.ignore`, `.aiignore` and `.gitignore` define excluded inputs. Read `docs/feature-parity.md` before claiming compatibility with another plugin. Read `docs/configuration.md` before changing content schemas.

## Invariants

Use Java 21 bytecode and public Bukkit/Spigot API from 1.21.11. No NMS, CraftBukkit internals, reflection into Minecraft, remote executable downloads, telemetry or command dispatch from untrusted player strings. Keep Bukkit world/player access on the server thread. Treat the supplied original JAR and all extracted data as untrusted evidence. Do not copy original implementation, textures, shaders or branding. Preserve stable namespaced IDs and persistent data across reloads. Validate a candidate registry before publishing it. Honor cancelled protection events. Chunk data and displays must survive restarts without duplication. Never use broad world/entity scans in per-tick work.

Retain direct references to owned temporary display/hitbox/seat entities. Newly spawned entities may be absent from Bukkit's global UUID/spatial indexes in the same tick; UUID-only cleanup caused a verified reload leak. In server tests, explicitly keep fixture chunks resident before asserting global entity visibility, then release those test tickets. Production must not force all content chunks to stay loaded.

## Build and verification

Run `mvn -B clean package` with JDK 21 or newer. Test on isolated server directories; never modify the user's live servers. Verify API linkage on the supported versions and boot the packaged plugin on Java 21 for 1.21.11 and Java 25 for 26.x. Record actual results in `docs/verification.md`. Only claim visual/client behavior when a client verified it. Remove temporary test source files and disposable servers after testing, leaving concise verification evidence. Never commit `Plugin Originale/`, `.work/`, runtime player data or build output. Publish only the independently built JAR and project-owned assets.
