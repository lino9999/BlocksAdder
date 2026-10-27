# Item components and event actions

## Custom block action lifecycle

`WorldItemActions` connects held-item `block_break` plans to custom block removal. Synthetic Bukkit protection checks and preliminary mining checks do not run the plans or charge custom tool wear. The root checks native/custom protection and supported-crop removal first, then runs the ordered item plan before mutation so its cancellation can veto the break. It checks the exact placement instance again afterward. A callback that removes and replaces the same ID cannot make the outer operation remove or reward the new placement.

After removal is saved, a per-attempt callback applies one custom tool wear charge. Native-material custom tools use this same path; timed mining retains its separate vanilla-tool charge only for items without custom identity. Zero-hardness blocks skip wear. Damage-event cancellation and a listener replacing the held item are honored. Native drop suppression remains binding even when a later custom callback requests drops.

Delayed actions retain a source snapshot. The known wear write updates only snapshots that still matched the tool immediately before that write. Replacement, exhaustion, configured source mutations and reload retain their cancellation behavior. This avoids treating the plugin's own successful durability update as an unrelated tool swap.

The complete current-format example below increments a meter when the held pickaxe successfully reaches a permitted custom-block break:

```yaml
format: blocksadder/bundle-1
bundle: quarry
entries:
  - key: work
    kind: meter
    settings: {min: 0, max: 1000, default: 0}
  - key: pick
    kind: artifact
    item: {base: DIAMOND_PICKAXE, label: Quarry Pick, stack: 1}
    traits:
      durability: 100
      mining: {speed: 8, tier: diamond}
      events:
        block_break:
          increment_player_stat: {name: 'quarry:work', amount: 1}
  - key: ore
    kind: block
    item: {base: PAPER, label: Quarry Ore}
    settings: {material: BARRIER, display: false, hardness: 0.3}
```

These are pre-removal action plans, not a committed-removal notification API: later failures can still stop a mutation, and arbitrary configured effects cannot be rolled back. The custom event itself is also a preparation event. The held-item break bridge covers custom blocks. Separate [placed-content plans](world-plans.md) cover physical use/removal/harvest sources, and the [held interaction bridge](held-world-interactions.md) coordinates native hand/entity/model inputs. Native projectile plans use the independent flight triggers described below. Script/gun providers and wider integration acceptance remain open.

Candidate `621bdb1d952a88bca30e9041d7dea79f06416c1d1fa436ee39063c3d7b00c6ed` passed 56 connected-player/API/protocol checks on each Spigot/Paper 1.21.11 endpoint with Java 21 and no ProtocolLib. The preceding independent preview failed 17 overlapping assertions in the same scenario. The same candidate passed 405 bundle/core checks across nine builds. Exact scopes are in [world-actions-verification.json](world-actions-verification.json), alongside its [distribution audit](world-actions-audit.json). Native-player behavior on 26.x is not established by the bundle matrix.

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

Numeric `durability`, `attributes`, `enchantments`, `equipment-model`, food and right-click shortcut bodies remain available inside a BlocksAdder bundle record's `traits`. Structured durability is read directly by the item adapter; the content loader does not normalize foreign documents. A remaining value must not be mistaken for a maximum. The observations above describe reference behavior, not an accepted complete external file format.

## Original food and consumable profiles

The observed `sj` parser accepts top-level `food` or `consumable`, with `food` taking precedence. Ordinary item profiles use `nutrition`, `saturation` with `saturation_modifier` fallback, `can_always_eat`, `consume_seconds` with `eat_seconds` fallback, `animation`, `sound`, `particles` and named `effects`. Canonical `always-eat` remains supported. Defaults are zero nutrition/saturation and two seconds. Fractional durations retain their precision. On the supported modern endpoints, saturation is the direct stored value; the legacy alias does not multiply it by nutrition. `hunger` belongs to the separate `behaviours.furniture_consumable` schema and is rejected as an ordinary food field.

Named consumable effects support `apply_status_effects`, `remove_status_effects`, `play_sound`, `teleport_randomly` and `clear_all_status_effects`, including suffixes for multiple entries. Apply effects contain named entries with `potion`, `duration`, `amplifier`, `ambient`, `particles`, `icon`/`show_icon`, plus a group probability. Removal accepts a potion list. Teleport diameter defaults to five. Unknown fields, invalid types and probabilities, duplicate singleton effects and unsupported effect families are rejected before registry publication. The original reversed nonempty-list check for removing effects is corrected.

Profiles use the public native component codec and copy their consumable through the existing cached `ConsumableSupport` bridge. Raw food and consumable components retain precedence independently. Drink animation defaults to the generic drink sound without particles; direct custom sound holders preserve resource-pack sound IDs. Native teleport effects honor normal teleport protection events. Source `po` explicitly dispatches both `eat` and then `drink` for non-POTION consumption and only `drink` for POTION; the event adapter preserves that compatibility behavior.

