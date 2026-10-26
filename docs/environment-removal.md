# Environmental removal rules

Blocks and crops can define `traits.environment` in an independent bundle record. These rules handle removal caused by the world, including events with no player. They are separate from the player-driven `world.use/remove/harvest` preparation plans.

```yaml
format: blocksadder/bundle-1
bundle: garden
entries:
  - key: stem
    kind: artifact
    item: {base: WHEAT}
  - key: river_herb
    kind: crop
    item: {base: WHEAT_SEEDS, label: River Herb}
    settings:
      stages: ['garden:stem', 'garden:stem']
      requires-farmland: false
      natural-destruction: true
      growth-seconds: 600
    traits:
      environment:
        water:
          effects:
            - {type: cue, sound: 'minecraft:block.bubble_column.bubble_pop', volume: 0.5}
            - {type: reward, item: 'minecraft:wheat_seeds', amount: 2}
            - {type: sparks, particle: BUBBLE_POP, count: 8, wait-ticks: 4}
        trample:
          prevent-removal: true
  - key: fragile_marker
    kind: block
    item: {base: PAPER, label: Fragile Marker}
    settings: {material: STONE, hardness: 1, blast-resistance: 0}
    traits:
      environment:
        explosion:
          effects:
            - {type: experience, amount: 3}
            - {type: cue, sound: 'minecraft:block.amethyst_block.break'}
```

## Causes and protection

Crops with `natural-destruction: true` accept `water`, `lava`, `trample`, `farmland-dry`, `piston`, `support-break`, `explosion` and `burn`. The crop lifecycle supplies the cause; for example, burning the support beneath a crop produces `burn`. Blocks accept `explosion`, through their protected custom-block explosion lifecycle. Furniture and inventory-only records reject these rules. Unsupported causes and fields reject the candidate before publication.

`prevent-removal: true` protects that content when the applicable removal is prepared. Existing native protection, `CustomCropDestroyEvent` and `CustomContentBreakEvent` remain binding. A new cancellable `CustomContentEnvironmentEvent` exposes a cloned anchor, content ID, kind, cause and optional player. Cancelling it stops the operation. A player is present only when the real cause supplies one; fluid/explosion events do not invent an actor. This does not turn environmental effects into player inventory actions.

Preparation checks the exact placement again after callbacks. Recursive preparation of the same anchor is denied, with a shared nesting bound of 32 anchors. Replacing a source with the same ID cannot transfer the outer removal to its replacement.

## Committed effects

Effects run only after the owned placement is removed and its removal saved. A rejected, discarded or later-cancelled native operation produces no effects. Calling the same removal receipt again does not repeat them. Arbitrary effects are not transactional: an exception or callback can stop later effects after an earlier effect has already occurred.

| Type | Fields and behavior |
| --- | --- |
| `cue` | Required resource `sound`; optional `volume` 0–16, `pitch` 0–2 and Bukkit `category` (default `BLOCKS`). Plays at the anchor center for nearby listeners. |
| `sparks` | Required Bukkit `particle` without typed payload; `count` 1–128 (default 12), `spread` 0–8 (default 0.2), `speed` 0–4 (default 0). |
| `reward` | Required custom or vanilla `item`; `amount` 1–256 (default 1), split by actual stack size. Short custom IDs resolve within the bundle. |
| `experience` | `amount` 1–10,000 (default 1), initialized on the spawned orb. |
| `shockwave` | `power` 0–8 (default 1), `fire` and `break-blocks` booleans (both default false). Uses Bukkit's native explosion/protection events. |
| `console` | Required one-line `command`, at most 2,048 characters, without a leading slash or external placeholders. Runs as the console. |

`reward` and `experience` honor both the caller's drop flag and `CustomContentBreakEvent` drop suppression, including creative-mode suppression. Other configured effects can still run when drops are disabled. Commands are trusted administrator configuration and can perform their own operations; their arbitrary side effects cannot be inferred or rolled back.

Console commands accept `{world}`, `{x}`, `{y}`, `{z}`, `{content}`, `{cause}` and `{player}`. Coordinates are the integer anchor and cause names use lowercase underscores. A command requiring `{player}` is skipped when no real actor exists. Unknown placeholders, external placeholder syntax and unsafe world/player identifier expansion are rejected or skipped. No chat or display-name text becomes a command.

## Delay and lifecycle

Each effect optionally accepts integer `wait-ticks` from 0 to 72,000. Delays schedule that individual effect and do not pause later immediate records. A rule allows at most 32 effects; the shared service retains at most 1,024 delayed jobs. Additional delayed work is skipped when that bound is reached.

Delayed effects retain the exact source retirement and the backing state observed after removal. Water/lava can remain at a retired crop anchor; an air-only guard would incorrectly suppress those effects. A new managed placement invalidates the older source even when that replacement is later removed. Changing the backing block also stops the applicable effects. Every immediate effect rechecks the source, so a callback that places new content stops the remaining records.

Jobs are indexed by chunk and cancelled when that chunk actually unloads. Reloading it cannot revive them. Content reload and plugin shutdown cancel remaining jobs and invalidate retained receipts. The service accesses only resident anchors on the server thread and does not keep content chunks loaded.

## Verification

The implementation is original BlocksAdder code and uses the public Bukkit API. It does not execute or translate the supplied reference plugin. The user owns rendered appearance checks. The [exact verification report](environment-removal-verification.json) records 160 scoped environmental assertions per Spigot/Paper 1.21.11/26.3 endpoint, 1,156 mixed native-player regression assertions across the same four endpoints and 405 core/bundle assertions across nine builds, all on one JAR. Only actual water and explosion scenarios are described as native environmental acceptance; all eight cause routes also have public API checks. Wider original action families and integration combinations remain in the parity tracker.
