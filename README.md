<p align="center"><img src="assets/blocksadder-icon.png" width="128" alt="BlocksAdder pixel art cube icon"></p>

# BlocksAdder

Development uses the independent `blocksadder/bundle-1` typed-record content format. See [configuration](docs/configuration.md) and the [implementation/distribution audit](docs/independent-implementation.md). External plugin YAML is not accepted by the development loader.

**Custom items, blocks, furniture, crops and resource packs for Minecraft Spigot and Paper.**

BlocksAdder by **Lino** is a Java plugin for building namespaced server content with YAML. Create items, decorate worlds, register recipes, grow crops and display player stats using the Minecraft client and a server resource pack.

[Download the JAR](https://github.com/lino9999/BlocksAdder/releases) · [Configuration](docs/configuration.md) · [World content](docs/world-content.md) · [Recipes and gameplay](docs/gameplay.md) · [Developer API](docs/api.md) · [Verification](docs/verification.md)

**Preview 1.0.0 scope:** the downloadable preview is an initial independent implementation. The [feature comparison](docs/feature-parity.md) records its surface and differences from the supplied reference plugin. That preview does not include skeletal animations, disguises, vehicles, custom fluid/fire simulation or dedicated third-party adapters. Headless server checks do not certify client visuals or every player interaction.

**Active development:** `main` is now `1.1.0-SNAPSHOT`, continuing toward full functional reconstruction. Development adds timed mining, native block states, skeletal rigs/disguises, vehicles, automatic generation, fluid/fire engines, richer crops and recipe adapters. Two actual protocol-player scenarios passed 141 assertions each; endpoint and artifact scope is recorded in [development verification](docs/development-verification.md). The [parity tracker](docs/parity-progress.md) retains remaining requirements. The released preview and its report remain historical artifacts.

## Features

Development guides: [items and event actions](docs/item-behaviors.md), [mining](docs/mining.md), [block sounds](docs/block-sounds.md), [models and disguises](docs/entity-models.md), [liquids and fire](docs/fluids-fire.md), [automatic generation](docs/world-generation.md), [crop growth](docs/crop-growth.md), [equipment](docs/equipment.md), and [content administration](docs/content-administration.md).

- **Custom items:** stable namespaced identity, modern item models, float model data, names/lore, stack limits, durability, enchantments, attributes, leather colors, food and equipment models.
- **Custom blocks:** collision blocks with display models, persistent placement indexes, protected placement/removal and configurable chunk limits.
- **Furniture:** display models, interaction hitboxes, optional collision, seats and cleanup on unload or reload.
- **Crops:** configurable model stages, loaded-chunk growth, farmland requirements, repeat harvesting, custom produce and [personal distance/detail settings](docs/crop-views.md).
- **Recipes:** shaped, shapeless, furnace, blasting, smoking, campfire, stonecutting, smithing and anvil recipes with exact custom ingredients.
- **Player content:** persistent bounded stats, regeneration, bossbar/actionbar HUDs, item actions, cooldowns, potion effects and simple gesture/particle/sound timelines.
- **Font images and audio:** bitmap font providers, permission-aware chat/sign/book aliases, custom sound definitions and sound/totem commands.
- **Loot and entities:** block, entity and fishing tables; configured vanilla living entities with custom stats, equipment and drops; manually generated custom trees.
- **Resource packs:** sorted ZIP output, stable hashes, modern item definitions, model/font/sound generation, asset merging, size limits, manual delivery and optional built-in HTTP hosting.
- **Administration and integrations:** paginated catalog, recipe previews, item management, candidate validation, reload rollback, Bukkit service API and cancellable content events.
- **Placed-content actions:** independent [use, removal and harvest plans](docs/world-plans.md), with permission/cancellation checks and retained source identity for delayed work.
- **Held interactions:** [shared native routing](docs/held-world-interactions.md) for both hands, managed helpers and furniture models, with protected cancellation and callback-safe consumption.

BlocksAdder's core has no mandatory plugin dependencies. Advanced packet features and raw tile-NBT loot conditions use optional ProtocolLib through its public API. Install a compatible official build for your server; modern Paper and Spigot have separate artifacts. See [ProtocolLib's installation/API guidance](https://github.com/dmulloy2/ProtocolLib/blob/master/Readme.md). BlocksAdder does not download executable tools, contact activation servers or send telemetry.

## Requirements and installation

The target server range is **Minecraft Java 1.21.11 through 26.3**, on Spigot or Paper. Use **Java 21 for 1.21.11** and **Java 25 for 26.x**, following your server's requirements. See the exact tested builds and test boundaries in [verification](docs/verification.md). Folia and Bedrock resource packs are not supported.

1. Download `BlocksAdder-1.1.0-SNAPSHOT.jar` from the [current development preview](https://github.com/lino9999/BlocksAdder/releases/tag/v1.1.0-dev.5). It uses the bundle format documented here; full functional reconstruction remains in progress.
2. Place it in the server's `plugins/` directory and restart the server.
3. Review `plugins/BlocksAdder/config.yml` and the generated examples in `contents/blocksadder/configs/examples.yml`.
4. Run `/ba validate`, then `/ba pack build` after adding assets.
5. Host `plugins/BlocksAdder/pack/BlocksAdder.zip`, set `resource-pack.url`, and run `/ba reload`.
6. Run `/ba pack send` and `/ba browse` in game. Administrators can use `/ba get blocksadder:crystal_block` to try the examples.

The examples refer to vanilla textures, so they do not redistribute Minecraft assets. Custom visuals require the generated pack. Pack building runs on a dedicated worker; world, player and content mutations run on the server thread.

## Commands and permissions

The main command is `/blocksadder`; aliases are `/ba` and `/badd`. Angle brackets mark required arguments and square brackets mark optional arguments. Item IDs may use a short form only when unambiguous.

| Command | Permission | Purpose |
| --- | --- | --- |
| `/ba help` | `blocksadder.help` | Show command usage. |
| `/ba info` | `blocksadder.info` | Show version, author, loaded item/placement counts and server version. |
| `/ba browse [category] [player]` | `blocksadder.browse` | Open the paginated item catalog. |
| `/ba recipe <item> [player]` | `blocksadder.recipe` | Preview the first configured recipe for an item. |
| `/ba glyph [id]` | `blocksadder.glyph` | List font image IDs or print a glyph. |
| `/ba get <item> [amount]` | `blocksadder.get` | Receive a custom item. |
| `/ba give <player> <item> [amount] [silent]` | `blocksadder.give` | Give items to an online player. |
| `/ba remove <player> <item> [amount] [silent]` | `blocksadder.remove` | Remove matching custom items from a player. |
| `/ba drop <item> <player> [amount]` | `blocksadder.drop` | Drop items at a player's position. |
| `/ba drop <item> <x> <y> <z> <world> [amount]` | `blocksadder.drop` | Drop items at coordinates in a loaded chunk. |
| `/ba inspect` | `blocksadder.inspect` | Inspect the held item's identity and material. |
| `/ba modeldata` | `blocksadder.modeldata` | Inspect modern model and custom model data. |
| `/ba block` | `blocksadder.block` | Inspect custom content within eight blocks. |
| `/ba repair` | `blocksadder.repair` | Repair the held item. |
| `/ba rename <name>` | `blocksadder.rename` | Rename the held item; supports `&` colors and glyph aliases. |
| `/ba durability <remaining>` | `blocksadder.durability` | Set remaining durability. |
| `/ba color <#RRGGBB>` | `blocksadder.color` | Color held leather armor. |
| `/ba validate` | `blocksadder.validate` | Validate candidate settings/content without publishing changes. |
| `/ba reload` | `blocksadder.reload` | Validate and reload content; rejected candidates retain prior content. |
| `/ba pack build` | `blocksadder.pack` + `blocksadder.pack.build` | Generate the resource pack. |
| `/ba pack send [player\|all]` | `blocksadder.pack` | Send a pack request using the configured URL. |
| `/ba pack hash` | `blocksadder.pack` | Print generated pack SHA-1 and SHA-256. |
| `/ba stat <read\|write\|add\|subtract> <player> <stat> [value]` | `blocksadder.stat` | Inspect or update a player's bounded stat. |
| `/ba hud <show\|hide> <hud\|all> [player]` | `blocksadder.hud` | Persist HUD visibility. |
| `/ba tree <id>` | `blocksadder.tree` | Generate a configured tree at your position. |
| `/ba entity spawn <id> [player]` | `blocksadder.entity` | Spawn a configured living entity. |
| `/ba entity remove <UUID>` | `blocksadder.entity` | Remove a loaded tagged custom entity. |
| `/ba emote <id\|list\|info\|stop\|stop-now> [player]` | `blocksadder.emote` | Inspect, play or stop a skeletal emote, existing rig clip or frame-effect timeline. |
| `/ba sound <sound> <player> [volume] [pitch]` | `blocksadder.sound` | Play a namespaced sound. |
| `/ba totem <player>` | `blocksadder.totem` | Play the vanilla totem effect. |

The development branch additionally provides these commands; they are absent from the historical 1.0.0 JAR:

| Command | Permission | Purpose |
| --- | --- | --- |
| `/ba model list` | `blocksadder.model` | List skeletal models. |
| `/ba plants <auto\|full\|hide\|status> [player]` | `blocksadder.plants` | Choose personal crop visibility and distance detail; another player requires `blocksadder.others`. |
| `/ba model attach <model> <entity UUID>` | `blocksadder.model` | Attach a model to a loaded entity. |
| `/ba model play <rig UUID> <animation\|stop> [loop]` | `blocksadder.model` | Control skeletal animation. |
| `/ba model detach\|info\|bones <rig UUID>` | `blocksadder.model` | Remove or inspect a rig. |
| `/ba model bone <rig UUID> <bone>` | `blocksadder.model` | Read an animated bone location. |
| `/ba model color <rig UUID> <bone\|all> <#RRGGBB>` | `blocksadder.model` | Set bone tint. |
| `/ba model skin <rig UUID> <player\|texture\|reset>` | `blocksadder.model` | Select a player skin or restore the configured skin. |
| `/ba model glow <rig UUID> <bone\|all> <true\|false>` | `blocksadder.model` | Toggle the enchantment effect. |
| `/ba model mount <rig UUID> <bone> <player>` | `blocksadder.model` | Use a bone seat. |
| `/ba model lock <rig UUID> <bone> <true\|false>` | `blocksadder.model` | Control mount locking. |
| `/ba disguise <model> [player]` | `blocksadder.disguise` | Apply a player model. |
| `/ba undisguise [player]` | `blocksadder.undisguise` | Restore normal player appearance. |
| `/ba liquid\|fire inspect` | `blocksadder.liquid` or `blocksadder.fire` | Inspect targeted environment content. |
| `/ba liquid\|fire place <id> [x y z world]` | Matching environment permission | Place custom environment content. |
| `/ba liquid\|fire remove [x y z world]` | Matching environment permission | Remove managed environment content. |
| `/ba fire age <age> [x y z world]` | `blocksadder.fire` | Set logical custom fire age. |
| `/ba vehicle list` | `blocksadder.vehicle` | List vehicle definitions. |
| `/ba vehicle spawn <id> [x y z world]` | `blocksadder.vehicle` | Spawn a vehicle. |
| `/ba vehicle info\|remove <vehicle UUID>` | `blocksadder.vehicle` | Inspect or remove a vehicle. |
| `/ba vehicle mount <vehicle UUID> [player]` | `blocksadder.vehicle` | Mount a native vehicle seat. |
| `/ba vehicle dismount [player]` | `blocksadder.vehicle` | Dismount a managed vehicle. |
| `/ba vehicle fuel <vehicle UUID> [amount]` | `blocksadder.vehicle` | Read or set fuel. |
| `/ba config item <disable\|delete> <item>` | `blocksadder.config.item.<action>` | Preview and apply an item configuration edit. |
| `/ba config namespace <disable\|delete> <namespace>` | `blocksadder.config.namespace.<action>` | Edit namespace content and dependent recipes. |
| `/ba config recipes <disable\|delete> <item>` | `blocksadder.config.recipes.<action>` | Edit recipes that consume or create an item. |

Configuration edits require a repeated identical command within thirty seconds. The first invocation previews the affected files/content; the second applies the reviewed plan. Changed source files invalidate the preview, and failed validation restores the original files and registry.

Vehicle, disguise and undisguise commands require `blocksadder.others` when targeting another player. Ordinary vehicle riding uses `blocksadder.vehicle.ride`; placement uses `blocksadder.vehicle.place`; sneak-attack removal uses `blocksadder.vehicle.break`. Crop fertilizing uses `blocksadder.crop.fertilize`.

Shortcuts are `/baget` (`/bget`), `/bagive` (`/bgive`), `/bapack` (`/bpack`), `/babrowse` (`/bbrowse`), `/bareload` (`/breload`), `/barecipe` (`/brecipe`) and `/bainspect` (`/binspect`). They accept the same arguments as their matching subcommands. Reference-plugin commands, aliases and permission nodes are not registered.

`blocksadder.admin` grants every built-in permission and defaults to operators. Public browsing/help/info/recipe/glyph/pack/HUD/emote/plant-view commands and ordinary building/use permissions default to everyone; administrative permissions and `blocksadder.pack.build` default to operators. `blocksadder.others` is required to open menus, send packs, control HUDs, choose plant detail or trigger emotes for another player. Item quantities are limited to 1–6,400; delivery splits legal stack sizes and drops overflow at the player.

Gameplay permissions are `blocksadder.place`, `blocksadder.break`, `blocksadder.use`, `blocksadder.glyphs` and per-definition permissions. Category access uses `blocksadder.category.*` or `blocksadder.category.<category>`; an explicitly denied category overrides the wildcard. Catalog left-clicks show recipes; right-clicks grant items only with `blocksadder.get`. Shift-right-click grants one legal stack.

## Content example

Create `plugins/BlocksAdder/contents/my_pack/configs/items.yml`:

```yaml
format: blocksadder/bundle-1
bundle: my_pack
entries:
  - key: sapphire
    kind: artifact
    item:
      base: AMETHYST_SHARD
      label: '&bSapphire'
      details:
      - '&7A custom crafting ingredient.'
      group: materials
      sprite: my_pack:item/sapphire
      stack: 64
```

Place its texture at `contents/my_pack/resourcepack/assets/my_pack/textures/item/sapphire.png`. Run `/ba validate`, `/ba reload` and `/ba pack build`, then upload the new ZIP if using external hosting. Read [configuration](docs/configuration.md), [world content](docs/world-content.md) and [gameplay](docs/gameplay.md) for complete schemas.

## Developer API and architecture

Resolve `BlocksAdderApi` from Bukkit's `ServicesManager`, or access `BlocksAdderPlugin` in a declared plugin dependency. The API creates/identifies items, finds definitions, places custom content, spawns entities and reads/writes stats. All world, inventory and player operations must run on the server thread.

```java
BlocksAdderApi api = getServer().getServicesManager().load(BlocksAdderApi.class);
ItemStack crystal = api.createItem("blocksadder:crystal", 1);
```

`CustomContentPlaceEvent` and `CustomContentBreakEvent` are cancellable. Player-originated placement/removal also honors Bukkit protection events. Direct block writes by another plugin bypass normal gameplay events; coordinate through the API/events. The API belongs to BlocksAdder and requires integrations to target its interfaces.

Packages separate content loading, item construction, world persistence, recipes, gameplay, pack generation, commands and the public API. Start with [docs/AGENTS.md](docs/AGENTS.md) for a concise contributor map and invariants.

## Build

Install JDK 21 or newer and Maven 3.9+, then run:

```text
mvn -B clean package
```

The current development output is `target/BlocksAdder-1.1.0-SNAPSHOT.jar`. Server APIs are provided by the server and are not bundled. GitHub Actions compiles the project on Java 21 and 25. Temporary test plugins, original artifacts and disposable servers are excluded from the repository.

## SpigotMC publishing files

- [Plugin description in BBCode](docs/spigot-description.bbcode)
- [Documentation in BBCode](docs/spigot-documentation.bbcode)
- [Pixel art icon](assets/blocksadder-icon.png)

Copyright © 2026 Lino. Independently written project sources are available under the [MIT license](LICENSE). Minecraft is a trademark of Mojang and Microsoft; this project is unaffiliated.
