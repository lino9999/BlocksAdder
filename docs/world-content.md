# World content

Development placement records now retain the clicked Cartesian block face beside yaw. Version-two records continue using the established chunk PDC key and read version-one records with an upward face. Reload retains non-default faces. The format supports furniture orientation without assigning new content IDs; downgrading to an older reader that only understands version one requires restoring a backup.

Development version 1.1.0-SNAPSHOT replaces the preview's one-click custom-block removal described below with [timed mining](mining.md). Automatic ore/decorator/native-tree population is documented in [world-generation.md](world-generation.md), and full skeletal entity/player rigs in [entity-models.md](entity-models.md). Remaining paragraphs retain the original world service schema; preview-specific limitations should be read with those newer modules.

World content uses public Bukkit APIs: a real collision block where needed, `ItemDisplay` for its model, and `Interaction` for furniture or crops. It does not reserve vanilla block-state palettes, modify Minecraft internals or depend on packet libraries. An installed resource pack supplies custom visuals. Custom blocks have full-block collision; furniture can have a full-block collision or no collision. Arbitrary model-shaped collision is not implemented.

## Blocks

Add a `block` section inside an item definition:

```yaml
block:
  material: BARRIER
  display: true
  scale: 1.0
  offset-y: 0.5
```

The collision material must be a non-air block without gravity. `BARRIER` hides the backing block; visible vanilla materials can be used with `display: false`. Item model orientation is snapped to 90 degrees when placed by a player. Players place the item by right-clicking an adjacent block and receive it back when breaking it in survival. Left-clicking indexed content removes it in one action after protection checks; this allows survival players to remove barrier-backed blocks. Adventure and spectator placement/breaking are denied. Creative breaking produces no drops. Custom mining duration and tool-tier gates are not implemented.

## Furniture and seats

```yaml
furniture:
  collision: false
  material: BARRIER
  display: true
  width: 1.0
  height: 1.0
  scale: 1.0
  offset-y: 0.5
  seat: true
  seat-height: 0.4
```

With `collision: false`, furniture occupies an otherwise air block and has an interaction hitbox. With `collision: true`, it uses the configured collision material. Right-click a seat to sit. Sneak and right-click the interaction hitbox, or attack it, to remove furniture. Dismounting removes its temporary seat entity. The interaction width/height are limited to 0.1–4 blocks and the display scale to 0.01–16. Furniture occupies one anchor block; this is not a multi-block vehicle system.

## Crops

```yaml
crop:
  collision: false
  requires-farmland: true
  stages:
    - blocksadder:ruby_sprout
    - blocksadder:ruby_plant
    - blocksadder:ruby_ripe
  growth-seconds: 240
  width: 0.8
  height: 0.8
  scale: 1.0
  offset-y: 0.4
  drops:
    - blocksadder:ruby 2
```

Each stage references an item definition supplying its model. Crops grow in loaded chunks, one stage at a time. `growth-seconds` is the delay between stages; a due stage advances after its chunk loads, without simulating every missed offline stage. Even a one-stage crop must wait once before yielding, preventing immediate repeat harvesting. Right-click a mature crop to harvest and reset its growth. Breaking it returns its planting item and, when mature, configured produce. Its supporting farmland cannot be broken, trampled, dried or moved while the crop exists. Harvest or remove the crop first. Bone meal, biome/light requirements and automatic world population are not implemented.

Drop entries use `item_identifier amount`, with an amount from 1 to 64. Vanilla materials such as `WHEAT 2` and namespaced custom item IDs are accepted.

## Custom living entities

The `entities` content section supports public Bukkit living entity types:

```yaml
entities:
  ruby_guardian:
    type: ZOMBIE
    display-name: '&cRuby Guardian'
    name-visible: true
    despawn: false
    ai: true
    silent: false
    glowing: false
    attributes:
      max_health: 40
      movement_speed: 0.2
    equipment:
      HEAD: blocksadder:ruby
      HAND: IRON_SWORD
    replace-drops: true
    drops:
      - blocksadder:ruby 3
    experience: 10
```

Attribute names resolve through Bukkit's `minecraft` attribute registry, and equipment names support `HAND`, `OFF_HAND`, `HEAD`, `CHEST`, `LEGS` and `FEET`. Entities retain a `blocksadder:entity_id` persistent tag. The selected vanilla entity supplies AI and animation. Multipart animated mob models, player disguises and packet-based emotes are outside this implementation. `replace-drops: false` keeps vanilla drops before configured drops are appended.

## Trees

```yaml
trees:
  ruby_tree:
    trunk: OAK_LOG
    leaves: blocksadder:ruby_block
    height: 5
    radius: 2
```

Trunk and leaves accept vanilla block materials or custom block item IDs. Trees have a configurable trunk and a compact three-layer crown. Height is limited to 2–16 and radius to 1–4. Generation requires empty space, fires `StructureGrowEvent`, and rolls back its changes when a custom block placement fails. Existing terrain is never replaced. This API generates a tree at a requested position; it does not populate newly generated chunks automatically.

## Persistence, protections and limits

The service stores a versioned placement index in each chunk's persistent data container (`blocksadder:placed_content_v1`). It writes changes immediately and before chunk unload. Only loaded chunk placements are held in memory. Display, hitbox and seat helpers are nonpersistent; they are removed during unload/disable and recreated from the chunk index when loading. Unknown item definitions retain their index, allowing a temporarily missing content pack to be restored. If another plugin changes a backing block, the stale record is removed without dropping a custom item.

Placement by a player fires a synthetic Bukkit `BlockPlaceEvent` and the cancellable `CustomContentPlaceEvent`. Breaking and harvesting respect Bukkit `BlockBreakEvent` and the cancellable `CustomContentBreakEvent`; integrations can suppress its drops. The custom events expose the content ID, kind, cloned location and player (nullable for server/API operations). Explosions, liquids, pistons and entity block changes preserve indexed content and crop supports. Integrations that change blocks directly should remove custom content through normal protected interaction first.

```yaml
world:
  enabled: true
  max-content-per-chunk: 128
  max-loaded-content: 100000
  max-entities-per-chunk: 32
  display-view-range: 1.0
  growth-check-ticks: 100
  growth-batch-size: 128
```

New placements honor limits; loading existing saved content does not delete records that exceed a newly lowered limit. The growth queue is indexed and processes a bounded batch each scheduled run. `blocksadder.place` and `blocksadder.break` control normal player building and should default to true in the plugin descriptor. Administrator commands have their own command permissions.

API operations must run on the server thread. `WorldContentService.count()` reports loaded placements, not the total in unloaded worlds. `place(location, id, player)` does not consume inventory items; the player interaction listener handles consumption. `spawnEntity(location, id)` and `spawnTree(location, id)` are server/API operations and require permission checks in the calling command or integration.

`WorldContentService.validate(candidate)` validates a candidate registry before reload, including collision materials, item references, crop stage/drop definitions, display bounds, entity type/attribute/equipment names and tree definitions. The static `WorldContentValidator.validate(candidate)` entry point is available before constructing a world service. Attribute availability on a particular living entity is checked again at spawn time; a failed entity setup removes the partially created entity.
