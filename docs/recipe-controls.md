# Control custom and vanilla recipes

Use the server configuration at `plugins/BlocksAdder/config.yml` to choose which workstations accept BlocksAdder recipes and which Minecraft recipes the server registers. A fresh install enables every custom recipe family and keeps all vanilla recipes.

## Enable custom workstations

For example, a server that uses crafting, cooking and anvils can select those three families.

```yaml
recipe-stations:
  active: [crafting, cooking, anvil]
```

| Name | BlocksAdder recipe types |
| --- | --- |
| `crafting` | Shaped and shapeless recipes, including automatic crafters |
| `cooking` | Furnace, blasting, smoking and campfire |
| `anvil` | Fixed-result anvil operations and custom repairs |
| `smithing` | Smithing transforms |
| `stonecutting` | Stonecutter recipes |

Run `/ba validate` and `/ba reload` after changing this list. Enabled families supply the native registrations, recipe previews and custom discovery rules. Disabled families keep their definitions on disk. Adding a family back validates its recipes before activating them. Vanilla workstation recipes and the protection for custom items used in unrelated recipes each have their own settings.

If a player already has a custom anvil operation open when its family is disabled, BlocksAdder withdraws the obsolete result and lets the anvil recalculate. The player keeps the input items and experience. A stale crafting result also becomes unavailable.

Omitting the whole `recipe-stations` section enables all five families, including when upgrading from preview 18. An empty `active: []` disables every BlocksAdder recipe. Unknown names, duplicate stations and invalid list types fail validation.

## Exclude Minecraft recipes by output

This example removes Minecraft recipes for iron ingots and diamond swords at the next full server start.

```yaml
native-recipes:
  exclude-results:
    - iron_ingot
    - diamond_sword
```

The server selects **every matching recipe key in the `minecraft` namespace** whose result matches its lookup for an ordinary stack of that material. Selecting iron ingots can therefore affect crafting, smelting and blasting together. Recipes that merely consume iron ingots continue to work. A BlocksAdder recipe or another plugin's recipe in its own namespace can still produce an iron ingot. A data pack that uses a `minecraft` key participates in the filter.

Use current item material names. Uppercase names and a lowercase `minecraft:` prefix are accepted. The configuration rejects air, non-item blocks, unknown names, duplicate materials and other namespaces. Omit this section or use `exclude-results: []` to keep every Minecraft recipe.

1. Edit the exclusion list and run `/ba validate` to check the material names.
2. Run `/ba reload` to load your content changes. A restart notice appears when the new exclusions differ from those used at startup.
3. Stop and start the server. Minecraft loads its authoritative recipe data, then BlocksAdder applies the configured exclusions before players connect. Removing a material from the list takes effect at this same point.

The filter runs once per full start. A later addition by another plugin or data pack follows the server's normal registration lifecycle. Restoring a recipe definition and restoring a player's earlier recipe-book knowledge are separate operations. Bukkit documents that removing recipes can affect saved discovery data. [Server recipe API](https://jd.papermc.io/paper/1.21.11/org/bukkit/Server.html#removeRecipe(org.bukkit.NamespacedKey))

On each of nine verified Spigot and Paper builds, the same test world went through an unfiltered start, a filtered start and a restored start. Players crafted and smelted iron in the control and restored worlds. During the filtered start, a BlocksAdder recipe still produced iron and a foreign-namespace recipe survived. [Exact builds and results](recipe-controls-verification.json)

## How this was reconstructed

Static REA and JVM inspection traced the original's output setting to a startup query for matching recipes followed by a namespace check and native key removal. Four separate settings controlled its custom recipe loaders. BlocksAdder implements these behaviors with its own list-based YAML, a station selector and a startup filter. BlocksAdder also makes stonecutting independently selectable. The startup filter inventories candidates before applying removals and reports any changed or rejected keys.
