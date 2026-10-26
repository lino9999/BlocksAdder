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
            frame: facing
            permission: quarry.excavate
            select: [minecraft:stone, minecraft:deepslate, quarry:ore]
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

Both `region` vectors contain exactly three integers from -8 to 8. The inclusive box must contain its origin and have 2–343 cells, including the origin. The origin is never broken a second time. The default `world` frame uses world X/Y/Z offsets. In the optional `facing` frame, +X means the player's right, +Y means up and +Z means forward, snapped to the nearest horizontal cardinal direction when queued. Pitch does not rotate the region.

`select` is optional. An omitted or empty list admits eligible blocks without a material filter. Up to 64 native block-data patterns or enabled custom block IDs are accepted. Native patterns use partial state matching and exclude managed content, even when its backing material matches. Custom IDs match the retained placement instance. Furnishings, crops, liquids, air, native negative-hardness blocks and externally owned fluid/fire cells are excluded. A denied cell is skipped; later eligible cells can still be attempted.

The sweep snapshots resident candidate states and managed placement identity. It never loads or generates a missing chunk. Changed backing states, removed/recreated managed candidates and unloaded candidate chunks are skipped. Work shares a global budget of 32 attempted cells per tick; unfinished players rotate through the queue. Attempts include cells skipped after a later state change, so the budget also bounds validation work.

The original hotbar slot and complete source stack remain authoritative. Confirmed durability events and committed custom wear can update the retained expectation; arbitrary metadata/amount changes or replacements cannot. Exhaustion, death, disconnect, teleport, world/mode/hand/slot changes, reload, shutdown, permission loss or leaving 32 blocks of the origin stops remaining work. The item-level `all_actions_permission`, action `permission` and `blocksadder.break` are checked while work remains pending. A changed origin backing or managed origin replacement also stops the sweep.

Native additional blocks use public `Player.breakBlock`, retaining native tool effectiveness, drops, experience, damage events and protection. Custom additional blocks use the existing protected removal transaction and mining profile. Correct-tool reward restrictions, creative mode and cancelled durability events remain binding. A callback changing the source during an ordinary native break can finish that current native operation; remaining cells stop. Owned wear writers check the retained hotbar slot and complete stack after damage/break callbacks; equal items in another slot do not become the source. The native source expectation uses the final observed damage event and exact resulting stack comparison, without adopting unrelated inventory writes.

Only immediate `block_break` actions accept `world_sweep`. Positive action delays and other triggers are validation errors. Committed scheduling supplies deferred execution; action order, permission and flow fields retain their normal admission meaning. Conditional replacements use the separate [world-change action](world-changes.md).

`WorldSweeps` owns scheduling and source expectations, `SweepRegion` validates the schema, and `MiningService.breakExtra` connects custom mining policy to the root's `ItemWorldAccess.extraBreak` bridge. The root excludes external ownership before choosing native or custom execution. The supplied reference JAR is never loaded or executed.

The [verification report](area-mining-verification.json) records the exact candidate and endpoint coverage. Native input, public API cases, generated data and repeated regression assertions are distinct evidence categories. These checks do not establish every original multiple-break configuration, client appearance, vertical view rotation or all third-party protection combinations. Visual checks belong to the user.
