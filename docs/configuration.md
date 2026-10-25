# Configuration and resource packs

BlocksAdder stores settings in `plugins/BlocksAdder/config.yml`. Content YAML files are loaded recursively from `contents/` in sorted path order. Every definition file needs a lowercase `namespace`; an item key `sapphire` inside namespace `my_pack` becomes `my_pack:sapphire`. Full identifiers are stable and should remain unchanged after placing content in worlds. Ambiguous short identifiers are rejected.

## File layout

```text
plugins/BlocksAdder/
  config.yml
  contents/
    my_pack/
      configs/
        items.yml
        gameplay.yml
      resourcepack/
        assets/
          my_pack/
            textures/item/sapphire.png
            models/item/chair.json
            sounds/chime.ogg
  resourcepack/
    assets/
  pack/
    BlocksAdder.zip
```

The optional top-level `resourcepack/` directory has the highest asset priority. Within content packs, sorted file paths determine asset overwrite priority. Explicit asset files override generated defaults. Conflicting generated definitions are rejected. Do not put private files in an asset tree; only supported resource-pack file types inside `assets/`, plus `pack.png`, are included. Symlinked files are excluded and escaped paths are rejected. YAML files are never packed.

## Item schema

```yaml
namespace: my_pack
items:
  sapphire:
    enabled: true
    material: AMETHYST_SHARD
    display-name: '&bSapphire'
    lore: ['&7A rare ingredient.']
    category: materials
    model: my_pack:sapphire
    texture: my_pack:item/sapphire
    model-parent: minecraft:item/generated
    model-data: 10001
    max-stack-size: 64
    unbreakable: false
    glow: false
    flags: [HIDE_ATTRIBUTES]
    enchantments:
      minecraft:fortune: 1
    allow-vanilla-recipes: false
```

`material` must be an item material supported by the running server. `model` defaults to the item's full ID; this is written into the modern item-model component and resolves through `assets/<namespace>/items/<value>.json`. Default generated model geometry lives at `assets/<namespace>/models/item/<value>.json`. `texture` is a namespaced path without `.png`; `model-parent` may be `minecraft:item/handheld` for tools. Supply a model JSON at the generated path to use your own geometry.

`model-data` is an optional float-compatible integer from 0–16,777,216; zero omits it. Identity is stored separately as persistent data and never inferred from a name, texture or numeric model ID. Therefore changing model data does not change the identity used by recipes or placed blocks. No automatic numeric ID allocation/cache cleanup is required.

Stack limits range from 1–99. `durability` sets a positive custom maximum damage on a damageable item. Leather armor accepts `color: '#55CCFF'`. `attributes` is a map of Minecraft attribute names to additive main-hand amounts. `equipment-model` is a namespaced equipment asset and `equipment-slot` is a Bukkit slot such as `CHEST`. Additional equipment assets must be supplied in your resource pack. Food uses a `food` section with `nutrition`, `saturation` and `always-eat`.

An item may have one world-content section: `block`, `furniture` or `crop`. See [world-content.md](world-content.md). Top-level supported sections are `recipes`, `categories`, `font_images`, `stats`, `huds`, `loot`, `emotes`, `entities`, `trees` and `sounds`. The loader retains a `liquids` definition section for future schema work, but no fluid simulation consumes it in this release.

## Resource-pack settings

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

`/ba pack build` creates `pack/BlocksAdder.zip` and calculates SHA-1 and SHA-256 from the actual ZIP bytes. File order, JSON object keys and ZIP timestamps are stable for the same inputs, including between JVM starts. The generated `pack.mcmeta` declares resource-pack formats 75 through 97, covering the target 1.21.11–26.3 range. Declaring a format range does not validate arbitrary administrator-supplied assets on every client version.

External hosting: upload this exact ZIP to a direct HTTP(S) download URL, set `url`, then `/ba reload`. Re-upload after every asset change before sending the new hash. The URL must not contain credentials or a fragment. Sending appends the current hash as a cache-busting query parameter.

Built-in hosting: enable `host.enabled`, choose a free TCP port, and set `url` to a public address ending in `/BlocksAdder.zip`, for example `https://assets.example.com/BlocksAdder.zip` behind a reverse proxy. The built-in server exposes only that exact file route through GET/HEAD. It serves from memory, caps workers and returns 503 until the first successful build. It does not configure DNS, router forwarding, firewalls or TLS. The port is separate from the Minecraft game port.

`auto-apply` requests the current pack shortly after joining. `required` marks the request as required and handles decline/failure for this plugin's pack UUID. No configured URL means manual requests fail with an English configuration message. Builds run asynchronously, and a failed build retains the previous successfully published in-memory artifact.

## Fonts and sounds

```yaml
namespace: my_pack
font_images:
  sapphire:
    character: "\uE001"
    texture: my_pack:font/sapphire
    height: 8
    ascent: 8
    replace: [':sapphire:']
sounds:
  chime:
    files: ['my_pack:chime']
    subtitle: Sapphire chime
```

The texture belongs at `assets/my_pack/textures/font/sapphire.png`, and the sound at `assets/my_pack/sounds/chime.ogg`. A glyph's `font` defaults to `minecraft:default`; custom font IDs generate a separate provider file and require font-aware text rendering from an integration. Default-font generation references Minecraft's standard font includes so normal text remains available. User-supplied font JSON overrides generated files and must merge any desired providers.

See [gameplay.md](gameplay.md) for permission-aware aliases, recipes, loot, stats/HUDs, right-click actions and simple emote timelines.

## Validation and reload

`/ba validate` parses settings, every content file, item prototypes, recipes, gameplay definitions and world-content definitions without replacing live content. Duplicate IDs, invalid references and unsupported values reject the candidate. `/ba reload` repeats validation, publishes the candidate and reconfigures services. Service activation failures restore the previous registry/settings and attempt to restore services; failures are logged. Fix the file and validate again. Keep a backup before large content edits.

Removing a definition does not automatically erase its saved placement identity; temporarily missing content can be restored. Backing blocks changed externally are treated as stale without creating duplicate custom drops. Never change persistent content IDs merely to rename their display text.

## Version references

Server Java requirements follow [Paper's requirements](https://docs.papermc.io/paper/getting-started/). Pack metadata uses the [modern min_format/max_format schema](https://www.minecraft.net/en-us/article/minecraft-snapshot-25w31a). The baseline client has [split block/item texture atlases](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-11), and [26.3 uses pack format 97.1](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3). Custom models must keep textures within a compatible atlas; administrator assets need interactive client acceptance.
