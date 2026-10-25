# Item components and event actions

This is a development checkpoint for the independent reconstruction. It does not claim complete original-item parity. Original classes were inspected statically; the supplied plugin was never executed. Production code uses public Bukkit APIs. `ConsumableSupport` retains its cached Spigot/Paper food bridge.

## Observed schemas

The original `sv`, `so`, `ss`, `sz`, `tc` and `tb` parsers establish these fields:

| Configuration | Behavior |
| --- | --- |
| `durability.max_durability` / `max_custom_durability` | Native maximum damage component, including custom damageable materials |
| `durability.durability` / `custom_durability` | Remaining durability, defaulting to the maximum |
| `durability.unbreakable`, `repair_cost`, `disappear_when_broken` | Break policy and native repair metadata |
| `durability.usages` | Independent persistent usage counters; consuming one reusable unit separates untouched units |
| `attribute_modifiers.<slot>.<attribute>` | Numeric values or `{value, operation}`; operations `add`, `multiply_base`, `multiply` |
| `equipment` | `slot`, `id`, `camera_overlay_id`, `sound`, `allowed_entities`, `dispensable`, `swappable`, `damage_on_hurt`, `glider`, `slot_attribute_modifiers` |
| `specific_properties.armor` | Slot-matched leather material, color, `can_wear`, relative `custom_armor` |
| `specific_properties.potion` | Base potion, color and named custom effects |
| `specific_properties.book` | Title, author, generation and pages |
| `enchants`, `item_flags`, `hide_tooltip`, `fire_resistant`, `glint` | Native item metadata |

Equipment IDs and legacy armor rendering references resolve against the item's namespace. Original absolute weapon `attackDamage` and `attackSpeed` values subtract the player base values of 1 and 4 before creating additive modifiers. Other attribute operations retain their supplied values. Original incompatible armor materials are replaced with slot-matched leather; the original mismatched leather-slot behavior is deliberately corrected. Equipment pack compilation belongs to `EquipmentAssetCompiler`.

Modern numeric `durability`, `attributes`, `enchantments`, `equipment-model`, food and right-click shortcut definitions remain available. Loader normalization retains the entire original durability section as `durability-options`; it must not flatten a current remaining value into a maximum.

## Event execution

Original `mv`, `jj`, `jh`, `ji`, `ou`, `np`, `nm`, `qm` and action classes establish ordered action sections under `events.<trigger>`. Suffixes such as `play_sound_2` identify independent actions. `execute_commands` contains named command sections; flow references the command group's action ID. Group outcomes are accumulated in configuration order.

Supported native triggers cover left/right and shifted interactions, main/offhand interactions, entity interaction, attacks, kills, breaking, dropping, pickup, eating/drinking, bow shooting, books, fishing, held/unheld state, armor wear/unwear, durability break, gliding, elytra boost/collision, native buckets, armor stand equipment and item frames. Bukkit must emit the corresponding native event; the engine does not synthesize protected interactions from raw packets. Equipment reconciliation visits online player equipment only, every five ticks and after inventory/hand changes.

Supported action families are sound start/stop, ordered commands, untyped particles and effects, durability/usage/amount changes, item grants/drops, experience drops, feeding, potion addition/removal on the player or target, bounded damage in sight/nearby/directly, explosions, native totem animation, custom model-data replacement/restoration, player-stat increments/decrements and cancellation. Nearby damage preserves original `HOSTILE`, `PLAYERS`, `PASSIVE` groups and uses a bounded query. Restorable model-data replacement toggles back to the configured item model data; it does not invent a property history.

Global `events_cooldown` or `events_settings.cooldown.ticks`, per-trigger `cooldown`, `all_actions_permission`, action permission negation (`!permission` / `-permission`) and `events_needed_player_stats` gates are enforced. Subsecond cooldowns retain their tick precision. Required stat comparisons accept the observed `>`, `<`, `=` and additionally `>=`, `<=`. Cooldown indicators support `TITLE`, `BOSSBAR`, `VANILLA`, `HIDDEN`; bossbar color/style are configurable. Native cooldown groups use namespaced item identity and do not apply a material-wide cooldown to vanilla lookalikes.

The twelve observed stop/skip flow conditions are validated. An action reference must name an earlier action. Permission-denied actions do not supply a flow outcome. Delayed actions run independently, matching the observed scheduling shape; combining their own immediate flow condition is rejected. Delayed work is bounded to 128 jobs per player, revalidates the complete source stack and permissions, and is cancelled on reload, disconnect or shutdown. Plans allow at most 128 steps; recursive action damage is guarded. All operations run on the server thread.

Commands expand only known player/target/world/location placeholders, never chat or display-name data. Registered plugin commands support silent console execution and bounded output matching through a public sender adapter. Native command dispatch can unwrap that sender; native commands therefore require explicit `silent: false` with no captured-output predicate. Player command output predicates are rejected. Unknown placeholders and action fields are validation errors rather than literal dispatch or silently ignored conditions.

## Explicit remaining gaps

This checkpoint rejects script/gun providers, projectile throw/hit and placed-content trigger families, world/furniture replacement and multiple-break actions, custom HUD cooldowns, typed particle data, custom totem animation items, arbitrary `nbt`/`components_nbt_file`, custom unregistered equipment sounds and captured native-command output. These need dedicated public capability adapters or domain integration before full parity can be claimed. Raw component rejection is deliberate; ProtocolLib availability alone is not evidence that arbitrary component reconstruction works.

Non-disappearing durability clamps the native damage component before native destruction; it exposes one native point remaining at exhaustion. Exact original zero-remaining presentation and exhausted-tool interaction policy remain open. Armor effects use public events and a five-tick equipment reconciliation window; they are not a claim of synchronous third-party equipment-event compatibility. Server-issued usage splitting preserves PDC identity and fresh remainder units. Rendering remains user verification.

## Verification checkpoint

The first actual protocol-player fixture passed 30 assertions on Spigot 1.21.11 / Java 21, candidate `71832cd7ea183869e27628d43b8da370afa64c6714976b0902e2a1af4d8363e0`. It exercised native interaction packets, both hands, sneaking, subsecond cooldown isolation, command/stat/effect/feed flow, reusable unit splitting/exhaustion, delayed source replacement, durability cancellation, hotbar changes, armor rejection, drops and native consumption, plus item component assertions.

Subsequent testing identified and fixed lost unwear identity, command-group flow references and Paper console-sender unwrapping. Corrected candidate `81636c3a04f06a2972e8989d6c39294ed2980a164655ebc60ada5330c177a2b2` passed 30/30 assertions on both Spigot 1.21.11 and Paper 1.21.11, running Java 25 with Java 21 bytecode. The Paper armor failure was a fixture null-versus-AIR assertion; its native click was already cancelled. A BLAZE_ROD right-air protocol packet produced no Paper interact event, so command flow acceptance uses a native right-block packet.

Read-only review then found an overly broad non-wearable-armor shift-click guard. It now restricts personal-inventory auto-equipping and direct armor targets while allowing chest transfers. The changed listener compiles against the public Spigot API; its dedicated native chest-transfer regression is pending the next snapshot run.

Java 21 became unavailable to new host processes after an OS runtime failure; continuation tests use the installed Java 25 runtime. No host settings were changed and no graphical client was launched. Paper 26.3 item component linkage and additional projectile/world/provider behavior remain separate pending checks.
