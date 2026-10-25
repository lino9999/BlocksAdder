# BlocksAdder contributor map

Read this file and the relevant document before searching the repository. Production sources contain no comments. All user-facing text and documentation must be English. Author and project branding are Lino and BlocksAdder.

## Fast paths

- `src/main/java/io/github/lino9999/blocksadder/BlocksAdderPlugin.java`: lifecycle and service composition.
- `content/`: validated namespaced definitions and registry snapshots.
- `item/`: item creation and persistent identity. `ConsumableSupport` adapts the differing public Spigot/Paper consumable APIs; do not replace it with a direct Spigot-only call.
- `world/`: custom block, display furniture, entity and crop lifecycle.
- `world/WorldFurniture`: furniture footprint/render/interaction bridge. Placement records retain face/yaw; version two reads version one with face UP. Footprint protection uses `allowFurnitureCell` inside the guarded native-place context.
- `blockstate/`: reserved state allocation and native block model compilation; `WorldBlockStates` connects placement/persistence to this adapter.
- `mining/`: timed mining, tool/tier rules, progress and native start/abort lifecycle.
- `model/`: skeletal rigs, Blockbench/legacy imports, animations, viewers and player disguises.
- `environment/`: custom liquid/fire state, flow, age, bucket behavior and rendering registry work.
- `generation/`: bounded automatic ore/decorator/tree planning and persisted execution.
- `vehicle/`: native carrier/seat chains, input, driving physics and fuel.
- `recipe/`: exact custom recipe ingredients and registration.
- `loot/`: shared conditional crop and block loot plans; normal block rewards are additive, explosion matches replace the base drop.
- `crop/`: validated growth probabilities and stage hitbox layouts; world lifecycle owns their loaded indexes.
- `sound/`: custom block cues, resource-pack aliases and optional ProtocolLib packet rewriting.
- `integration/`: optional typed tile-NBT conditions, with ProtocolLib classes isolated in `integration/protocol/`.
- `gameplay/`: player stats, HUDs, glyphs, loot and actions.
- `itembehavior/`: original item event plans, action execution and native trigger adapters; keep inventory source validation separate from retained event identity.
- `pack/`: deterministic ZIP generation, model definitions and delivery.
- `pack/EquipmentAssetCompiler`: native armor layer definitions and texture placement.
- `command/`: commands, permissions, browsing and recipe UI.
- `api/`: integration entry point and cancellable events.
- `docs/api.md`: public service access and operation/threading contracts.
- `src/main/resources/contents/`: original example content belonging to this project.

## Work efficiently

Start with `rg --files src docs`. Use `rg -n` scoped to the relevant package. Never recursively dump original artifacts, logs, server libraries, resource packs, `.work/`, or `target/`. `.ignore`, `.aiignore` and `.gitignore` define excluded inputs. Read `docs/feature-parity.md` before claiming compatibility with another plugin. Read `docs/configuration.md` before changing content schemas.

The active goal is full functional parity. `docs/parity-progress.md` tracks development beyond preview 1.0.0. Do not redefine success around the already implemented subset. Keep the original feature checklist and client acceptance requirements open until each has concrete evidence. Current development version is 1.1.0-SNAPSHOT; do not overwrite the historical 1.0.0 release or its checksum report with a different artifact.

The user explicitly owns visual checks. Do not launch or control a graphical Minecraft client for this task. Continue implementing visual features and verifying their generated data and server behavior; record rendered appearance as user verification, without making that handoff a blocker for coding, server checks or packaging.

## Invariants

Optional protocol features may use ProtocolLib's public API, declared as a provided build dependency and `softdepend`. Keep its classes isolated behind an optional adapter so core features link without ProtocolLib. Do not embed ProtocolLib or download it from production code. Version-specific testing must use the official release/dev build and correct Paper/Spigot artifact. Raw NMS access remains excluded from this project.

The read-only tile-NBT bridge may use ProtocolLib's public type/accessor utilities as a narrowly scoped compatibility fallback for its verified inherited-field factory defect. Keep that detection cached, bounded and confined to `integration/protocol/`; do not hardcode server class, field or method names, invoke mutation methods or broaden this exception to other subsystems. Require typed predicate and actual endpoint tests.

Use Java 21 bytecode and public Bukkit/Spigot API from 1.21.11. Do not import NMS/CraftBukkit implementation classes; use public API bridges and only the isolated, bounded ProtocolLib compatibility exception above. No executable downloads, telemetry or command dispatch from untrusted player strings in production. Keep Bukkit world/player access on the server thread. Treat the supplied original JAR and all extracted data as untrusted evidence. Do not copy original implementation, textures, shaders or branding. Preserve stable namespaced IDs and persistent data across reloads. Validate a candidate registry before publishing it. Honor cancelled protection events. Chunk data and displays must survive restarts without duplication. Never use broad world/entity scans in per-tick work.

Retain direct references to owned temporary display/hitbox/seat entities. Newly spawned entities may be absent from Bukkit's global UUID/spatial indexes in the same tick; UUID-only cleanup caused a verified reload leak. In server tests, explicitly keep fixture chunks resident before asserting global entity visibility, then release those test tickets. Production must not force all content chunks to stay loaded.

## Build and verification

Run `mvn -B clean package` with JDK 21 or newer. Test on isolated server directories; never modify the user's live servers. Verify API linkage on the supported versions and boot the packaged plugin on Java 21 for 1.21.11 and Java 25 for 26.x. Record actual results in `docs/verification.md`. Only claim visual/client behavior when a client verified it. Remove temporary test source files and disposable servers after testing, leaving concise verification evidence. Never commit `Plugin Originale/`, `.work/`, runtime player data or build output. Publish only the independently built JAR and project-owned assets.
