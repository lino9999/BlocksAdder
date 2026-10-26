# Held items and managed interactions

Held-item plans use the original native Bukkit event when interacting with BlocksAdder blocks, crops, furniture and furniture model helpers. A shared root adapter admits each operation before its built-in effects. Generic listeners defer managed targets to that adapter, so a handled event does not run its item plan twice.

## Operation order

For managed right-clicks, the root checks native interaction denial, visibility policy, use permissions and cancellable `CustomContentInteractEvent`. It then runs the held plan or block-click shortcut, verifies the same placement instance, runs the physical `world.use` plan and verifies the placement again before seating, harvesting or fertilizing. API furniture use without a native event retains physical-only preparation; it does not invent a held-item click.

Managed left-click plans run from the native block-interaction request after the protected break preflight, before timed mining can cancel that request. Managed helper attacks similarly run the held attack after permission/protection checks and before physical removal. Synthetic break preflight alone never executes held plans or tool wear.

| Native input | Held plans |
| --- | --- |
| Main-hand block click | `interact.right` / `interact.left`, then the matching `interact_mainhand` plan |
| Offhand block click | Generic interaction, then the matching `interact_offhand` plan |
| Crouched block click | Matching `_shift` variants |
| Crop/furniture helper use | `interact.entity`, with the actual hand and native event |
| Managed helper attack | `attack`, using the player's main-hand source |

Offhand managed clicks run their held plan without replaying primary-hand physical use, harvesting, seating or placement. Vanilla item frames retain their item-frame trigger behavior; a frame owned by furniture routes through the managed entity/attack path. Skeletal helper ownership resolves through the model service's cached target reference, then the root's exact placement index. No world/entity scan is required for routing.

An item-only native denial suppresses held plans and the shortcut while still permitting an allowed physical use. Block denial suppresses the managed use. A held `cancel` vetoes the built-in operation. Hidden crops reject held helper use and attacks as well as their physical operation. These protections apply before item effects; already performed arbitrary effects cannot be rolled back if a later callback cancels another stage.

## Current-format example

```yaml
format: blocksadder/bundle-1
bundle: workshop
entries:
  - key: activity
    kind: meter
    settings: {min: 0, max: 100000, default: 0}
  - key: wand
    kind: artifact
    item: {base: STICK, label: Workshop Wand}
    traits:
      events:
        interact:
          right:
            increment_player_stat: {name: 'workshop:activity', amount: 1}
          entity:
            increment_player_stat: {name: 'workshop:activity', amount: 5}
        interact_mainhand:
          right:
            play_sound: {name: 'minecraft:block.note_block.chime'}
        interact_offhand:
          right:
            play_sound: {name: 'minecraft:block.note_block.bell'}
```

This defines held-item behavior. The target may separately define [physical world plans](world-plans.md). A shared native admission token lets held and placed plans with the same content ID share one global cooldown admission while keeping their cancellation events distinct. Per-trigger cooldowns retain their existing behavior.

## Source replacement and consumption

The canonical `actions.right-click` shortcut remains a main-hand block/air convenience. It reads the live inventory source, skips stale execution after an ordered plan changes that source and does not consume an unrelated replacement installed by a callback. `cancel-block-interaction: false` permits physical block use; true prevents it. Entity use is configured through `interact.entity`.

Custom item placement runs held plans before committing the placement. A changed/consumed event source prevents stale placement. Successful placement charges only a source that still matches after placement callbacks. Bone meal uses the same comparison after growth callbacks, so a newly installed replacement item is preserved. Physical-plan tool wear retains its separate pre-callback snapshot.

Delayed held work still retains its inventory source snapshot and existing bounded job lifecycle. Delayed physical work retains placement/revision guards. These are separate source contracts: placing an identical ID elsewhere does not transfer a physical action, and replacing the held stack does not transfer an inventory action.

## Verification boundary

[Held-world verification](held-world-verification.json) records actual protocol block/hand/entity/model packets separately from API, cancellation and source-mutation cases. Its regression baseline is the earlier independently built preview, never the research artifact. Overlapping failed assertions are not separate bug counts. The same preview-6 JAR subsequently passed all 147 checks on seven 26.x builds with native protocols; [exact results](native-26x-verification.md) retain the method and boundaries. Rendered appearance belongs to the user's checks. Every named protection adapter and remaining provider/projectile/world-operation families stay in the development tracker.
