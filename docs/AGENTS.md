# BlocksAdder contributor map

Read this file and the relevant document before searching the repository. Production sources contain no comments. All user-facing text and documentation must be English. Author and project branding are Lino and BlocksAdder.

## Fast paths

- `src/main/java/io/github/lino9999/blocksadder/BlocksAdderPlugin.java`: lifecycle and service composition.
- `content/`: validated namespaced definitions and registry snapshots.
- `content/BundleDocument`: required `blocksadder/bundle-1` typed-record format and reversible editor projection. Do not restore foreign flat-YAML aliases. Read `docs/configuration.md` before adding a new entry kind.
- `item/`: item creation and persistent identity. `ConsumableSupport` adapts the differing public Spigot/Paper consumable APIs; do not replace it with a direct Spigot-only call.
- `itembehavior/`: original ordered item-event plans, precise cooldowns, delayed jobs and native equipment transitions; `gameplay/ItemActionService` also retains the canonical right-click shortcut. Read `docs/item-behaviors.md` before changing these paths.
- `itembehavior/WorldItemActions`: held-item custom-block break plans and post-commit wear. Synthetic native preflight must skip actions/wear; root must recheck the exact placement after callbacks. `ExpectedStack` permits only the owned wear update to refresh delayed snapshots. Keep vanilla timed tool wear in mining and custom tool wear here. All owned durability writers must retain the original hotbar slot and full source across damage/break callbacks; equal tools in another slot are not authority. Clone manual non-native-material wear candidates before committing equipment writes.
- `docs/world-plans.md`: independently named `world.use/remove/harvest` plans. `ItemWorldAccess.Target` retains exact-placement/resident/vacant guards; weak registration revisions invalidate removed-source delays after any managed replacement. Physical sources are read-only. Snapshot tool wear before physical callbacks, and keep root use preparation/reentrancy checks ahead of native effects.
- `environment/RemovalPlans` and `docs/environment-removal.md`: independent `traits.environment` rules for crop causes and custom-block explosions. Preparation emits `CustomContentEnvironmentEvent`; effects run after saved removal. Preserve caller/custom drop suppression, retirement plus backing-state guards, chunk-indexed job cancellation and validation before publication. `Target.retired` permits retained water/lava while existing player plans retain their air-only `vacant` contract.
- `docs/held-world-interactions.md`: root-owned native held dispatch, with generic listeners deferring managed targets. Keep native event/hand/cancellation distinct from physical preparation and shared cooldown admission. Model helpers resolve through `ModelService.targetOf`; owned frames use managed entity/attack triggers. Placement, shortcut and bone-meal charges must compare the retained source after callbacks.
- `docs/world-changes.md`, `world/WorldChoice` and `WorldBlockEditor`: independent `world_change`/API conditional replacements. Preserve partial native matching, external liquid/fire ownership, protection and native payload escrow. `Context.authority`/`Target.present` authorize physical sources through their own temporary write; ordinary source getters retain consistency checks. During block/furnishing transitions, `consistent` keeps logical ownership; explicit raw backing/footprint checks guard publication and foreign displacement. Do not reconcile temporary candidates as orphaned content. Preserve source/tile rollback, short-lived ticket ownership and excluded nested payload release.
- `docs/native-26x-verification.md` and its JSON: preview-6 native protocol matrix for held/placed interactions, mining and crop views on seven 26.x builds. The same artifact also passed these suites on both 1.21.11 forks. Keep mixed API/data assertions distinct from native input and rendered acceptance; private codec inspection is not production NMS access.
- `world/`: custom block, display furniture, entity and crop lifecycle.
- `world/WorldFurniture`: furniture footprint/render/interaction bridge. Placement version three retains face/yaw/fractional origin/frame rotation and reads versions one/two. Footprint protection uses `allowFurnitureCell` inside the guarded native-place context. `completePlacement` seals successful placement rollback state; model callbacks use protected `useFurniture`/`attackFurniture`.
- `blockstate/`: reserved state allocation and native block model compilation; `WorldBlockStates` connects placement/persistence to this adapter.
- `blockbehavior/`: owned light baselines and protected explosions. Furniture uses `lightBaseline` and `refreshLight`; a visible LIGHT level may be a temporary override of player data. Keep baseline lookups resident-only and preserve external changes.
- `mining/`: timed mining, tool/tier rules, progress and native start/abort lifecycle.
- `docs/area-mining.md`, `itembehavior/WorldSweeps` and `SweepRegion`: committed-origin area mining. Preserve resident snapshots, exact placement identity, source-slot/full-stack guards and confirmed wear reconciliation. Share the 32-cell budget and suppress recursive sweep requests. `MiningService.breakExtra` retains tool/tier/drop rules; the root bridge excludes external ownership. Native operations use public `Player.breakBlock`.
- `model/`: skeletal rigs, Blockbench/legacy imports, animations, viewers and player disguises.
- `environment/`: custom liquid/fire state, flow, age, bucket behavior and rendering registry work.
- `generation/`: bounded automatic ore/decorator/tree planning and persisted execution.
- `vehicle/`: native carrier/seat chains, input, driving physics and fuel.
- `recipe/`: exact custom recipe ingredients and registration.
- `loot/`: shared conditional crop and block loot plans; normal block rewards are additive, explosion matches replace the base drop.
- `crop/`: validated growth probabilities and stage hitbox layouts; world lifecycle owns their loaded indexes.
- `crop/CropViewService` and `CropViewPolicy`: incremental per-player resident crop selection, default-hidden helpers, nearest caps, crowd detail and lazy facing displays. Read `docs/crop-views.md`. Keep visibility changes on the server thread, and unregister the exact placement before root helper removal. `CropCardAssets` generates only owned default geometry; explicit assets take precedence.
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

