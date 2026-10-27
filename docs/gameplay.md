# Recipes and gameplay

Definitions live in YAML files beneath `plugins/BlocksAdder/contents/`. Each file declares a `namespace`; the short keys below become IDs such as `example:ruby_recipe`. References to items should use full `namespace:id` strings. A short item ID resolves only when unambiguous. All examples assume the referenced custom items exist.

## Recipes

```yaml
namespace: example
recipes:
  ruby_recipe:
    type: shaped
    result: example:ruby
    amount: 1
    shape:
      - ' D '
      - 'D D'
      - ' D '
    ingredients:
      D: minecraft:DIAMOND
    permission: blocksadder.recipe.ruby
  dust_recipe:
    type: shapeless
    result: example:ruby_dust
    amount: 2
    ingredients:
      - example:ruby
      - minecraft:FLINT
  smelt_ruby:
    type: furnace
    ingredient: example:ruby_ore
    result: example:ruby
    cooking-time: 200
    experience: 0.7
  cut_ruby:
    type: stonecutting
    ingredient: example:ruby
    result: example:ruby_dust
    amount: 2
  upgrade_blade:
    type: smithing
    template: minecraft:NETHERITE_UPGRADE_SMITHING_TEMPLATE
    base: minecraft:DIAMOND_SWORD
    addition: example:ruby
    result: example:ruby_sword
  repair_blade:
    type: anvil
    base: example:ruby_sword
    addition: example:ruby
    result: example:ruby_sword
    experience-cost: 3
```

Supported types are `shaped`, `shapeless`, `furnace`, `blasting`, `smoking`, `campfire`, `stonecutting`, `smithing` and `anvil`. Set `enabled: false` to omit a recipe. A result amount must fit in one result stack. Shaped recipes support normal mirroring and offsets; all rows must have equal length, up to three columns and three rows. A space is an empty slot. Shapeless recipes take one to nine ingredient entries; repeat an entry to require more than one occupied slot. Ingredient stack sizes do not increase a recipe's input requirement.

Custom ingredients use exact item metadata, including persistent identity. Renaming, enchanting, damaging or recoloring a custom ingredient can make it no longer match its configured prototype. A vanilla ingredient uses a material choice and rejects protected custom items using that same base material.

`permission` is optional and applies to player crafting, stonecutting, smithing and anvil results. Permission checks occur when preparing and taking relevant results. Recipes are discovered on join and after a successful reload for permitted players. A crafter has no player actor, so it cannot execute a permission-restricted recipe. Cooking recipes reject a `permission` field because an automated furnace or campfire has no authoritative player actor.

Cooking time is in server ticks, between 1 and 72,000; experience is between 0 and 10,000. Default cooking time is 200 ticks, or 600 for campfires. Smithing uses a template, base and addition; the configured result replaces the base rather than inheriting its enchantments, damage or other modifications. Anvil recipes require exactly one base item in the left slot, consume one addition and charge 1–100 experience levels. The configured anvil output is fixed; it does not inherit a typed rename. Regular anvil renaming remains available when a custom recipe does not match.

Protected custom items are blocked from unrelated vanilla crafting, cooking, smithing, stonecutting, brewing and furnace fuel consumption. An anvil also rejects a protected custom right-hand ingredient unless the left item has the same custom identity or an explicit custom anvil recipe matches. This protects custom ingredients without replacing all vanilla repair behavior. An item can explicitly opt out with `allow-vanilla-recipes: true`.

Recipe reload builds and validates all candidate recipes before removing previously registered recipes. A failed Bukkit registration restores the previous registered recipe set. The plugin only removes its own recipe keys. `RecipeService.validate(candidate)` and `GameplayService.validate(candidate)` parse candidate definitions without registering recipes, creating tasks or publishing player-visible state.

## Player stats and HUDs

```yaml
namespace: example
stats:
  energy:
    min: 0
    max: 100
    default: 100
    regeneration-per-second: 1
huds:
  energy_bar:
    stat: example:energy
    type: bossbar
    title: '&bEnergy &f{value}/{max}'
    color: BLUE
    style: SEGMENTED_10
    visible-by-default: true
    permission: blocksadder.hud
```

Stats are stored in player persistent data. Reads and writes clamp to the configured bounds; missing values use `default`. Bounds and values must be finite numbers, with `max > min`. Regeneration runs once per 20 eligible player ticks; the meter rules in the HUD guide determine admission. Negative regeneration is allowed. Stat updates require the server thread.

HUD types are `bossbar` and `actionbar`. Titles support `{player}`, `{value}`, `{min}`, `{max}` and `{percent}` plus `&` color codes. Several actionbar HUDs combine into one line. Bossbar colors and styles use Bukkit `BarColor` and `BarStyle` names. HUD visibility is remembered per player; `hud(player, "all", false)` hides every configured HUD. Permission removal hides a HUD on the next update. Hidden or disconnected players have no active bossbar objects retained. HUD updates run once per second, not once per tick.

## Right-click actions

