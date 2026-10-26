# Plant visibility and distance detail

Development 1.1.0-SNAPSHOT adds a personal view policy for loaded custom crops. It uses public Bukkit entity visibility and display billboards; ProtocolLib is not required. The setting affects crop display and interaction helpers, never their saved stage or natural growth.

Use `/ba plants auto`, `/ba plants full`, `/ba plants hide` or `/ba plants status`. The command requires `blocksadder.plants`, granted by default. An optional online player name requires `blocksadder.others` when it names someone else. The preference is saved in the player's persistent data and survives content reload and reconnect.

| Mode | Behavior |
| --- | --- |
| `auto` | Select the nearest plants within range. Crowded views thin distant cells, optionally restrict the field of view and use distant facing models. This is the default. |
| `full` | Select the nearest normal models within range and the per-player cap. Skip crowd thinning, field-of-view filtering and distant facing models. |
| `hide` | Hide crop displays and interaction helpers for this player. Native crop clicks and attacks cannot harvest, fertilize or remove those hidden plants. API growth and natural growth continue. |

## Server policy

These independent server settings belong in `config.yml`:

```yaml
plants:
  view:
    radius: 32
    maximum: 1400
    crowded-at: 233
    thin-after: 8
    card-after: 10
    field-of-view: 0
    work-per-tick: 2048
    millis-per-tick: 3
    refresh-ticks: 10
```

Range and model-distance checks use horizontal block-center distance. `radius` accepts 1–256 blocks; `maximum` accepts 1–4096 plants per player. Selection retains the nearest candidates, with stable registration order resolving ties. These settings do not change placement-density limits under `crops`.

Automatic crowd detail begins above `crowded-at` plants in range. Once active it stays active until the population reaches 75% of that threshold or lower. Nearby plants remain complete. Beyond `thin-after`, alternating coordinate cells are retained, including at negative coordinates. Beyond `card-after`, retained plants use a facing display. Separate far-stage items are optional; without them the normal stage item is used with the facing policy.

`field-of-view` accepts 0–360 degrees; 0 and 360 disable directional filtering. In crowded automatic views, the horizontal viewing angle filters distant plants. Plants within two blocks and viewpoints at least nine blocks above the plant bypass that filter. `full` mode does not apply this filter.

The scheduler shares both `work-per-tick` operations and `millis-per-tick` milliseconds across players using a rotating queue. It incrementally scans the resident crop index and applies visibility changes. `refresh-ticks` sets the pause after a finished selection. Work, population and movement can extend the effective refresh time; lowering the budget trades responsiveness for less per-tick work. Movement is rechecked before showing a new plant, and the next selection refreshes older choices. The service does not load chunks or scan world entity collections on each tick.

## Explicit far-stage models

Complete bundle example using vanilla inventory models as placeholders:

```yaml
format: blocksadder/bundle-1
bundle: garden
entries:
  - key: sprout
    kind: artifact
    item: {base: WHEAT_SEEDS}
  - key: grown
    kind: artifact
    item: {base: WHEAT}
  - key: sprout_card
    kind: artifact
    item: {base: CARROT}
  - key: grown_card
    kind: artifact
    item: {base: POTATO}
  - key: grain
    kind: crop
    item: {base: WHEAT_SEEDS, label: Grain}
    settings:
      stages: ['garden:sprout', 'garden:grown']
      growth-seconds: 240
      requires-farmland: true
      visuals:
        far-stages: ['garden:sprout_card', 'garden:grown_card']
        facing: VERTICAL
```

`visuals.far-stages` must have exactly one known item ID per normal growth stage. `visuals.facing` accepts `FIXED`, `VERTICAL`, `HORIZONTAL` or `CENTER`, defaulting to `VERTICAL`. Invalid stage counts, references, policies and numeric budgets reject validation before candidate activation. Supply your own suitable flat model assets for a textured distant plant; the example only demonstrates item routing.

For a `crop-series` record, optional `settings.cards` generates hidden far-stage items alongside its normal generated stages:

```yaml
cards:
  texture-prefix: garden:block/grain_card_
  parent: blocksadder:block/plant_card
  slot: plant
```

This is a settings fragment, not a complete document. Textures are indexed from zero to the series' maximum stage. Put owned PNG assets at `assets/garden/textures/block/grain_card_0.png`, and so on. The default parent is an independently generated two-sided vertical quad with the `plant` texture slot. A custom parent may use another slot. The built-in parent requires `slot: plant`; explicit pack assets may override its generated default. Texture files are supplied by the administrator and are not copied from the research archive.

Facing displays are created lazily when a viewer first needs them and shared by viewers using that variant. Unused cards are reclaimed after 100 ticks plus an incremental sweep delay. Growth, removal, chunk unload and reload discard obsolete helpers through the same directly owned entity lifecycle.

## Verification boundary

Static observations from the retained reference archive established distance/cap selection, optional directional filtering, distant cell thinning, per-player visibility and alternate facing stages as feature requirements. They were not used to translate individual decompiled renderer classes. BlocksAdder uses its own configuration, indexed main-thread scheduler, nearest heaps, hysteresis and lazy shared display lifecycle. The original plugin was never run.

The [verification report](crop-views-verification.json) records the exact candidate, actual connected protocol clients and API/data checks separately. Tests cover individual selections and commands, packet-level spawn/removal state, stage regeneration, low-budget progress, chunk unload/reload and helper cleanup. Generated geometry is checked as data. Rendered appearance belongs to the user's visual checks; this does not certify every client rendering or native trampling combination.
