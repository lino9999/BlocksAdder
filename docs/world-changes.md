# Conditional world changes

The independent `world_change` action changes one matching block or furnishing at the event anchor. It works in ordered held-item plans and in the read-only physical use/removal plans. It does not consume the source item, apply mining hardness or charge tool wear. Crop definitions are not replacement choices in this checkpoint.

```yaml
format: blocksadder/bundle-1
bundle: workshop
entries:
  - key: polished_marker
    kind: block
    item: {base: PAPER, label: Polished Marker}
    settings: {material: STONE, display: true}
  - key: converter
    kind: artifact
    item: {base: STICK, label: Marker Converter}
    traits:
      events:
        interact:
          right:
            world_change:
              match: 'minecraft:stone'
              into: 'workshop:polished_marker'
  - key: rough_marker
    kind: block
    item: {base: PAPER, label: Rough Marker}
    settings: {material: BARRIER, display: true}
    traits:
      events:
        world:
          use:
            world_change:
              match: 'workshop:rough_marker'
              into: 'workshop:polished_marker'
```

## Matching and destination

`match` and `into` are required. Both resolve an enabled custom block/furnishing definition or a native block-data string. Short custom IDs prefer the source bundle, then the registry's unambiguous short-ID lookup. Explicit namespaced IDs avoid ambiguity. Unknown definitions, unsupported record kinds, malformed states and unknown fields reject the candidate.

A native material selector such as `minecraft:oak_log` matches all its orientations. An explicitly specified state such as `minecraft:oak_log[axis=x]` additionally matches that property. This uses the public API's partial [BlockData matching contract](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/block/data/BlockData.html#matches(org.bukkit.block.data.BlockData)). A native selector excludes managed custom blocks/furniture and indexed custom liquids/fire, even when they share the same physical material. Custom selectors match the stable content ID and family.

Blocks can change from native to custom, custom to native, custom to custom or native to native. `minecraft:air` is an explicit empty destination. A furnishing changes to another furnishing through the existing footprint/render transaction; crossing between furnishing and block families is rejected. Custom block replacements preserve face/yaw. Native destination states use the parsed state values. The operation changes one cell; it does not automatically construct a native bed/door's other part or update surrounding blocks with physics.

A same-ID custom replacement creates a new placement instance. Earlier physical-source delays cannot transfer to it. After a physical plan replaces its own source, the plan's remaining source-dependent steps stop. A held-item plan keeps its separate inventory source and ordinary action-flow rules.

The action does not automatically cancel the original native input. Add the ordinary `cancel` action when a source material's vanilla use or placement should be suppressed. The world change itself does not consume the held item; uncancelled native behavior retains its own costs. Cancellation later in a plan does not undo an already committed change.

## Permission and preparation

Player block changes require `blocksadder.break`, and a non-air destination also requires `blocksadder.place`. Existing source break/destination place permission suffixes apply; adventure-mode block changes are rejected. Furnishing state changes retain the existing interaction contract: `blocksadder.use` and the source's interaction suffix authorize the transition, including adventure-mode use. New furnishing footprint cells still pass native placement protection. Spectators, disconnected players and actors in another world are rejected. New custom placements retain chunk/global content limits. A changed solid cell cannot intersect a living entity. Server API calls with a null player omit player authorization while retaining cancellable custom events.

`CustomContentReplaceEvent` exposes cloned anchor coordinates and previous/next identifiers. For a native block, the identifier is its block-data string. Native break protection is checked before mutation, without held break plans or mining rewards. A custom destination also emits `CustomContentPlaceEvent`. Non-air player destinations run native `BlockPlaceEvent` protection against the actual temporary candidate and captured previous state. Air removal has no invented native placement event. The synthetic placement event represents the requested result rather than charging a held placement stack.

The same-anchor replacement cannot reenter, and preparation nesting is bounded to 32 anchors. Callbacks cannot transfer an outer custom operation to a replacement with the same ID. Held source snapshots are rechecked after preparation callbacks; physical sources use a separate resident ownership authority so their own temporary backing write is not mistaken for an unrelated item swap. Content reload is rejected while a replacement is committing.

Changing a support's backing prepares its managed crop for protected support removal. Cancellation preserves both. A later placement failure closes that preparation without removing or locking the crop. A committed support change removes the prepared crop without mined rewards. This is separate from a normal crop harvest.

## Commit, rollback and native payloads

The editor retains a complete public [BlockState snapshot](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/block/BlockState.html) before its candidate write. Rejected placement restores that state, including the ordinary container inventory and tile PDC exercised in verification. A failure while publishing helpers/native state registration restores the former placement and helpers. An external callback that writes a different backing state is preserved, and the old managed identity is retired rather than attached to that foreign block.

During a block transaction, owned logical identity remains available while its backing is temporarily different. The editor checks the raw candidate state before publishing. It uses a short-lived plugin ticket for the already resident chunk, releasing only the ticket it added. It does not load new chunks or keep ordinary content permanently active.

Changing a native storage block's type retains its stored items separately from mined block loot. Container, lectern, bookshelf, jukebox, campfire and brushable snapshots supply their stored item payloads. Native item/XP emission during the temporary write or rollback is intercepted only within that write's source cell and the cell immediately above it. A failed change discards that temporary emission and restores the snapshot. A successful change releases its retained payload after publication through ordinary item/entity spawn events. It does not award the old block item as mining loot.

Furnace recipe counts supply stored smelting XP through the public recipe API, including ordinary fractional XP rounding. It is emitted only after a successful type change; cancellation leaves the original recipe counts intact. Furnace payload preparation accepts at most 1,024 used recipe entries and 1,000,000 stored XP. Other temporary payload escrow is bounded; exceeding it aborts the operation. These bounds keep one configured action from producing unbounded work.

Payload release is excluded from any enclosing temporary-write escrow. A nested committed change therefore cannot have its items swallowed by the outer change. Individual release failures are logged after commit; already released items and arbitrary other-plugin effects cannot be rolled back. Native spawn protection may still reject a committed payload entity.

An occupied entity-storage block, such as a beehive, keeps its inhabitants during same-material state changes. Changing its material is currently rejected to preserve those entities. Cross-material entity-store migration, every other tile payload, double-container topology and arbitrary callback shutdown combinations remain separate acceptance work. This is not full native tile-data migration certification.

## Delays and API

The ordinary action `permission`, `delay` and `flow` fields apply. Delays are independent scheduled steps. A held delay retains the held source and applies `match` to the resident target at execution. A physical delay also retains exact placement/retirement guards. A committed `world.remove` delay can match its still-empty retired anchor and create a new block; an intervening managed placement invalidates that old source even if removed again.

`BlocksAdderApi.changeContent(location, match, replacement, player)` exposes the same conditional operation on the server thread. It returns whether the change committed. Invalid choices raise `IllegalArgumentException`; cancellation, a mismatch or unavailable source returns false. The internal `WorldChoice`, `ItemWorldAccess` predicates and transaction lifecycle belong to service composition. Integrations should use the public method and events.

## Evidence boundary

The retained static string inventory identifies the reference feature families for replacing blocks/furniture and multiple-block breaking. BlocksAdder implements its own `world_change` compiler, choice type, ownership authority and guarded editor; no decompiled implementation class or external configuration alias is translated. Multiple-block breaking remains separate work in the parity tracker.

Verification distinguishes actual native main/offhand/furnishing inputs from public API, preparation, failure-injection and tile-state cases. The user owns rendered appearance checks. Exact candidate and endpoint results are recorded with the development verification evidence; a green scoped suite does not establish full functional parity.