## Raw modern components

Inline `nbt` and source-relative `components_nbt_file` now accept modern item SNBT or JSON compounds containing `components`. The adapter extracts bounded outer fields and forwards opaque component values to the public `ItemFactory.createItemStack(String)` codec. It does not use NMS, CraftBukkit reflection, ProtocolLib internals or `UnsafeValues`. Typed arrays, byte/float/double suffixes, nested compounds and strings retain native syntax. The server validates component identifiers and payload schemas. Explicit removals use serialized `!minecraft:component` keys with empty compounds. Legacy `tag`-only data remains a separate conversion gap.

Configured material, requested amount and canonical BlocksAdder PDC identity always win, matching the observed original material/count behavior and preventing forged custom identities. Foreign PDC and opaque custom data survive metadata writes. Raw display names, item models and unconfigured lore are retained; explicitly configured later options such as lore, durability, repair cost, attributes and stack size are applied afterward. Raw equippable and glider components preserve their independent component boundaries. Original food normalization supplies missing nutrition/saturation from configured food values or zero. Explicit native consumables retain their duration through the cached Spigot/Paper bridge.

Component files resolve relative to `ItemDefinition.sourceFile`, remain inside the real `contents` directory, and reject symbolic-link paths, directories, missing files, malformed UTF-8 and data above 1 MiB. A bounded stream also enforces the limit while reading. Inline values share the same byte limit. Outer nesting is limited to 64 levels and component count to 128. File input takes precedence over inline input, as observed in the original parser. Duplicate keys or canonical component aliases are errors. A weak definition-keyed template cache shares the validated snapshot with the active item service; live item creation does not reread changed files. Reload constructs and validates new definitions before publishing them.

## Event execution

Original `mv`, `jj`, `jh`, `ji`, `ou`, `np`, `nm`, `qm` and action classes establish ordered action sections under `events.<trigger>`. Suffixes such as `play_sound_2` identify independent actions. `execute_commands` contains named command sections; flow references the command group's action ID. Group outcomes are accumulated in configuration order.

Supported native triggers cover left/right and shifted interactions, main/offhand interactions, entity interaction, attacks, kills, breaking, dropping, pickup, eating/drinking, bow shooting, books, fishing, held/unheld state, armor wear/unwear, durability break, gliding, elytra boost/collision, native buckets, armor stand equipment and item frames. Bukkit must emit the corresponding native event; the engine does not synthesize protected interactions from raw packets. Native hotbar and hand-swap callbacks retain the actual cancellable event, including identical-ID stacks and rapid transitions. Their source updates and returns avoid hand slots that vanilla overwrites after callback. Equipment reconciliation visits online player equipment only, every five ticks and after inventory changes, and suppresses duplicate native hand callbacks.

Supported action families are sound start/stop, ordered commands, [typed particles](particle-actions.md), [typed native effects and custom totem displays](effects-and-totems.md), durability/usage/amount changes, item grants/drops, experience drops, feeding, potion addition/removal on the player or target, bounded damage in sight/nearby/directly, explosions, native totem animation, custom model-data replacement/restoration, player-stat increments/decrements and cancellation. Nearby damage preserves original `HOSTILE`, `PLAYERS`, `PASSIVE` groups and uses a bounded query. Restorable model-data replacement toggles back to the configured item model data; it does not invent a property history.

The independent [`world_change` action](world-changes.md) conditionally replaces native/custom blocks or furnishings through protected domain transactions. Its `match`/`into` choices, native payload escrow, physical ownership authority, delay semantics and remaining tile/crop limitations are documented separately. It is a world operation rather than an inventory-property mutation.

Global `events_cooldown` or `events_settings.cooldown.ticks`, per-trigger `cooldown`, `all_actions_permission`, action permission negation (`!permission` / `-permission`) and `events_needed_player_stats` gates are enforced. Subsecond cooldowns retain their tick precision. Required stat comparisons accept the observed `>`, `<`, `=` and additionally `>=`, `<=`. Indicators apply to positive global cooldowns, matching the observed original HUD contract; per-trigger admission is independent. Cooldown indicators support `TITLE`, `BOSSBAR`, `VANILLA`, `HIDDEN`; bossbar color/style are configurable. Native cooldown groups use namespaced item identity and do not apply a material-wide cooldown to vanilla lookalikes.

The twelve observed stop/skip flow conditions are validated. An action reference must name an earlier action. Permission-denied actions do not supply a flow outcome. Delayed actions run independently, matching the observed scheduling shape; combining their own immediate flow condition is rejected. Delayed work is bounded to 128 jobs per player, revalidates the complete source stack and permissions, and is cancelled on reload, disconnect or shutdown. Plans allow at most 128 steps; recursive action damage is guarded. All operations run on the server thread.

