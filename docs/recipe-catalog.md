# Browse recipes and ingredient uses

Open `/ba recipe` while holding a custom item, or use `/ba recipe <item>` to look it up by ID. The shortcuts `/barecipe` and `/brecipe` accept the same arguments. You can also left-click an item in `/ba browse [category]`. To open a named item for another player, use `/ba recipe <item> <player>` with `blocksadder.recipe` and `blocksadder.others`.

**Try the pottery example.** Copy the example bundle into `plugins/BlocksAdder/contents/`, run `/ba validate` and `/ba reload`, then enter `/ba recipe kiln:tile`. The first two pages show how to make a tile in a furnace and by crafting. The third shows how tiles and glaze make blue terracotta. Click the clay blank in either production recipe to see its own uses. [Download the example YAML](examples/recipe-catalog.yml)

## Navigate

- The book identifies the workstation and whether the recipe produces or uses the selected item. The inventory title shows the current page and total. Previous and Next appear when a page exists in that direction.
- Recipes producing the item appear first, followed by recipes that use it as a registered custom ingredient. Within each group, pages are sorted by recipe key. An ingredient repeated in a shape still produces one page for that recipe.
- An item with no producing recipe starts on an information page. Its uses follow, including recipes with vanilla outputs. Disabling a workstation removes its pages on reload.
- Click a registered custom ingredient or another custom result to inspect it. Ordinary clicks on vanilla ingredients leave the page open. **Back** returns to the immediately preceding item and page, then to the originating catalog category and page. At a direct command entry, Back closes the menu. **Close** exits from any page.
- During an online session, BlocksAdder remembers up to 128 item page positions per player. Logging out or reloading content clears the saved positions.

The catalog covers shaped and shapeless crafting, the four cooking stations, stonecutting, smithing, fixed-result anvils and anvil repairs. Repair previews show a single item of the repairable base type. The player's actual anvil determines the repaired stack's damage, name and costs.

## Choose who can find or inspect an item

Put these optional fields under an item-bearing record's `traits` in your content bundle.

```yaml
traits:
  catalog:
    permission: kiln.catalog.glaze
    inspect: true
```

| Setting | Effect |
| --- | --- |
| `catalog.permission` | The viewer needs this literal permission node to see the item in the catalog and recipe-command suggestions. Category access and the existing `hidden` setting also apply. Omit the node for public listing within its category. |
| `catalog.inspect` | Set to `false` to disable opening this item's recipe pages from the catalog, a command or another recipe. The default is `true`. |
| Recipe record's `settings.permission` | Controls who can craft the recipe. Players can inspect the recipe before earning that permission. |

An unlisted item can still be opened by known ID or through another recipe when inspection is enabled. An ingredient can appear as part of a recipe even if opening that ingredient's own page is disabled. A listing permission therefore does not conceal an ingredient's identity in every context.

## Administrative item grants

With `blocksadder.get`, the **Receive displayed item** button gives the selected result with its configured quantity and metadata. On an information page it gives one copy of the subject. Shift-clicking an ingredient gives one registered custom item or one plain vanilla ingredient. The new recipe-page grants mark inventory overflow with the recipient's UUID. Taking an item this way uses an administrative permission and consumes no crafting inputs.

The existing catalog shortcuts also work. Right-click a listing to receive one item, or shift-right-click it for a full stack. The server rechecks the open view and permissions before granting items. [Recipe permission feedback](recipe-feedback.md) · [Workstation controls](recipe-controls.md)

The acceptance tests use two protocol players to navigate menus, compare received slots, change permissions and check the granted items against the server inventory. The pottery bundle is also loaded in the core server tests. [Exact builds and test results](recipe-catalog-verification.json)
