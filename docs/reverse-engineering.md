# Supplied artifact investigation

This report describes the supplied artifact, not a claim of complete behavioral equivalence. BlocksAdder is independently authored. Original classes, shaders, textures, models, configuration files, libraries and license checks are not incorporated into its implementation.

## Identity and evidence

| Fact | Observation |
| --- | --- |
| Input | `Plugin Originale/ItemsAdder.jar` |
| SHA-256 | `cedc3ff88d57fb303e865b017d8e090da0f9f203d3922ff7887e27342f0c053d` |
| Size | 4,704,329 bytes |
| Declared product/version | ItemsAdder 4.0.18; build timestamp `dev` |
| Bukkit entry point | `dev.lone.itemsadder.Main` |
| Declared API minimum | `1.14`; not evidence that every packaged implementation works on that version |
| Required plugin | ProtocolLib |
| ZIP inventory | 2,197 entries; 2,094 files; 103 directory occurrences |
| JVM class inventory | 1,946 class files; all parsed for constant-pool and member metadata |
| Class file versions | 1,834 version 65 (Java 21); 44 version 52 (Java 8); 68 version 50 (Java 6) |
| Public API package | 74 classes, including nested classes; 773 public/protected method records including constructors and bridge methods |
| Declared commands | 35 |

REA `open_binary` authenticated the archive identity. REA `inspect_artifact` retained parent evidence `ev_5a4a4ddc4581dedc578bda1fe894acfd4ea4202a51e709148d11b4f32a6e72d1`; summary evidence is `ev_ec1b67b2e3cf2d9d7fbba77f1ce7c7d3dc3c4a536f9a66dbebdab5a61d4ea6e4`. The inventory graph reports 2,191 unique nodes, 2,198 occurrences including the root, 2,197 edges and zero integrity contradictions. This is archive coverage, not runtime coverage.

Additional evidence: complete Java class constant-pool/member inspection, packaged `plugin.yml`, `config.yml`, English message resources, and focused CFR 0.152 decompilation. The decompiler was executed as a tool; the original plugin was never loaded or executed. Derived sources and analysis tools are private, ignored working artifacts rather than product source.

The archive manifest declares `Main-Class: yo`, but no corresponding root `yo.class` exists. Bukkit uses the independently declared plugin entry point. This discrepancy does not establish tampering or malicious behavior.

## Observed feature families

The following are observed entry points, schema switches and resources. Their presence alone does not establish every configuration combination or actual gameplay result.

| Family | Evidence in the supplied artifact |
| --- | --- |
| Content registry | Namespaced item/block/entity IDs, configuration namespaces, categories, model and texture paths, item recognition and registry lookup |
| Items and equipment | Names/lore, model data, usages, custom durability, damage/armor modifiers, enchants, colors, equipment rendering, modern component and legacy shader paths |
| Block rendering | Note block, mushroom, chorus, tripwire, fire and entity/display block modes; sound patching, light levels, directional state and per-chunk display limits |
| Persistent block identity | `CustomBlock.Advanced` exposes region lookup, placement/removal, chunk enumeration and bulk removal; block data is distinct from custom identity |
| Furniture | Display/armor-stand rendering, solid/non-solid placement, rotation/transforms, lights, hitboxes, interaction/break events, seating and per-chunk limits |
| Complex furniture and vehicles | Complex entity-backed furniture, mounting, passenger control, fuel indicators and entity teleportation |
| Crops | Seeds, staged ages, growth/harvest, crop hitbox assistance, density limits and client visibility controls |
| Liquids and fire | Custom liquid registration/analyzer/bucket messages; custom fire age, placement and removal API; static inventory does not reveal all flow rules |
| Recipes | Crafting, cooking, anvil and smithing switches; custom-item ingredients, permission feedback, recipe browser, unlocks and vanilla recipe suppression |
| Loot | Block, mob and fishing loot resources and browse buttons; tool-sensitive loot API |
| World content | Block populators, surface decorators, custom trees and world/biome/height/chance metadata |
| Entity models | Custom entities and mobs, bones, animations, model conversion, colors, glowing/enchanted bones, frustum/viewer control and damage effect |
| Player models | Player-model spawning, skins, emotes, disguises, animation play/stop and mount bones |
| Fonts and text | Bitmap font images/emojis, textured inventories, custom/thin fonts, chat/sign/book/anvil/name/lore replacements and text shaders/effects |
| HUD and statistics | Player HUD holders, quantity/custom HUD wrappers, statistic read/write/increment/decrement and permission-based bypasses |
| Sound and animation effects | Namespaced sound playback, OGG conversion, custom music, totem animation and emote lifecycle events |
| Resource packs | Asset generation, merging, ZIP compression, duplicate image checking, SHA-1, legacy version overlays, self/external/managed hosting, player send/status handling |
| Administration | Content reload, ID cache cleanup, item auto-update controls, config item/namespace/recipe disable/delete and debugging |
| Integrations | Protection providers, PlaceholderAPI, Skript, MythicMobs, Citizens, permissions/economy providers and other declared soft dependencies |
| Developer extension | Item/block/furniture/entity/font/HUD wrappers and cancellable lifecycle events; some public signatures leak obfuscated internals |

