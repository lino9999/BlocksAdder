# Area mining

`world_sweep` adds a bounded mining region to a held item's `block_break` plan. It accepts native blocks and BlocksAdder blocks, and starts after the origin break commits. Each additional block passes through its own native protection events; custom blocks also use their content permissions, protection, tool whitelist/blacklist, tier, unbreakable and reward rules. This is an independently designed action.

```yaml
format: blocksadder/bundle-1
bundle: quarry
entries:
  - key: excavator
    kind: artifact
    item: {base: DIAMOND_PICKAXE, label: Excavator, stack: 1}
    traits:
      durability: 1000
      mining: {speed: 8, tier: diamond}
      events:
        block_break:
          world_sweep:
            region:
              min: [-1, -1, 0]
              max: [1, 1, 2]
            frame: surface
            permission: quarry.excavate
            select: [minecraft:stone, minecraft:deepslate, quarry:ore]
            preserve: [mechanisms]
            rewards:
              mode: origin
              silk-required: false
  - key: ore
    kind: block
    item: {base: PAPER, label: Quarry Ore}
    settings:
      material: BARRIER
      display: false
      hardness: 1.5
      minimum-tier: iron
      require-correct-tool: true
      drop-only-correct-tool: true
```

The origin follows ordinary native/custom mining timing. Additional cells are attempted without a separate client mining timer. The action reports whether it queued eligible cells, rather than promising every cell will break. One pending sweep per player and suppression of nested sweep requests prevent recursive expansion. Other ordinary item actions can still run on additional block breaks.

## Regions and coordinate frames

A box's `region.min` and `region.max` vectors each contain exactly three integers from -8 to 8. The inclusive box must contain its origin and have 2–343 cells, including the origin. The origin is excluded after transforming the coordinates and is never broken a second time. The default `world` frame uses world X/Y/Z offsets. In the optional `facing` frame, +X means the player's right, +Y means up and +Z means forward, snapped to the nearest horizontal cardinal direction when queued. Existing world/facing iteration order is preserved. Pitch does not rotate either frame.

`frame: surface` instead uses the accepted clicked block face. Local +Z points inward, opposite that outward face. Horizontal faces retain the same right/up/forward convention as `facing`; top and bottom use a fixed roll with local +X pointing world east. World +X/+Y/+Z mean east/up/south:

| Clicked face | Inward local +Z | World offset for local `(x,y,z)` |
| --- | --- | --- |
| NORTH | SOUTH | `(-x,y,z)` |
| SOUTH | NORTH | `(x,y,-z)` |
| EAST | WEST | `(-z,y,-x)` |
| WEST | EAST | `(z,y,x)` |
| UP | DOWN | `(x,-z,-y)` |
| DOWN | UP | `(x,z,y)` |

Surface box offsets are sorted by transformed world X, then Y, then Z before candidate collection. Every transform is an exact integer axis mapping, so it introduces no duplicated cells or rounded coordinates. The frame is captured when the sweep is requested; looking around afterward does not bend the pending region.

An upward column is a separate region shape:

```yaml
world_sweep:
  region:
    column: 12
  frame: surface
  permission: quarry.excavate
  preserve: [resources, mechanisms]
  rewards: {mode: natural, silk-required: false}
```

`column` must be the region's sole key and have an integer height from 2 through 343. It defaults to the surface frame; an explicitly different frame is rejected. Columns accept only horizontal clicked faces. A top/bottom face produces a false action outcome without a queued sweep. The column stays at the origin X/Z and extends in world +Y. If `originY > playerY` when queued, its base offset is -1; otherwise it is 0. Exactly `height` cells are described before the origin is excluded, giving at most `height - 1` additional candidates. This anchoring is retained even if the player later moves.

Columns have their own height/reach bound: height 343 can reach +342 from an unshifted base, or +341 from a base of -1. The existing box coordinate limit of ±8 is unchanged. World height, resident chunks, permissions and the ordinary extra-break path still limit actual work; a tall column does not load chunks or guarantee every described cell will break. A region cannot mix `column` with box vectors.

## Surface input admission

The surface bridge captures a main-hand native left-click on a Cartesian block face. Its short-lived admission retains the exact player connection, world/block, backing state, full main-hand tool and selected slot. A managed origin also retains its exact placement's `Target.current` authority, so removing and recreating the same custom ID invalidates the old admission. Ordered surface actions may inspect the same admission until that origin attempt finishes, so a failed or empty first selector does not consume the face needed by a later fallback. Queries do not consume it. Once an action queues a sweep, the one-pending-sweep rule prevents a second job; origin completion or lifecycle invalidation retires the admission. Another block, stale source, changed state or missing admission cannot supply a guessed face; surface/column requests then fail while world/facing remain available.

Admissions expire lazily after two seconds without a matching animation refresh. An animation refresh requires the retained player, block/backing, slot and complete tool to remain current. Abort, world/teleport/mode/hand/slot transitions, disconnect and reload clear the relevant state. The bridge distinguishes custom mining's own interaction takeover from an unrelated denial; synthetic protection probes do not consume the admission. A cancelled or uncommitted origin still launches no additional work.

