# Typed particle actions

`play_particle` emits a cloud at the action's effect origin. `shoot_particle` samples a straight line every half block, including the origin and endpoint. Held actions use the player's eye position and view direction. Physical/projectile contexts use their adapter's effect origin; projectile impact delays retain the impact anchor. These actions use public Bukkit particle APIs and create no helper entities.

The payload format below belongs to BlocksAdder. Put actions inside a current-format bundle record:

```yaml
format: blocksadder/bundle-1
bundle: effects
entries:
  - key: ember_wand
    kind: artifact
    item:
      base: STICK
      label: '&6Ember Wand'
    traits:
      events:
        interact:
          right:
            shoot_particle:
              name: DUST_COLOR_TRANSITION
              distance: 7
              payload:
                shade: '#ff8030'
                fade: '#401020'
                scale: 1.25
              emit:
                count: 2
                spread: [0.04, 0.04, 0.04]
                speed: 0
                offset: [0, -0.2, 0]
                force: false
```

## Emission

`name`/`id` selects an existing Bukkit particle enum name on the running server. Integer `distance` is 1–32, default 7, and controls the line length. A cloud has one sample; a line has `2 * distance + 1` samples. Optional `emit` defaults preserve previous behavior: count 40 for clouds, one per line sample, zero spread/speed/offset and `force: false`.

| Field | Accepted values |
| --- | --- |
| `count` | Integer 0–1024 per sample |
| `spread` | Three finite numbers, each 0–32; default `[0, 0, 0]` |
| `speed` | Finite number 0–16 |
| `offset` | Three finite world-axis offsets, each −64 through 64 |
| `force` | Boolean, forwarded to the public API's distance override |

A zero count preserves Minecraft's single directional-particle mode. Only in that mode, spread components may be negative, down to −32. The budget counts it as one particle per sample. An action may request at most 4,096 particles. This is a per-action budget, not a global rate limiter; normal permissions/cooldowns remain available.

The engine prepares resident sample/payload pairs before emission. It skips unloaded samples/destinations without loading chunks. A missing/removed victim or cross-world destination skips that sample. The action succeeds when at least one sample is emitted, allowing ordinary flow conditions to use its outcome. Normal source checks, protection, permissions and delayed-job cancellation apply.

## Payload

Public `Particle.getDataType()` determines the payload shape. Omitting `payload` supplies the defaults below. A `Void` particle rejects even an empty payload section. Unknown fields and malformed values reject the candidate before publication.

| Particle/data family | Fields | Defaults and limits |
| --- | --- | --- |
| `BLOCK`, `BLOCK_MARKER`, `FALLING_DUST`, `DUST_PILLAR`, `BLOCK_CRUMBLE` | `state` | `minecraft:stone`; valid native block-state text, e.g. `minecraft:oak_log[axis=x]` |
| `ITEM` | `item` | `minecraft:stone`; vanilla material or existing custom item ID, relative to the source bundle |
| `ENTITY_EFFECT`, `FLASH`, `TINTED_LEAVES` / `Color` | `shade` | `'#ffffff'` |
| `DUST` | `shade`, `scale` | Red, scale 1; scale 0.01–4 |
| `DUST_COLOR_TRANSITION` | `shade`, `fade`, `scale` | Red to blue, scale 1; scale 0.01–4 |
| `EFFECT`, `INSTANT_EFFECT` / `Spell` | `shade`, `power` | White, power 1; finite power 0 through `Float.MAX_VALUE` |
| `SCULK_CHARGE` / `Float` | `value` | 0; finite float range, including negative rotation |
| `DRAGON_BREATH` / `Float` | `value` | 1; finite nonnegative float range |
| `SHRIEK` / `Integer` | `value` | 0; integer 0–1200 |
| `TRAIL` | `shade`, `ticks`, `destination` | White, 20 ticks; ticks 1–1200 |
| `VIBRATION` | `ticks`, `destination` | 20 ticks; ticks 0–1200 |
| `GEYSER`, `GEYSER_PLUME` | `value` | 1 water block; integer 0–64, where available |
| `GEYSER_BASE`, `GEYSER_POOF` | `water`, `impulse` | Water 1, impulse 1; integer water 0–64, finite impulse 0–16, where available |

Quote YAML colors. `shade`/`fade` accept `#RRGGBB` or `#AARRGGBB`; native RGB-only families use the RGB channels. Item/block templates are cloned per sample; item particles retain custom identity/model metadata.

Newer particles remain unavailable on older servers. Spigot 26.3 uses integer basic geyser data; Paper uses public `Particle.Geyser` objects. Both accept the same `value` setting. Configuration-time public constructors avoid linking newer geyser classes into the Java 21 baseline. No server implementation classes, private members or accessibility overrides are used. Unknown future data classes are rejected.

## Destinations

Without `destination`, trails/vibrations target one world block along positive Z from each sample. An explicit section defaults to `at: emitter` and zero shift:

```yaml
play_particle:
  name: VIBRATION
  payload:
    ticks: 20
    destination:
      at: victim
      follow: true
```

`at` accepts `emitter`, `player` or `victim`. Players/victims resolve their current eye positions at execution time. `shift` accepts three finite world-axis offsets between −64 and 64. Only vibrations allow `follow: true`, requiring an unshifted player/victim and using a native entity destination. Other vibrations use native block destinations, whose Y must fit −2048 through 2047. Resolution never searches arbitrary UUIDs or loads chunks.

## Evidence and limits

Retained static reference evidence identifies particle actions and a typed-data helper; it does not establish original support for these custom color, destination or emission fields. This is an independently designed BlocksAdder schema. The supplied original plugin was not executed.

Public contracts: [Spigot Particle](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/Particle.html), [Trail](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/Particle.Trail.html), [Spell](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/Particle.Spell.html), [Vibration](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/Vibration.html). Server packet acceptance and rendered appearance are separate evidence; the user owns visual checks. Typed `play_effect` data remains separate work.

## Verification checkpoint

Frozen JAR SHA-256 `9455cc01c064e4f792edd122a24af0f60e097f1e2618a266080d78191ff05d41`, 1,107,721 bytes, passed 210 mixed native/API/data assertions per 1.21.11/26.1.x build and 234 per 26.2/26.3 build: **1,986 particle assertions across nine builds**. The latter builds additionally exercise all four geyser defaults and explicit water/impulse payloads. Native held block/entity inputs and encoded payload/emission fields are observed by loopback clients; candidate rejection and the distant resident-anchor/unloaded-offset scenario have separate API/data scope.

The same JAR passed 118 projectile checks on each Spigot/Paper 1.21.11/26.3 endpoint and 45 core/bundle checks on each of nine builds: **2,863 scoped assertions, 22 server processes**. This count includes repeated assertions and is not a count of distinct native interactions. [Exact report](particle-actions-verification.json) records versions, hashes, probe provenance, fixture corrections and limits. The [distribution audit](particle-actions-audit.json) checks the exact JAR. Broader historical suites were not rerun for this artifact.

The verified artifact is available in [development preview 11](https://github.com/lino9999/BlocksAdder/releases/tag/v1.1.0-dev.11). Its [source CI](https://github.com/lino9999/BlocksAdder/actions/runs/40425579150) passed on Java 21/25, and the downloaded release JAR matched the frozen checksum; see [publication evidence](particle-actions-release.json).