## Original command surface

Command names below identify the observed original interface only. BlocksAdder defines its own command and permission names. Usage is from the packaged manifest; focused decompilation confirmed item give/drop/remove, configuration mutation, item updating, reload/ZIP flags and entity subcommands.

| Original command | Observed purpose and argument shape |
| --- | --- |
| `ia` | Item/category GUI: `[category] [player]` |
| `iatexture`, alias `iapack` | Send/reload the resource pack: `[all\|player]` |
| `iaget` | Obtain a custom item: `<item> [amount]` |
| `iagive` | Give to an online player: `<player> <item> [amount] [silent]` |
| `iadrop` | Drop at player or coordinates: `<item> <player\|x y z world> [amount]` |
| `iaremove` | Remove matching identity from inventory: `<player> <item> [amount] [silent]` |
| `iatag` | Inspect held custom-item/NBT metadata; separate console-output permission |
| `iacustommodeldata` | Inspect held custom model data |
| `iaplayerstat` | `<read\|write\|increment\|decrement> <player> <attribute> [value]` |
| `iarepair` | Repair held vanilla/custom item |
| `iarename` | Rename held item with text/font-image replacement: `<name>` |
| `iadurability`, alias `iadur` | Modify held-item durability |
| `iareload` | Reload content; observed `--scripts-only` and `--skip-recipes` flags |
| `iazip`, alias `iaz` | Reload/build pack; `--uncompressed`, `--apply-to all\|none\|self\|player`, `--skip-recipes`, `--open-ia-menu`, `--check-duplicate-images` |
| `iablock` | Inspect targeted custom block |
| `ialiquid` | Inspect targeted/coordinate liquid: `[x] [y] [z]` |
| `iaimage`, aliases `iaemoji`, `emoji`, `e` | Font-image browser/replacement support |
| `iarecipe`, alias `iaguide` | Recipe GUI: `[item] [player]` |
| `iaemote`, alias `emote` | `<emote\|stop\|stop-now> [player]`; separate others permission |
| `iahud` | Force show/hide a named HUD |
| `iainfo` | Plugin information |
| `iaspawntree` | Select custom tree and place at a clicked surface |
| `iaplaytotemanimation`, alias `totemanimation` | `<totem> <player> [silent]` |
| `iaplaysound`, alias `playcustomsound` | `<sound> <player> [volume] [pitch]` |
| `iacleancache` | Clean unused item/block ID allocations: `<items\|blocks>` |
| `iasha1` | Calculate pack SHA-1: `<local\|online>` |
| `iahitbox` | Toggle furniture hitbox inspection |
| `iaconfig` | `item delete\|disable`, `namespace delete\|disable`, `recipes deleteofitem\|disableofitem` |
| `iaentity` | Entity summon/kill/animation/disguise; player models/skins and Citizens operations |
| `iacolor` | Color held or targeted player's item; separate others permission |
| `iadebug` | Developer diagnostics |
| `iaitem` | Update items, reload/update all, configure stack auto-update; player/slot/item selectors |
| `iadisguise` | Disguise as custom entity |
| `iaundisguise` | Remove disguise |
| `crops` | Toggle custom crop visibility |

Give/drop commands build an item instance and apply a requested amount. Removal walks inventory slots and removes matching custom identities up to the requested quantity. Their original permissive numeric parsing is an observation, not a design requirement; BlocksAdder must validate finite values and safe quantity bounds independently.

## Public API observations

