# Recipe book discovery

Players can learn a recipe when they first handle one of its custom ingredients. With the shipped settings, picking up a BlocksAdder crystal teaches recipes that consume the crystal. Clicking the crystal in an inventory also counts. Renaming the crystal preserves its identity. An ordinary amethyst shard has a different identity.

## Choose when players learn recipes

Fresh installations use the following server setting in `plugins/BlocksAdder/config.yml`.

```yaml
recipe-book:
  learn: [encounter]
```

An existing configuration that omits the entire `recipe-book` section retains preview 17's grant-on-join behavior. To enable progression on that server, add the section above and run `/ba validate` followed by `/ba reload`. Players keep previously learned recipes for which they still have permission. To introduce a discovery path gradually, override the policy on individual recipes.

Place `book.learn` under a recipe record's `settings` to replace the global list for that recipe. Each trigger may appear once. Any listed trigger can teach the recipe.

| Trigger | What the player does |
| --- | --- |
| `encounter` | Picks up or clicks a registered custom ingredient. The full BlocksAdder item ID determines the match. |
| `join` | Joins the server while eligible. A successful content reload also checks connected players. |
| `pickup-input` | Picks up an ingredient matching the recipe's configured choice. This can include vanilla materials. |
| `click-input` | Clicks an ingredient matching the configured choice. |
| `pickup-output` | Picks up the result. Custom results match by ID. Vanilla results match their item metadata and material. |
| `click-output` | Clicks the result using the same matching rules as `pickup-output`. |
| `[]` (empty list) | Disables automatic grants by BlocksAdder for this recipe. An authorized grant from another source can still teach it. |

For clicks, BlocksAdder examines the clicked slot. An empty slot uses the stack on the cursor. Outside-inventory clicks and cancelled events do not trigger discovery. Stack count has no effect on matching. Exact input choices retain their crafting metadata checks, so choose `encounter` when a renamed custom ingredient should teach a recipe.

The downloadable example adds two recipes. A crystal teaches a spyglass recipe. Picking up quartz, a copper ingot or glass, or clicking the finished tinted glass, teaches a second recipe. It uses the `blocksadder:crystal` supplied with the starter bundle. Save the file under `plugins/BlocksAdder/contents/`, validate and reload. [Example YAML](examples/recipe-book.yml)

## Permissions and workstations

The same recipe-level `settings.permission` governs discovery and player crafting. Players can retry an encounter after gaining permission or after another plugin cancels discovery. Joining and reloading also remove access to owned entries for which permission was revoked. Servers can use `/ba recipe <item>` to show a recipe preview independently of these learning rules.

The index supports shaped and shapeless crafting, furnace, blasting, smoking, campfire, stonecutting and smithing transform recipes. For smithing, the index includes the template, base and addition. Minecraft determines which workstation interfaces display known entries. Anvil operations have no native recipe key and reject a per-recipe book rule. Cooking recipes cannot carry a player permission because a furnace can run unattended.

## Reconstruction

Static REA inventory and inspection of the reference artifact traced pickups and clicks to the ingredient index. Its shipped defaults enable encounters. Its separate result lookup does not grant a recipe merely because the player holds its product. BlocksAdder implements these behaviors with an independently built index and YAML policy. Explicit output triggers are a BlocksAdder extension. Each queued grant checks cancellation, current registration, player connection and permission.

The preview 18 tests used protocol players on nine Spigot and Paper builds. On both endpoint versions, eight custom ingredients built from iron swords were renamed and damaged. Each unlocked its own cooking, cutting or smithing recipe and the server retained the unlock through reconnect. [Exact builds, assertions and scope](recipe-book-verification.json)
