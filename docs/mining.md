# Custom block mining

Current development sources replace preview 1.0.0's one-click block removal with a timed mining service. Block definitions accept:

```yaml
block:
  hardness: 3
  break-tools: [PICKAXE]
  denied-tools: [WOODEN_PICKAXE]
  minimum-tier: iron
  require-correct-tool: true
  drop-only-correct-tool: true
  unbreakable: false
  sound:
    hit:
      name: minecraft:block.stone.hit
```

The original `break_tools_whitelist` and `break_tools_blacklist` names also work. Rules match material categories, material names and partial custom item IDs. Blacklist entries take precedence. `minimum-tier` accepts hand, wood/gold, stone, iron, diamond and netherite. A custom item can declare `mining.tier` and `mining.speed`. Hardness defaults to 0.5. Hardness zero or -1 breaks instantly; the separate `unbreakable` flag prevents mining. This preserves the original documented negative-one instant-break setting rather than interpreting it as vanilla bedrock hardness.

Mining progresses in server ticks and considers tool tier/speed, efficiency, haste, mining fatigue, underwater/airborne penalties, aqua affinity and the player's block-break-speed attribute. Session validation checks the actual held item, target ray, interaction range, game mode, permission and continued swings. Abort-dig, teleport, world change, held-slot change, hand swap, mode change, quit and reload terminate sessions and reset cracks. A shared task runs only while sessions exist.

The service checks cancellable Bukkit break protection before starting and again before completion. It then uses the custom content break event and existing persistence/drop lifecycle. Successful survival mining consumes tool durability through `PlayerItemDamageEvent`, respects unbreaking/unbreakable metadata and fires `PlayerItemBreakEvent` when appropriate. Creative breaking produces no drops or tool wear. Furniture/crop interaction removal retains its separate lifecycle.

`WorldContentService.canBreak(location, player)` checks protected eligibility. `breakPlaced(location, player, drops)` performs normal protected removal. `rollbackPlacement(location, expectedId)` is an internal transaction recovery tool: it removes only the expected managed identity without drops or re-firing cancellation, allowing generation to restore a previously captured backing state. Integrations must not use rollback as ordinary player authorization.

## Evidence and tests

The supplied artifact's block schema parser (`itemsadder/m/si`) reads hardness, tool blacklist/whitelist, five sound phases, light level, explosion behavior and permission suffixes. These are static observations; the independent mining implementation was not copied from its bytecode. The published reference documentation confirms the partial tool-name rules and negative-one instant setting.

Temporary protocol clients connected as two actual offline test players to isolated Spigot 1.21.11 and Paper 1.21.11 servers. Each run passed 34 assertions: correct/incorrect tools and tier, progress factors, native start/abort messages, delayed completion, held-tool wear, break-protection cancellation, model disguise/visibility restoration, stat clamping, bossbar delivery and resource-pack request delivery. The client observed positive block-crack stages and resets. A test-client startup issue was fixed by sending Minecraft's normal player-loaded acknowledgement; no production mining restriction was weakened.

These early tests verify gameplay requests and server/network state, not rendered crack textures, visual models, perceived latency or every animation/anti-cheat interaction. The current preview-6 JAR subsequently passed the expanded 56-check held-break/mining suite on Spigot/Paper 1.21.11 and all seven available 26.x builds, including native 26.2/26.3 protocols. See [native 26.x verification](native-26x-verification.md) for exact endpoints and method. The user owns appearance checks; broader feature reconstruction remains in progress.

## Area mining

Held block-break plans can queue a protected native/custom region through [world_sweep](area-mining.md). The origin retains ordinary mining timing; additional cells use the shared budget, source expectations and individual tool/protection/reward rules described in that guide.

Owned durability writes retain the source stack and hotbar slot across damage/break callbacks. A listener replacing the source or selecting an equal tool in another slot cannot redirect the charge or overwrite that inventory slot. Manual wear for non-native-material items uses a cloned candidate and commits only when its retained equipment source still matches.