The principal wrappers are `CustomStack`, `CustomBlock`, `CustomFire`, `CustomCrop`, `CustomFurniture`, `CustomComplexFurniture`, `CustomEntity`, `CustomMob` and `CustomPlayer`. They cover registry lookup, item conversion/cloning, identity, model/config paths, usages/durability, placement/removal, loot, color/light changes, entities and animation control.

`CustomEntity.Bone` exposes bone identity/location/color/enchantment state; `MountBone` adds passenger, lock and control operations. Font wrappers cover image substitution, player HUDs and inventory textures. Public event types cover block place/interact/break, furniture pre-place/place/interact/break/sit/unsit, custom-entity death, content-load causes, pack compression/sending, emote play/end and trade-machine opening/trading.

Some signatures accept obfuscated `itemsadder.m.*` internals. Renaming a plugin does not preserve binary compatibility for integrations compiled against these types. BlocksAdder uses a new API and new events. Compatibility with plugins expecting the original API requires a separately designed and tested adapter; it must not be implied by feature similarity.

## Bounded security findings

All 1,946 class constant pools were searched for direct network, process, native load, class-load, command dispatch, filesystem deletion and scripting references. This identifies static indicators, not all reachable operations.

| Indicator | Result and interpretation |
| --- | --- |
| Remote startup gate | Focused `Main` decompilation shows `onEnable()` first calls synthetic `loadConfig0()`. It makes an HTTP GET to Spigot's legacy premium endpoint with unresolved user/resource/nonce placeholders, one-second connect/read timeouts and redirects enabled. A literal `false` response throws a runtime exception; IO errors are swallowed. This is remote activation/revocation behavior. Its provenance and authenticity cannot be established from this JAR. Do not recreate it. |
| Other outbound services | Constant pools contain update/license endpoints, pStats telemetry, Maven mirrors, a vanilla-assets download worker, a skin service and managed pack-upload endpoints. Focused upload-class decompilation corroborates resource-pack upload to Lobfile. These are explicit network features, not proof of credential exfiltration. |
| External processes | One class, `itemsadder/m/h`, references `ProcessBuilder`. Focused decompilation shows FFmpeg audio conversion, duration probing and version checks plus OS-specific FFmpeg ZIP downloads. No shell invocation was found in the inspected paths. Downloading/executing an external binary remains a supply-chain boundary and is omitted from the rewrite. |
| Dynamic classes | 39 classes reference class loading/reflection; the archive also contains a runtime dependency manager. These are consistent with dependency loading and compatibility layers, but each dynamic target was not resolved. |
| Command execution | 19 classes reference Bukkit command dispatch or player command execution. Focused `np` inspection shows configured item-event command actions with player/location placeholders; focused `ael` is a console-proxy command sender. Administrator-configured commands need explicit trust boundaries. |
| Elevated operator API | No direct constant-pool member reference named `setOp` was found. Reflection, downloaded classes and indirect behavior remain outside that negative result. |
| Filesystem operations | Ten classes reference delete operations. A complete data-flow and reachable-path review was not performed. |
| Prompt injection/webhook/shell strings | No match for the selected instruction-override, system-prompt, Discord webhook, `powershell`, `cmd.exe`, `/bin/sh` or `/bin/bash` patterns in all decoded constant-pool strings. Encoded, constructed and external payloads remain unknown. |
| Native loading API | No direct `System/Runtime.load` or `loadLibrary` reference was found. Native-method declarations and external helper behavior are separate questions. |

No security verdict of “safe” is justified. There is no confirmed malicious backdoor in the examined evidence, and the negative searches cannot exclude one. Untrusted resources, strings and decompiled comments were treated as data. Original remote activation, telemetry, updater, runtime dependency downloads and executable downloads are not requirements for BlocksAdder.

## Remaining unknowns

- Exact runtime behavior, order of protection checks, packet handling and persistent-world formats were not established by running the original.
- Source authenticity, provenance of the startup gate and license/redistribution rights of the original artifact are not established by its bundled license file.
- Rendering of custom armors, emotes, font effects and model bones requires client-side visual verification on each supported protocol.
- Original pack/config formats, add-ons and public API integrations are not automatically compatible with an independent rewrite.
- Full feature equivalence requires individually implementing and testing every row of the feature requirements matrix; a working core is not proof of parity.