Native redstone ore has narrowly scoped state reconciliation. An uncancelled `BlockDamageEvent` may confirm the normal unlit-left-click to lit-damage change for `REDSTONE_ORE` or `DEEPSLATE_REDSTONE_ORE`. A matching, finally uncancelled `BlockFadeEvent` may then authorize its exact lit-to-unlit decay after the new backing state actually appears. The contact index selects only players holding that block's lease. Exact player/block/tool/slot authority remains required; other backing changes, cancelled or retargeted fades and managed replacement states are rejected.

Public Bukkit `BlockBreakEvent` does not carry a clicked face or prove whether client input or `Player.breakBlock` caused it. The bridge associates it with a current matching admission; that association is not proof of packet causality. Face-less API operations cannot rely on an unrelated last-click cache, and the bridge does not infer a face from yaw/pitch or ray tracing.

## Selection and preservation

`select` is optional. An omitted or empty list admits eligible blocks without a material filter. Up to 64 native block-data patterns or enabled custom block IDs are accepted. Native patterns use partial state matching and exclude managed content, even when its backing material matches. Custom IDs match the retained placement instance. Furnishings, crops, liquids, air, native negative-hardness blocks and externally owned fluid/fire cells are excluded. A denied cell is skipped; later eligible cells can still be attempted.

`preserve` is an optional list containing unique `resources` and/or `mechanisms` entries. Wrong types, unknown names and duplicates fail validation. Preservation excludes additional candidates even when `select` matches; it does not change the directly mined origin.

- `resources` preserves native materials ending in `_ORE`, plus ancient debris and crying obsidian, and all additional custom BlocksAdder blocks.
- `mechanisms` preserves native `TileState` blocks, `Powerable`/`AnaloguePowerable` block data, doors, trapdoors, rails and beds. It also covers crafting/cartography/fletching/smithing tables, looms, stonecutters, grindstones, composters, all anvil states, redstone wire/torches, repeaters, comparators and tripwire/hooks.

These are bounded local type/tag checks on prepared candidates. They do not scan or discover entire connected mechanisms, and mechanisms preservation does not classify custom blocks by their underlying native backing state. Use `resources` to preserve all custom candidates.

## Additional rewards

The optional `rewards` section accepts only `mode` and the strict boolean `silk-required`. Defaults preserve the earlier behavior: `mode: natural`, `silk-required: false`. Allowed modes are:

| Mode | Additional-cell rewards |
| --- | --- |
| `natural` | Keep normal native drops/experience and the existing custom block loot policy at each cell. |
| `none` | Suppress additional-cell item rewards and experience. |
| `origin` | Route supported additional item rewards to the original broken block's center; retain the receiving loot policy and event protections. |

With `silk-required: true`, a missing Silk Touch enchantment suppresses the extra rewards rather than rejecting removal. Native/custom tool correctness, configured loot restrictions and creative-mode behavior remain binding. If the origin's effective native/custom drop permission is false, including caller suppression, or its native `BlockDropItemEvent` is cancelled, the remaining sweep suppresses additional rewards. These controls affect extras only: they neither create origin rewards nor prevent permitted additional removals and ordinary durability/usage charges. Cancelling a break itself still prevents that removal and can stop origin commitment.

For native `origin` rewards, the router retains the actual `Item` references exposed by that cell's `BlockDropItemEvent`. Its synchronous scope has a shared budget of 1024 item-list inspections and at most 32 observed drop events. It relocates only still-valid items retained by the event and still at their observed positions. Event cancellation, removed event entries and items moved by another plugin are respected. The default destination must remain resident in the same world; if it is unavailable, the item remains at its ordinary per-cell location. Items outside the inspection budget are not synthesized or aggregated.

Captured native item relocation emits the cancellable public `AreaRewardMoveEvent` on both supported server families before calling `Item.teleport`. Integrations can inspect `getItem()`, `getPlayer()`, `getSourceBlock()`, `getFrom()` and `getTo()`, cancel the move or choose a different destination with `setTo(Location)`. Location getters return copies. The router then rechecks its live scope, the item and its unchanged observed position, and requires finite coordinates and a resident destination. Cancellation or a failed check leaves the item under its existing ownership; an item moved directly by another listener is not moved back. The eventual teleport still uses the server's public API, but native item teleport-event behavior differs between forks, so integrations needing a portable veto or redirect should use `AreaRewardMoveEvent`.

The move event covers captured native item relocation. Custom loot and escrowed container contents select their destination before creating an item entity, so those spawn routes do not emit it. Their normal spawn/protection behavior remains applicable.

Owned custom item rewards and configured block-loot rewards use their existing loot route with the origin as destination when available. Native experience stays at the additional cell; configured custom loot experience follows its selected loot destination. This does not capture arbitrary `ItemSpawnEvent` traffic, call context-free `getDrops`, combine items by material or claim ownership of third-party rewards. Provider/plugin side effects and already committed breaks are not rolled back by reward routing.

