# Quantity HUDs and player meters

BlocksAdder separates persistent meter data from HUD presentation. Several HUDs can read the same meter without changing its limits or mutation policy. Content uses `blocksadder/bundle-1`; configuration files from other plugins are not accepted.

## Complete bundle example

Supply three original 8-by-8 PNGs at `resourcepack/assets/garden/textures/gui/full.png`, `half.png` and `empty.png` inside the plugin data directory. An opaque eight-pixel bitmap at height eight has a nine-pixel advance, including Minecraft's spacing. The explicit `advance` must match the actual font asset. This declaration describes layout; it does not resize the texture or alter the native bitmap advance.

```yaml
format: blocksadder/bundle-1
bundle: garden
entries:
  - key: full
    kind: glyph
    settings:
      character: "\uE310"
      font: garden:meters
      texture: garden:gui/full
      height: 8
      ascent: 8
      advance: 9
  - key: half
    kind: glyph
    settings:
      character: "\uE311"
      font: garden:meters
      texture: garden:gui/half
      height: 8
      ascent: 8
      advance: 9
  - key: empty
    kind: glyph
    settings:
      character: "\uE312"
      font: garden:meters
      texture: garden:gui/empty
      height: 8
      ascent: 8
      advance: 9
  - key: energy
    kind: meter
    settings:
      min: 0
      max: 10
      default: 10
      regeneration-per-second: 0
      rules:
        worlds:
          include: [world, 'survival_*']
          exclude: [survival_testing]
        creative: false
        pulses:
          - interval-ticks: 100
            delta: -0.25
            biomes: [minecraft:desert]
            sky-light: {min: 13, max: 15}
        food: {gain: 0.5, loss: 0}
        respawn-delta: 5
        depleted:
          interval-ticks: 20
          damage: 1
          effects:
            - {type: slowness, duration: 40, amplifier: 0}
  - key: energy_pips
    kind: overlay
    settings:
      stat: garden:energy
      type: actionbar
      permission: blocksadder.hud
      visible-by-default: true
      visibility:
        creative: true
        spectator: false
        breath: any
        riding: any
        worlds:
          include: [world, 'survival_*']
          exclude: [survival_testing]
      placement: {x: 0, center: true}
      art:
        mode: pips
        slots: 10
        direction: right
        full: garden:full
        half: garden:half
        empty: garden:empty
  - key: quest_icons
    kind: overlay
    settings:
      stat: garden:energy
      type: actionbar
      permission: blocksadder.hud
      placement: {x: 80, center: false}
      art: {mode: custom}
```

Run `/ba validate`, `/ba reload` and `/ba pack build` after saving content and assets. Apply the generated resource pack to display custom images. Glyph fonts are preserved in outgoing components; the server does not flatten every image into the default font.

## Art modes and placement

`pips` uses one to 64 slots, default ten. It maps the bounded meter range onto those slots: whole slots use `full`, any remaining fraction uses `half`, and unused slots use `empty`. `direction: left` reverses their order. All three glyph references are required.

`frames` selects `floor(value - min)` from a nonempty `frames` list of at most 256 glyph IDs, clamping to the first or last frame. A frame for every integer step uses `max - min + 1` images. Example: `art: {mode: frames, frames: [garden:empty, garden:half, garden:full]}`.

`custom` displays an ordered per-player sequence controlled through the public API. It starts empty. Sequences are copied and canonicalized, contain at most 64 registered glyph IDs and are cleared on quit or successful content reload. An invalid reload preserves the current sequence.

Every selected glyph needs a printable Unicode character, a valid font ID and an integer `advance` from zero to 512. Short glyph IDs resolve only when unambiguous. Unrelated glyphs without an advance continue to work in existing text replacement features. Art requires the `actionbar` type; an ordinary title belongs in a separate HUD definition.

`placement.x` is an integer from -16384 to 16384. `center` defaults to true and subtracts half the declared art width. Each image HUD restores the text cursor after its glyphs, so other image HUDs can use their own offsets. For an image-only actionbar, zero is its horizontal center. When ordinary text HUDs share the line, Minecraft centers that text and image offsets start at the text line's origin.

The generated `blocksadder:layout` font uses independently assigned supplementary characters and signed advances. Its font ID and `assets/blocksadder/font/layout.json` path are reserved. An authored collision makes pack generation fail explicitly. No original font tables, textures or shaders are included.

There may be at most 128 HUD definitions. Actionbars share a budget of 256 image glyphs, 8192 expanded title characters and 128 configured style markers. Fixed art is checked before publication; custom sequence edits check the combined retained sequences before changing state. Bounds limit packet growth and per-player rendering work.

## Visibility and persistent values

HUD show permission permits display; an empty permission allows everyone. Manual show/hide is remembered in player persistent data. `hudVisible` reports the effective current state. Manual show does not override a denied permission, a disallowed world or a visibility condition.