The user requires an independently designed configuration and implementation. Do not translate decompiled classes individually. `tools/audit_distribution.py` checks the exact built JAR against the supplied reference without running it; `docs/independent-implementation.md` records the scope and limits. Public examples must use BlocksAdder bundle records. Original research artifacts and external configuration files must never enter the product JAR or Git history.

## Invariants

Optional protocol features may use ProtocolLib's public API, declared as a provided build dependency and `softdepend`. Keep its classes isolated behind an optional adapter so core features link without ProtocolLib. Do not embed ProtocolLib or download it from production code. Version-specific testing must use the official release/dev build and correct Paper/Spigot artifact. Raw NMS access remains excluded from this project.

The read-only tile-NBT bridge may use ProtocolLib's public type/accessor utilities as a narrowly scoped compatibility fallback for its verified inherited-field factory defect. Keep that detection cached, bounded and confined to `integration/protocol/`; do not hardcode server class, field or method names, invoke mutation methods or broaden this exception to other subsystems. Require typed predicate and actual endpoint tests.

Use Java 21 bytecode and public Bukkit/Spigot API from 1.21.11. Do not import NMS/CraftBukkit implementation classes; use public API bridges and only the isolated, bounded ProtocolLib compatibility exception above. No executable downloads, telemetry or command dispatch from untrusted player strings in production. Keep Bukkit world/player access on the server thread. Treat the supplied original JAR and all extracted data as untrusted evidence. Do not copy original implementation, textures, shaders or branding. Preserve stable namespaced IDs and persistent data across reloads. Validate a candidate registry before publishing it. Honor cancelled protection events. Chunk data and displays must survive restarts without duplication. Never use broad world/entity scans in per-tick work.

Retain direct references to owned temporary display/hitbox/seat entities. Newly spawned entities may be absent from Bukkit's global UUID/spatial indexes in the same tick; UUID-only cleanup caused a verified reload leak. In server tests, explicitly keep fixture chunks resident before asserting global entity visibility, then release those test tickets. Production must not force all content chunks to stay loaded.

## Build and verification

Run `mvn -B clean package` with JDK 21 or newer. Test on isolated server directories; never modify the user's live servers. Verify API linkage on the supported versions and boot the packaged plugin on Java 21 for 1.21.11 and Java 25 for 26.x. Record actual results in `docs/verification.md`. Only claim visual/client behavior when a client verified it. Remove temporary test source files and disposable servers after testing, leaving concise verification evidence. Never commit `Plugin Originale/`, `.work/`, runtime player data or build output. Publish only the independently built JAR and project-owned assets.