```yaml
namespace: example
items:
  energy_apple:
    material: APPLE
    display-name: '&bEnergy Apple'
    actions:
      right-click:
        permission: blocksadder.use
        consume: 1
        cooldown: 2.0
        food: 4
        saturation: 2.4
        heal: 2
        effects:
          - type: minecraft:speed
            duration: 200
            amplifier: 0
        stats:
          'example:energy': 15
        sound: minecraft:entity.player.burp
        message: '&aEnergy restored.'
        cancel-block-interaction: true
```

The action runs once for the main hand when item use is permitted by the interaction event. Permission and cooldown are checked before applying changes or consuming items. Creative players receive the action without consumption; spectators cannot use it. `consume` defaults to zero. `cooldown` is in seconds, up to 86,400. Potion duration is in ticks; amplifier zero is level I. Food and saturation additions cap at normal player limits; healing caps at maximum health. Configured stat changes use existing stat IDs and clamp to their bounds. Unknown effects, stats or invalid numeric values reject the configuration. There is no command-execution action.

## Font image aliases

```yaml
namespace: example
font_images:
  ruby_icon:
    character: "\uE001"
    replace:
      - ':ruby:'
    permission: blocksadder.glyphs
```

Each glyph needs exactly one Unicode code point. A matching resource-pack font provider supplies its image; the glyph definition by itself does not draw a texture. Aliases expand in ordinary player chat, sign edits and book edits. Server-side `expand(text)` expands all configured aliases for trusted plugin-generated text. Player input expansion checks the definition's permission, defaulting to `blocksadder.glyphs`. Use an empty permission to permit everyone. Async chat consumes immutable permission snapshots refreshed on the server thread each second, so a permission change can take up to one second to affect chat aliases. It does not read player persistent data or call permission providers asynchronously. Newer chat integrations may supersede Bukkit's legacy chat event and need their own integration.

## Loot tables

```yaml
namespace: example
loot:
  ruby_from_stone:
    source: block
    match: [STONE, DEEPSLATE]
    worlds: [world]
    require-player: true
    replace: false
    permission: ''
    drops:
      - item: example:ruby
        chance: 0.02
        min: 1
        max: 2
  ruby_from_zombies:
    source: entity
    match: [ZOMBIE]
    drops:
      - item: example:ruby
        chance: 0.1
  treasure:
    source: fishing
    match: [COD, SALMON]
    drops:
      - item: example:ruby
        chance: 0.01
```

Sources are `block`, `entity` and `fishing`. An empty `match` list matches every source of its type; an empty `worlds` list matches every world. Block matching uses the broken vanilla material. Entity matching uses the custom entity ID when present, otherwise the vanilla entity type. Fishing matching uses the caught item's material. A player killer is required for entity loot by default; set `require-player: false` to permit other death causes. Creative and spectator actors never earn these drops. An optional permission requires a player with that permission.

`chance` is a probability from 0 to 1, rolled independently for each drop and matching table. `min` and `max` are inclusive, from 1 to 4,096; large amounts split into legal stack sizes. `replace: true` removes the source's vanilla drops even if the custom roll fails. Several matching tables combine. General tables add to custom entity definitions' own `drops`; configure the two deliberately to avoid unintended duplicate rewards. Custom block breaks have their own item-drop lifecycle and do not enter vanilla block-drop tables.

Cancelled block and fishing events do not produce custom loot. Block additions and fishing replacement wait until the next server task so later event handlers can cancel the event first. The plugin does not cancel region-protection decisions. Block drops also require their chunk to remain loaded at delivery time. No per-tick world scan is used.

## Simple emote timelines

```yaml
namespace: example
emotes:
  celebrate:
    permission: blocksadder.emote
    duration: 40
    frames:
      - at: 0
        swing: true
        particle: HAPPY_VILLAGER
        count: 8
        sound: minecraft:entity.player.levelup
      - at: 20
        particle: HEART
        count: 4
```

Timelines combine a main-hand swing, supported particles, sounds and optional messages. `duration` and `at` are in ticks. Duration is limited to 1,200 ticks, frames to 128 and particle count to 100 per frame. Particles requiring extra data are rejected. A player has one active timeline; starting another replaces it. `stop` and `stop-now` stop immediately. Active timelines end on quit, death, reload or plugin disable. One shared task runs only while timelines are active. These are public-API gestures and effects; full-body skeletal avatar animation, shader emotes and player disguises are separate capabilities and are not provided by this timeline system.

## Verification boundary

These services were compiled with Java 21 against Spigot API 1.21.11. Compilation establishes API availability, not client rendering or complete interaction behavior. See `verification.md` for the final recorded server and client checks. Recipe exactness, permissions and transactional reload paths need integration coverage whenever these schemas change.

## Expanded image HUDs and meter rules

Development preview 14 adds full/partial/empty image bars, quantity-selected frames and API-owned custom sequences, with named fonts, bounded offsets, world/mode visibility, source-aware bypass and eligible player timers. See the complete current-format [HUD and meter guide](hud-meters.md) and [exact candidate evidence](hud-meters-verification.json). Environmental admission applies to timers/native changes; held-item stat actions retain creative use while respecting automatic bypass.
