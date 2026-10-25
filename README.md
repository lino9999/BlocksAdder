<p align="center"><img src="assets/blocksadder-icon.png" width="128" alt="BlocksAdder pixel art cube icon"></p>

# BlocksAdder

**Custom items, blocks, furniture, crops and resource packs for Minecraft Spigot and Paper.**

BlocksAdder by **Lino** is a Java plugin for building namespaced server content with YAML. Create items, decorate worlds, register recipes, grow crops and display player stats using the Minecraft client and a server resource pack.

[Download the JAR](https://github.com/lino9999/BlocksAdder/releases) · [Configuration](docs/configuration.md) · [World content](docs/world-content.md) · [Recipes and gameplay](docs/gameplay.md) · [Verification](docs/verification.md)

**Release status:** initial independent implementation. The [feature comparison](docs/feature-parity.md) records the implemented surface and remaining differences from the supplied reference plugin. Full-body skeletal animations, player disguises, controlled vehicles, custom fluid/fire simulation and dedicated third-party integration adapters remain unimplemented. Headless server checks do not certify client visuals or every player interaction.

## Features

- **Custom items:** stable namespaced identity, modern item models, float model data, names/lore, stack limits, durability, enchantments, attributes, leather colors, food and equipment models.
- **Custom blocks:** collision blocks with display models, persistent placement indexes, protected placement/removal and configurable chunk limits.
- **Furniture:** display models, interaction hitboxes, optional collision, seats and cleanup on unload or reload.
- **Crops:** configurable model stages, loaded-chunk growth, farmland requirements, repeat harvesting and custom produce.
- **Recipes:** shaped, shapeless, furnace, blasting, smoking, campfire, stonecutting, smithing and anvil recipes with exact custom ingredients.
- **Player content:** persistent bounded stats, regeneration, bossbar/actionbar HUDs, item actions, cooldowns, potion effects and simple gesture/particle/sound timelines.
- **Font images and audio:** bitmap font providers, permission-aware chat/sign/book aliases, custom sound definitions and sound/totem commands.
- **Loot and entities:** block, entity and fishing tables; configured vanilla living entities with custom stats, equipment and drops; manually generated custom trees.
- **Resource packs:** sorted ZIP output, stable hashes, modern item definitions, model/font/sound generation, asset merging, size limits, manual delivery and optional built-in HTTP hosting.
- **Administration and integrations:** paginated catalog, recipe previews, item management, candidate validation, reload rollback, Bukkit service API and cancellable content events.

BlocksAdder has no mandatory plugin dependencies. It uses public server APIs and does not download executable tools, contact activation servers or send telemetry.

## Requirements and installation

The target server range is **Minecraft Java 1.21.11 through 26.3**, on Spigot or Paper. Use **Java 21 for 1.21.11** and **Java 25 for 26.x**, following your server's requirements. See the exact tested builds and test boundaries in [verification](docs/verification.md). Folia and Bedrock resource packs are not supported.

1. Download `BlocksAdder-1.0.0.jar` from [GitHub Releases](https://github.com/lino9999/BlocksAdder/releases).
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
| `/ba emote <id\|stop> [player]` | `blocksadder.emote` | Start or stop a simple effect timeline. |
| `/ba sound <sound> <player> [volume] [pitch]` | `blocksadder.sound` | Play a namespaced sound. |
| `/ba totem <player>` | `blocksadder.totem` | Play the vanilla totem effect. |

Shortcuts are `/baget` (`/bget`), `/bagive` (`/bgive`), `/bapack` (`/bpack`), `/babrowse` (`/bbrowse`), `/bareload` (`/breload`), `/barecipe` (`/brecipe`) and `/bainspect` (`/binspect`). They accept the same arguments as their matching subcommands. Reference-plugin commands, aliases and permission nodes are not registered.

`blocksadder.admin` grants every built-in permission and defaults to operators. Public browsing/help/info/recipe/glyph/pack/HUD/emote commands and ordinary building/use permissions default to everyone; administrative permissions and `blocksadder.pack.build` default to operators. `blocksadder.others` is required to open menus, send packs, control HUDs or trigger emotes for another player. Item quantities are limited to 1–6,400; delivery splits legal stack sizes and drops overflow at the player.

Gameplay permissions are `blocksadder.place`, `blocksadder.break`, `blocksadder.use`, `blocksadder.glyphs` and per-definition permissions. Category access uses `blocksadder.category.*` or `blocksadder.category.<category>`; an explicitly denied category overrides the wildcard. Catalog left-clicks show recipes; right-clicks grant items only with `blocksadder.get`. Shift-right-click grants one legal stack.

## Content example

Create `plugins/BlocksAdder/contents/my_pack/configs/items.yml`:

```yaml
namespace: my_pack
items:
  sapphire:
    material: AMETHYST_SHARD
    display-name: '&bSapphire'
    lore:
      - '&7A custom crafting ingredient.'
    category: materials
    texture: my_pack:item/sapphire
    max-stack-size: 64
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

The output is `target/BlocksAdder-1.0.0.jar`. Server APIs are provided by the server and are not bundled. GitHub Actions compiles the project on Java 21 and 25. Temporary test plugins, original artifacts and disposable servers are excluded from the repository.

## SpigotMC publishing files

- [Plugin description in BBCode](docs/spigot-description.bbcode)
- [Documentation in BBCode](docs/spigot-documentation.bbcode)
- [Pixel art icon](assets/blocksadder-icon.png)

Copyright © 2026 Lino. Independently written project sources are available under the [MIT license](LICENSE). Minecraft is a trademark of Mojang and Microsoft; this project is unaffiliated.