Visibility accepts `creative` (default true), `spectator` (default false), `breath: any|reduced|full` and `riding: any|mounted|unmounted`. Reduced breath means remaining air is below maximum air. Visibility world selectors accept either a list or `{include: [...], exclude: [...]}`. An optional trailing `*` matches a prefix, empty inclusion allows all worlds, and exclusions win. Names match case-sensitively and do not load worlds or chunks.

Meters retain their existing namespaced PDC identity across reloads and restarts. Finite values are clamped to increasing finite bounds; absent or corrupt values use the configured default. `adjustStat` adds a finite delta with saturation at the bounds, including arithmetic that would otherwise overflow. Progress normalization also supports ranges spanning `-Double.MAX_VALUE` to `Double.MAX_VALUE`.

## Automatic changes and source policy

`regeneration-per-second` applies once per 20 eligible player ticks. Each pulse and depleted-effect interval must be a multiple of five between five and 1,728,000 ticks. Each player owns an eligible-time clock. Disallowed worlds, spectator, creative when disabled and automatic bypass pause that meter's clock. Hidden HUDs do not pause meters. Quit and reload discard temporary clock state; a newly joined player does not inherit the server's global pulse phase.

A meter supports at most 64 pulses. Their finite signed deltas run at their own intervals. Optional registered biome keys and sky-light bounds are checked at the player's resident location. The service never loads a destination chunk for a condition. Sky-light bounds are inclusive and default to zero through fifteen.

`food.gain` and `food.loss` multiply positive and negative native food-level changes separately; each defaults to zero. The shorthand `food-multiplier` applies to both directions and is mutually exclusive with `food`. Cancelled food events do not change a meter. `respawn-delta` applies on the next server tick after respawn, using the new location and current player identity; a reload invalidates the pending operation.

At the minimum value, `depleted` can apply public native damage and at most 16 potion effects at its configured interval. Native protection/cancellation remains effective. Each remaining effect rechecks the current meter, player and configuration after callbacks. Defaults are damage zero, potion duration 40 ticks and amplifier zero. Damage is bounded to 1024, potion duration to 1,200,000 ticks and amplifier to 255.

API, command and automatic bypass are distinct. A granted bypass **prevents** writes or effects; it is not permission to ignore protection. Built-in global nodes are:

- `blocksadder.bypass.meter.api`
- `blocksadder.bypass.meter.command`
- `blocksadder.bypass.meter.automatic`

All default to false, including for operators. A per-meter node appends the complete canonical ID, for example `blocksadder.bypass.meter.api.garden:energy`. Per-meter dynamic nodes require an explicit permission assignment, avoiding Bukkit's operator default for unknown permissions. `rules.bypass` may also specify extra `api`, `command` or `automatic` permission names; registered extra permissions retain their declared defaults.

API setters and deltas return the unchanged current value when blocked. Command writes, additions and subtractions report an English error, including self-issued commands. Automatic bypass covers timer, food, respawn, depleted effects and held-item stat actions. Environmental world/game-mode conditions govern timer and native environmental changes. Held-item stat actions retain their own event admission, including creative use without consumption, while still honoring automatic bypass.

## Commands and integration API

Existing commands remain `/ba hud <show|hide> <hud|all> [player]` and `/ba stat <read|write|add|subtract> <player> <stat> [value]`, protected by their documented command permissions. No original command aliases or permissions are introduced.

The Bukkit `BlocksAdderApi` service provides server-thread operations:

```java
api.stat(player, "garden:energy", 7.5);
api.adjustStat(player, "garden:energy", -0.5);
api.hud(player, "garden:energy_pips", true);
boolean visible = api.hudVisible(player, "garden:energy_pips");
api.hudGlyphs(player, "garden:quest_icons", List.of("garden:full", "garden:half"));
List<String> icons = api.hudGlyphs(player, "garden:quest_icons");
```

Returned sequences are immutable. All player meter/HUD operations reject asynchronous access. Nested content reloads and reload during shutdown are rejected before publication; outgoing packet callbacks cannot activate a second candidate during the outer reload.

## Verification scope

The [exact candidate report](hud-meters-verification.json) distinguishes native server checks, owned pure helpers and private observer checks. The [distribution audit](hud-meters-audit.json) compares the independent JAR with the supplied archive without executing that archive.

The native observer has its own bounded text/run/font limits and flags truncation. Its selected fixtures do not establish successful observation of every maximum-budget packet. Helper checks exhaust the spacing encoder's supported signed integer domain separately. Minecraft's heterogeneous NBT list wrappers and modified UTF-8 are decoded explicitly so plain text and supplementary spacing characters are retained.

Rendered image alignment, actual font advances, animation appearance and interaction with other plugins' actionbar output remain user checks. Full reference-plugin parity, arbitrary stat providers, wider third-party integrations and every HUD combination remain open in the [development tracker](parity-progress.md).
