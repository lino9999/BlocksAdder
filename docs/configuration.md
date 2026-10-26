# BlocksAdder bundle configuration

Server settings live in `plugins/BlocksAdder/config.yml`. Content uses the independently designed `blocksadder/bundle-1` format: a bundle identifier and a list of typed definition records. Flat namespace/item documents and configuration files from other plugins are rejected before a registry is published.

```yaml
format: blocksadder/bundle-1
bundle: garden
entries:
  - key: sapphire
    kind: artifact
    item:
      base: AMETHYST_SHARD
      label: '&bSapphire'
      details: ['&7A bright crafting ingredient.']
      group: materials
      visual: garden:sapphire
      sprite: garden:item/sapphire
      variant: 10001
      stack: 64
    traits:
      unbreakable: false
      allow-vanilla-recipes: false
  - key: sapphire_block
    kind: block
    item:
      base: PAPER
      label: '&bSapphire Block'
      sprite: minecraft:block/amethyst_block
    settings:
      material: BARRIER
      texture: minecraft:block/amethyst_block
      display: true
  - key: sapphire_block
    kind: recipe
    settings:
      type: shaped
      result: garden:sapphire_block
      shape: ['SS', 'SS']
      ingredients:
        S: garden:sapphire
```

The stable ID is `<bundle>:<key>`. Different definition families may share a key; two item-bearing records cannot. Bundle names accept lowercase letters, digits, underscores, dots and hyphens. Keys accept lowercase letters, digits, underscores, dots, slashes and hyphens. Keep saved content IDs stable.

Set `active: false` on a record or the whole bundle to disable it. Unknown document, record and presentation fields, repeated definitions, invalid identifiers and unsupported format versions reject the candidate. `/ba validate` checks a candidate; `/ba reload` validates before publishing it.

The `item` presentation fields are `base`, `label`, `details`, `group`, `visual`, `sprite`, `parent`, `variant` and `stack`. `base` is required. Other inventory-item properties belong in `traits`, including durability, enchantments, attributes, equipment, food and actions. Do not duplicate presentation fields inside traits. Typed world behavior belongs in `settings`.

| Kind | Purpose | Body |
| --- | --- | --- |
| `artifact` | Inventory item | `item`, optional `traits` |
| `block`, `furnishing`, `crop` | Item with block, furniture or crop behavior | `item`, optional `traits`, world `settings` |
| `recipe`, `catalog` | Recipe or browser category | `settings` |
| `glyph`, `meter`, `overlay` | Font image, player statistic or HUD | `settings` |
| `drop-rule`, `block-reward` | Loot or conditional block/crop rewards | `settings` |
| `motion`, `creature`, `rig`, `vehicle` | Emote, entity, skeletal model or controlled mount | `settings` |
| `tree`, `ore-field`, `surface-cover`, `tree-field` | Manual tree or automatic generation | `settings` |
| `fluid`, `flame`, `audio` | Custom liquid, fire or sound | `settings` |
| `crop-series`, `armor-set`, `armor-effect` | Generated crop stages or equipment definitions | `settings` |

This table describes routing, not acceptance of every rendering mode or option. Read the [development tracker](parity-progress.md) and each module's evidence. Subsystem guides describe settings/traits bodies. Their earlier flat namespace/type examples are historical excerpts, not complete bundle documents. Wrap the relevant body in a record. The packaged [examples](../src/main/resources/contents/blocksadder/configs/examples.yml) are executable complete current-format documents. External plugin YAML is unsupported.

## Resource files

```text
plugins/BlocksAdder/
  config.yml
  contents/
    garden/
      definitions.yml
      resourcepack/
        assets/
          garden/
            textures/item/sapphire.png
            models/item/chair.json
            sounds/chime.ogg
  resourcepack/
    assets/
  pack/
    BlocksAdder.zip
```

YAML files beneath `contents/` load in sorted path order. Only resource-pack assets enter the ZIP; YAML and analysis files are excluded. Supply assets you own or may distribute. Top-level `resourcepack/` has highest priority. Symlinked files and escaped paths are rejected. Explicit resource JSON overrides generated defaults.

`visual` selects a modern item model and defaults to the item's ID. `sprite` is a namespaced texture path without `.png`. `parent` selects generated/handheld geometry. Identity is stored separately from names and numeric model data.

## Pack delivery

```yaml
resource-pack:
  build-on-startup: true
  auto-apply: false
  required: false
  url: ''
  max-uncompressed-mb: 128
  host:
    enabled: false
    bind: '0.0.0.0'
    port: 8163
```

`/ba pack build` creates `pack/BlocksAdder.zip`, orders its entries deterministically and calculates SHA-1/SHA-256 from the actual bytes. A failed asynchronous build retains the previous published artifact. Upload that exact ZIP for external hosting and configure a direct HTTP(S) URL without credentials or a fragment.

Built-in hosting exposes only `/BlocksAdder.zip` through GET/HEAD with capped workers. Configure its public URL and a separate available port. It does not configure DNS, forwarding, firewalls or TLS. `auto-apply` requests the pack after joining; `required` handles decline/failure for this plugin's pack UUID.

## Editing and upgrades

Configuration commands preview changes to items, bundles and recipes. They edit current-format records, preserve unrelated definitions, reject stale previews and restore exact previous file bytes after activation failure where recovery succeeds. Keep backups. Saved placements retain missing IDs so content can be restored later; changed backing blocks are treated as stale without duplicate drops.

The historical 1.0.0 preview used another BlocksAdder format. Upgrade content deliberately; the development loader does not silently reinterpret old or third-party documents. API calls that touch worlds, players or registries must run on the server thread.

Pack metadata declares formats 75 through 97, including 97.1. This follows [Mojang's format-range specification](https://www.minecraft.net/en-us/article/minecraft-snapshot-25w31a) and [26.3 format 97.1](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3). These declarations do not certify arbitrary supplied assets. The user performs appearance checks; server/generated-data verification is recorded separately.
