# Native effects and custom totem displays

BlocksAdder compiles `play_effect` payloads and `play_totem_animation` displays as part of a validated bundle candidate. These are independently designed content options. The supplied reference plugin was not executed, and its implementation, data factory and configuration files are not bundled.

## Native effects

`play_effect` uses the action's retained location, including the clicked block or projectile impact location supplied by its adapter. Optional `emit.offset` adds three finite world-axis offsets, each between −64 and 64. Integer `emit.radius` is 1–256, default 64. Unloaded emission chunks and locations outside the native signed block-position Y range, −2048 through 2047, are skipped. No chunk is loaded for an effect.

```yaml
format: blocksadder/bundle-1
bundle: effects
entries:
  - key: smoke_wand
    kind: artifact
    item:
      base: STICK
      label: '&7Smoke Wand'
    traits:
      events:
        interact:
          right:
            play_effect:
              name: SMOKE
              payload:
                face: UP
              emit:
                radius: 48
                offset: [0, 1, 0]
```

`name`/`id` selects a public Bukkit effect name. Public compatibility fields also resolve names that Paper moved to aliases, such as `SMOKE` and `CLICK1`. The lookup is cached from public API fields and uses no server implementation classes. Additional effects available only on one server remain provider-specific.

| Public data family | Payload | Default / validation |
| --- | --- | --- |
| Block material / block data | `block` | `minecraft:stone`; a current native block material |
| Direction | `face` | `UP`; only `DOWN`, `UP`, `NORTH`, `SOUTH`, `WEST`, `EAST` |
| Color | `shade` | `'#ffffff'`; quoted `#RRGGBB` or `#AARRGGBB`, converted to opaque RGB for the native effect |
| Bone meal quantity | `amount` | 15; integer 0–1024 |
| Other native integer data | `value` | 0; a signed 32-bit integer interpreted by that particular effect |
| Composter boolean | `success` | `false` |
| Other native boolean | `flag` | `false` |
| Dragon-breath sound | `audible` | `false`; the same native sound control on both forks |
| Axis | `axis` | `Y`; `X`, `Y` or `Z` |
| Record | `record` or `song` | Disc `minecraft:music_disc_13`; see below |
| No data | No `payload` section | An explicit payload is rejected |

Spigot exposes `STEP_SOUND` data as a material, while Paper exposes block data. Both accept the same `block` setting. Color alpha is not a property of these native effects; [particle actions](particle-actions.md) retain alpha where their native particle supports it. Generic integer `value` is native event data, not a universal particle count; use the relevant server API contract for that effect. Ordinary action permissions, cooldowns and flow remain applicable.

The obsolete door/gate/trapdoor effects are rejected. Spigot's obsolete `VILLAGER_PLANT_GROW` event 2005 is also rejected because the supported native client has no handler for it. Use `BONE_MEAL_USE` for portable crop particles. Paper's separately exposed `BEE_GROWTH` remains a native capability. Diagonal/SELF directions are rejected instead of inheriting the old adapter's conversion to downward smoke.

## Record playback

```yaml
play_effect:
  name: RECORD_PLAY
  payload:
    record: minecraft:music_disc_cat
```

Use `record: minecraft:air` to stop at the effect location. Alternatively, `song: minecraft:cat` selects an existing native jukebox-song registry key. `record` and `song` are mutually exclusive. Unknown discs/songs reject the candidate.

The native client uses song registry IDs for event 1010 and a separate stop event 1011. Paper's typed song API is used when available. The legacy Spigot bridge uses bounded, canonical song-key ordering through the public registry and the public integer effect overload. Its order must match actual client configuration registry data in the supported-build tests; public registry iteration order alone is insufficient. Custom datapack registry/reconfiguration combinations remain separate acceptance work. The fallback examines at most 4,096 current keys. No production NMS access or hardcoded disc-to-ID list is used.

## Custom totem displays

```yaml
format: blocksadder/bundle-1
bundle: symbols
entries:
  - key: crest
    kind: artifact
    item:
      base: PAPER
      label: '&6Golden Crest'
      sprite: minecraft:item/totem_of_undying
      variant: 11001
  - key: reveal_wand
    kind: artifact
    item:
      base: STICK
      label: '&6Reveal Crest'
    traits:
      events:
        interact:
          right:
            play_totem_animation:
              item: symbols:crest
```

