# Actions on placed content

Development content may define ordered `world.use`, `world.remove` and `world.harvest` plans in the item-bearing record's `traits.events`. These are independent BlocksAdder trigger names. They operate on the placed block, furniture or crop definition, separately from the item held by the player.

```yaml
format: blocksadder/bundle-1
bundle: workshop
entries:
  - key: visits
    kind: meter
    settings: {min: 0, max: 100000, default: 0}
  - key: signal_block
    kind: block
    item: {base: PAPER, label: Signal Block}
    settings: {material: BARRIER, display: false, hardness: 0.5}
    traits:
      events:
        world:
          use:
            increment_player_stat:
              name: workshop:visits
              amount: 1
            play_sound:
              name: minecraft:block.note_block.chime
              volume: 0.6
              pitch: 1.2
          remove:
            increment_player_stat:
              name: workshop:visits
              amount: 5
```

| Trigger | Preparation point |
| --- | --- |
| `world.use` | Main-hand right-click on a managed block or crop/furniture helper, or a protected furniture model/API use callback. Runs before harvesting, fertilizing, seating or built-in furniture behavior. |
| `world.remove` | Player-initiated managed removal after native/custom protection and supported-crop preparation, before the backing block or placement changes. |
| `world.harvest` | Protected crop harvest before its stage/reward update. This is separate from removing the crop. |

Bare-handed players can activate placed plans. Held-item `block_break` plans continue to execute independently for custom blocks. Physical plans do not charge tool wear or consume the representation of the placed item. Server-initiated removal with a null player does not invent a player action context. Natural crop destruction and custom-block explosions use separate [environmental removal rules](environment-removal.md), including actor-free committed effects and prevention.

## Protection and identity

Use preparation requires `blocksadder.use`, permits no spectators, honors the definition's interaction permission suffix and emits cancellable `CustomContentInteractEvent`. The event exposes the cloned anchor location, stable content ID, content kind, player, hand and optional clicked helper entity. Cancel it to stop the placed use plan and built-in operation. Native interaction denial and hidden-crop policy are checked before preparation. Reentrant use of the same placement during this preparation is rejected, with a bounded nesting limit across placements.

`cancel` inside a placed plan cancels the corresponding preparation event. Removal and harvest also honor `CustomContentBreakEvent` and native protection. After callbacks, the root verifies the same live placement instance. Replacing a block with another copy of the same ID cannot let the outer operation remove, harvest or mount that replacement. Remaining steps of a placed plan stop when its source placement changes.

These plans are preparation effects, not committed-operation notifications. Arbitrary commands, sounds or player-stat writes already performed by a plan cannot be rolled back if a later failure stops the operation. Keep operations that require exact committed outcomes in an integration with appropriate lifecycle handling.

## Actions, cooldowns and delays

The existing ordered action compiler supplies stat changes, player effects, sounds, particles, commands, grants and cancellation, including permission/flow rules and global/per-trigger cooldowns. World coordinates resolve to the placement anchor. Target-living-entity actions need a living target; a display-backed placement does not create one.

The source is a read-only representation of the placed definition. Inventory amount, durability, usage and model-property mutations are rejected in `world.*` plans during candidate validation. They cannot accidentally edit the player's hand or grant the virtual source. Conditional [`world_change`](world-changes.md) operations now provide protected block/furnishing replacements, including self-change and recreation from a retired empty removal anchor. They retain a separate physical ownership authority during their own temporary backing write. Multiple-block operation actions remain separate work.

Delayed use/harvest actions require the same resident placement. A delayed removal action may survive its own committed removal while the resident anchor is empty. A new managed placement invalidates that older removed-source action, even if that replacement is removed again before the delay ends. Weakly keyed revision markers retain this distinction without an indefinitely growing coordinate history. An unloaded anchor, player disconnect, changed step permission or content reload cancels the applicable job through the existing bounded scheduler.

If a placed plan replaces the held tool, committed custom wear only applies when the tool still matches the snapshot captured before that physical plan. The replacement tool receives no unrelated wear. Held-item delayed snapshots continue to follow only the plugin's owned wear update.

## Evidence and remaining work

The [world-plan verification](world-plans-verification.json) records exact artifacts and separate native/API cases. Actual protocol use packets exercise block use, furniture use/seating and crop harvesting. Additional cases cover permissions, cancellation, same-ID callbacks, reentrant use, delayed source identity, replacement-tool wear and validation. The prior tool-wear regression is retained as a failed development candidate, not counted as successful verification.

These are independently implemented domain events based on observed feature requirements. External trigger/configuration names and original binary API compatibility are not provided. The [held interaction bridge](held-world-interactions.md) coordinates native hand/helper/model inputs. Environmental removal and conditional world changes have their own rules and verification scopes. Wider integration acceptance, remaining environmental/world-change combinations, multiple-break actions, providers and projectiles remain in the development tracker. Rendered appearance is checked by the user.