Commands expand known player/target/world/location placeholders. A named command can additionally opt in with the strict boolean `placeholder_api: true` to resolve bounded external `%selector%` values in arguments; it defaults to false. Command labels remain fixed literals, replacements must be safe scalar identifiers/numbers, and callback-time source, inventory, permissions, world and lifecycle checks precede dispatch. These checks do not establish semantic security for privileged or nested administrator templates; trust the configured commands and installed providers. See [incoming command placeholders](command-placeholders.md) for the complete grammar, failure/flow behavior and independent bundle example. Registered plugin commands support silent console execution and bounded output matching through a public sender adapter. Capability validation also recognizes loaded plugins' declared commands and aliases before those plugins enable. Native command dispatch can unwrap that sender; native commands therefore require explicit `silent: false` with no captured-output predicate. Player command output predicates remain rejected. Unknown built-in placeholders and action fields are validation errors rather than literal dispatch or silently ignored conditions.

Immediate held `block_break` plans also accept [`world_sweep`](area-mining.md). Its independent region/frame/select schema queues bounded native/custom block mining after the origin commits, with per-cell protection, tool rules, source guards and durability. Positive action delays and other triggers are rejected for this family.

Native [projectile plans](projectile-actions.md) add `flight.launch`, `flight.block` and `flight.entity`. They bind the projectile item independently of held inventory, preserve impact receipts for delayed effects and use explicit direct-projectile/indirect-player damage attribution. Block/entity spatial effects retain their impact anchor.

## Explicit remaining gaps

This checkpoint rejects script/gun providers and external placed-trigger aliases, custom HUD cooldowns, legacy tag-only NBT, custom unregistered equipment sounds and captured native-command output. External command expansion is available only through the explicit trusted-provider opt-in and scalar limits in the [command placeholder guide](command-placeholders.md); arbitrary external free-text expansion remains unsupported. Conditional block/furnishing changes and independently named `world.use/remove/harvest` plans are implemented with the limits in their guides. Remaining families and wider world-change combinations need dedicated public capability adapters or domain integration before full parity can be claimed. Modern raw components use the public native codec; ProtocolLib availability alone is not evidence that a component codec exists.

Non-disappearing durability clamps the native damage component before native destruction; it exposes one native point remaining at exhaustion. Exact original zero-remaining presentation and exhausted-tool interaction policy remain open. Armor effects use public events and a five-tick equipment reconciliation window; they are not a claim of synchronous third-party equipment-event compatibility. Server-issued usage splitting preserves PDC identity and fresh remainder units. Rendering remains user verification.

## Verification checkpoint

Frozen food snapshot `a6c31c76240af885899af3593aa43b546621415e375f3c96fa8e8634117a45cf` passed the following development checks on Java 25 with Java 21 bytecode:

| Endpoint | Scope | Result |
| --- | --- | --- |
| Spigot 1.21.11 | Actual protocol player | 85/85 |
| Paper 1.21.11 | Actual protocol player | 85/85 |
| Spigot 1.21.11 | Components, validation and persistent configuration restart | 52/52 on each of two boots |
| Paper 1.21.11 | Components, validation and persistent configuration restart | 52/52 on each of two boots |
| Paper 26.3 | Components, validation and persistent configuration restart | 52/52 on each of two boots |
| Bounded outer parser | Pure boundary and food-alias cases | 30/30 |

Native acceptance covers both hands and sneaking, global cooldown isolation, per-trigger replay across fresh consumables, command/stat/effect/feed flow, reusable splitting/exhaustion, delayed source replacement and reload cancellation, durability protection, same-ID rapid hotbar and hand swaps, cancelled transitions, swap remainder conservation, exact grants into partial stacks, armor/cursor/offhand/chest transfer rules, dropping and cancellation, actual 0.2-second raw consumption, metadata precedence and opaque typed custom data. Invalid candidate checks verify that validation adds no listeners or tasks. File tests cover origin, validated snapshots after deletion, new-candidate failure, byte limits and invalid UTF-8. Final code fixes include empty armor slots, removed unwear identity, command-group references, plugin-command enable order and the separation of global HUD from per-trigger admission.

An earlier baseline passed 30 native Spigot assertions on Java 21. That runtime later became unavailable to new processes after a host OS/runtime failure, so continuation checks use the installed Java 25 runtime. No host settings were changed and no graphical client was launched. Paper 26.3 evidence covers server components and lifecycle, not native player protocol or rendered appearance.

Rich food acceptance additionally verifies actual status application/removal, clearing all effects, dual consume triggers, custom effect sound packets, random teleport and cancelled teleport protection. The additional headless cases reject unsupported hunger mapping, malformed profile shapes, invalid timing/probability and duplicate or unknown effects. Previous held/raw snapshot `e3ff5dced904e8824ee21e2ca87e67e283009998a5052649fe3bc837de60c2f3` passed 75 native checks per 1.21.11 server and 42 headless checks on two boots per endpoint before the food extension. Projectile/world/provider behavior and the final synthetic custom-break bridge remain separate work.