Omit `item` for the ordinary vanilla animation. Custom references resolve against the source bundle and may also select a vanilla material. A display retains its material, model data, item model, custom data and other component metadata. The public component codec must round-trip the unchanged source before a display can be prepared; a lossy/unsupported source is rejected explicitly.

Preparation creates a display-only copy with empty-effects `minecraft:death_protection`. Native clients select the first hand item containing that component when handling the totem status. A temporary main-hand equipment view selects the display even when a real totem occupies the offhand. The server inventory, source count, health and original death-protection metadata remain unchanged.

The sequence is a self equipment view, native totem status, then a view of the current authoritative main hand. Restoration runs in `finally` and rereads current equipment, including callback-driven item/slot changes. A per-player guard rejects reentrant animations. A guarded item action rechecks its complete source after the temporary view. Delayed actions retain the engine's source/reload/disconnect cancellation rules.

The optional model-equipment mask recognizes only the exact temporary stack sent to its owner during that synchronous send. Restoration resumes normal mask policy; another viewer or modified/unowned stack gains no bypass. Third-party packet filters can still suppress or replace a client effect and require their own integration checks. The normal native particles/sound can reach nearby viewers; only the target receives the temporary item view.

Component preparation is bounded to 1 MiB of UTF-8 data, 64 nesting levels and 128 output component entries. Native metadata is parsed only enough to replace the top-level death-protection patch; nested values remain opaque. No server inventory write, helper entity, persistent display map or per-tick animation task is required.

## Commands and API

- `/ba totem <player> [item] [--quiet]` uses `blocksadder.totem`. The optional item is a custom namespaced ID. `--quiet` suppresses the success confirmation. Item IDs and the flag are tab-completed.
- `BlocksAdderApi.playTotem(Player, String)` uses a custom item ID, or `null` for vanilla. Unknown IDs and unavailable/reentrant players return `false`; malformed IDs or unsupported component round trips may throw `IllegalArgumentException`.
- All preparation and playback require the server thread. Immediate command/API calls create no pending job.

## Evidence boundary

Native packet/metadata checks and graphical appearance are distinct. The user performs rendered acceptance. Retained REA archive evidence identifies the reference feature family; the current REA evidence-reader request reached its 450-second host deadline without returning new Evidence. Public SDK contracts and independently observed native clients inform this implementation.

Primary API references: [Effect](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/Effect.html), [public component string](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/inventory/meta/ItemMeta.html#getAsComponentString()), [equipment views](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/entity/Player.html#sendEquipmentChange(org.bukkit.entity.LivingEntity,org.bukkit.inventory.EquipmentSlot,org.bukkit.inventory.ItemStack)). Native client bytecode establishes the death-protection selection and obsolete-effect/record contracts; no original plugin code is executed.

## Verification checkpoint

Frozen JAR SHA-256 `6e9fbf645cbb4cc50dd023741385f543de59ca1b3c3ff12617367835e7f290f0` passed **9,842 scoped assertions across 25 accepted server processes**: 8,081 new effects/totem checks on nine builds, 888 particle regressions on four endpoints, 468 optional ProtocolLib callback/mask assertions on three endpoints and 405 core/bundle checks on nine builds. [Exact report](effects-totems-verification.json) records every artifact/version, scope, fixture correction and limit. The [audit](effects-totems-audit.json) covers the exact distributed bytes. Counts include repeated native/API/data and headless assertions; they are not distinct rendered interactions or full original parity.

**Open integration issue:** native players disconnected before input with the tested ProtocolLib 5.5 snapshot in the full BlocksAdder/model integration scenario on Paper 26.3. A separate ProtocolLib-only/no-listener control passed nine checks. The cause is still unresolved; this does not prove a provider-alone defect. Core actions passed on that server without ProtocolLib. Wider model/camera integration remains required work.

The exact artifact is downloadable in [development preview 12](https://github.com/lino9999/BlocksAdder/releases/tag/v1.1.0-dev.12). Its [source CI](https://github.com/lino9999/BlocksAdder/actions/runs/40436012255) passed on Java 21/25, and the downloaded JAR matched the frozen checksum; see [publication evidence](effects-totems-release.json).