For policies needing container-payload suppression or origin routing, supported Bukkit `Container` inventories use a bounded escrow of at most 256 local slots. A chest uses its own block inventory rather than the combined double-chest inventory. The router snapshots complete stacks and metadata, clears that retained payload around the owned removal, and either suppresses or releases it according to the effective drop policy. Cancelled or failed removal restores an unchanged empty source where possible; it does not overwrite foreign replacement inventory. Source/destination residency and the existing return path remain binding. This handles container contents separately from the container block's ordinary drop event instead of collecting unrelated item spawns.

Escrow is limited to supported `Container` slot contents and excludes `ShulkerBox` payload handling through this path. Non-`Container` storage, arbitrary block-entity payloads and furnace recipe-experience bookkeeping are not established by this contract. Native block-item metadata and ordinary loot rules remain separate. These additions are a development capability, not a claim of complete original multiple-break or storage parity.

## Scheduling and source authority

The sweep snapshots resident candidate states and managed placement identity. It never loads or generates a missing chunk. Changed backing states, removed/recreated managed candidates and unloaded candidate chunks are skipped. Work shares a global budget of 32 attempted cells per tick; unfinished players rotate through the queue. Attempts include cells skipped after a later state change, so the budget also bounds validation work.

The original hotbar slot and complete source stack remain authoritative. Confirmed durability events and committed custom wear can update the retained expectation; arbitrary metadata/amount changes or replacements cannot. Exhaustion, death, disconnect, teleport, world/mode/hand/slot changes, reload, shutdown, permission loss or leaving 32 blocks of the origin stops remaining work. The item-level `all_actions_permission`, action `permission` and `blocksadder.break` are checked while work remains pending. A changed origin backing or managed origin replacement also stops the sweep.

Native additional blocks use public `Player.breakBlock`, retaining native tool effectiveness, drops, experience, damage events and protection. Custom additional blocks use the existing protected removal transaction and mining profile. Correct-tool reward restrictions, creative mode and cancelled durability events remain binding. A callback changing the source during an ordinary native break can finish that current native operation; remaining cells stop. Owned wear writers check the retained hotbar slot and complete stack after damage/break callbacks; equal items in another slot do not become the source. The native source expectation uses the final observed damage event and exact resulting stack comparison, without adopting unrelated inventory writes.

Outside creative mode, native extra-cell admission requires the current source to pass public `Block.isPreferredTool`. Custom additional blocks retain their own mining profile's tool whitelist/blacklist and tier policy.

Doors, beds and supported native paired `Bisected` plants receive an additional protection preflight for the matching other half before the engine break. Both parts must remain resident, unowned by managed/external content and unchanged after callbacks; the player, source slot and complete tool are checked again. A denied companion stops that extra break. Supported plant families include tagged flowers with paired halves, tall grass, large fern, tall seagrass, small dripleaf and pitcher crop. The native engine performs the actual break and its paired removal; BlocksAdder does not manually delete the other half or fabricate a second reward. This preflight does not claim support for every multi-block structure or third-party protection combination.

Only immediate `block_break` actions accept `world_sweep`. Positive action delays and other triggers are validation errors. Committed scheduling supplies deferred execution; action order, permission and flow fields retain their normal admission meaning. Conditional replacements use the separate [world-change action](world-changes.md).

`WorldSweeps` owns scheduling and source expectations, `SweepRegion` validates the schema, and `MiningService.breakExtra` connects custom mining policy to the root's `ItemWorldAccess.extraBreak` bridge. The root excludes external ownership before choosing native or custom execution. The supplied reference JAR is never loaded or executed.

The historical [verification report](area-mining-verification.json) retains its exact candidate and 75 scoped assertions per recorded endpoint for the earlier area-mining implementation. It does not establish the new surface, column, preservation or reward behavior. This guide describes their implementation contract; new native results are recorded separately after execution. Native input, public API cases, pure geometry/configuration checks and repeated regressions remain distinct evidence categories. These scopes do not establish every original multiple-break option, client appearance or all third-party protection combinations. Visual checks belong to the user.

## Preview 17 verification

The [exact preview-17 report](area-surface-verification.json) records 2,049 scoped assertions in 20 accepted server processes for JAR `79e1c0fba95625952fe0d1102ee19ebcb628c6dd6ab667645572db41b8ea6092`. Nine builds passed the extended suite, two endpoints repeated the earlier 75 area assertions with a client-channel readiness gate, and nine passed core/bundle checks. Actual six-face protocol input, timed mining, abort and slot changes are distinguished from public API/event, lifecycle and data cases. The packaged geometry helper separately passed 1,196,821 checks; proxy reward/storage checks are excluded from the server total.

Development failures are retained outside the product and excluded from acceptance. They exposed native redstone activation/decay, invalid fixture ray/item assumptions, a lost initial test message before channel registration, native container spills and public API differences between Spigot and Paper. Foreign item teleport is exercised at the earliest valid public boundary: native drop callbacks where the entity is valid, otherwise the owned relocation preflight after insertion. Full marked quantity and final foreign positions remain asserted. Container game-rule lookup uses the existing bounded public API adapter across renamed rules. User appearance checks and broader parity requirements remain open.
