# Organize the item catalog

Run `/ba browse` to see the groups available to you. Select an icon to open its items. The number on the icon counts the items you can see in that group. A group can appear before its first item is added. The built-in **All items** group provides a single list of visible items.

The group overview and each item list hold up to 45 entries per page. Open a recipe, then use **Back** to return to the same item page. **Back to catalog** returns to the overview page from which you selected the group. Opening a group directly with `/ba browse showcase` skips the overview. The shortcuts `/babrowse` and `/bbrowse` accept the same arguments.

## Try the pottery shelf

Place both bundles in `plugins/BlocksAdder/contents/`, run `/ba validate` and `/ba reload`, then open `/ba browse showcase`. The shelf displays the kiln tile first and the clay blank second. The glaze has a separate restricted group. **Upcoming exhibits** starts empty. The fallback crystal display is skipped because the pottery shelf already defines `showcase`. [Pottery items](examples/recipe-catalog.yml) · [Showroom groups](examples/catalog-groups.yml)

| Catalog record setting | Behavior |
| --- | --- |
| `group` | Global group key used by the command and by the `item.group` field. Defaults to the record key. Records from different bundles can contribute to the same group. |
| `label`, `title` | Caption on the overview icon and heading of the item window. Setting just one provides both. |
| `icon` | Vanilla item material or full custom item ID. A custom icon keeps its model. BlocksAdder prepares its catalog display separately from the actual item. |
| `rank` | Signed integer. After pinned groups, ranked groups appear lowest rank first, with ties sorted by group key. Unranked groups follow the configured remainder order. **All items** appears last unless pinned or ranked. |
| `permission` | Optional full permission node for this group. Without it the group uses `blocksadder.category.<group>` and the existing category wildcard. |
| `include`, `exclude` | Ordered lists of item IDs or wildcard selectors. A bare item key refers to the contributing bundle. A qualified selector such as `kiln:*` selects items in another bundle. |
| `all-items` | Set to `true` to include all registered items. Item listing permissions and exclusions still apply. |
| `if-defined` | Use `merge` to contribute to an existing group (the default). Use `skip` for a fallback record that creates the group only when earlier active records leave it undefined. |

A selector accepts up to 256 characters. Use `*` for any run of identifier characters and `*:*` for all namespaces. Each inclusion is expanded in canonical item-ID order. The first occurrence fixes an item's place. The group then includes items assigned through `item.group`, or the whole catalog for an all-items group, and applies exclusions. The same item can appear in several groups. An item group used only by older bundles gains an overview tile automatically.

When multiple records target a group, BlocksAdder processes their namespaced record IDs in order. The first specified value for each presentation setting wins, and a later contribution can supply a usable icon. The fallback icon is stone. Each contribution can add or exclude members. To disable a group supplied by a single record, set `active: false` on that record. An enabled contribution keeps a shared group available.

## Choose the overview order

Use `catalog-layout` in the server's `config.yml` to pin groups from any bundle. This example puts **Upcoming exhibits** first and **Pottery shelf** second. Groups with a rank follow, then the remaining groups in declaration order. Run `/ba validate` and `/ba reload` to apply it.

```yaml
catalog-layout:
  front: [exhibits, showcase]
  remainder: declared
```

The default is an empty front list and `remainder: alphabetic`. In `declared` mode, active authored groups follow their first declaration in file and record order. Groups created solely from item assignments follow in first item registration order. A missing pinned group takes no space. Pinned names are case-insensitive and must be unique. Include `all` in the front list to give the built-in group a fixed position.

## Permissions

Players need `blocksadder.browse` to browse the catalog. For the generated category permission, an explicit denial overrides `blocksadder.category.*`. The optional literal `permission` on a group controls access to that group directly. To open a group for someone else, use `/ba browse <group> <player>` with `blocksadder.others`. Both players need access to the group and the recipient needs catalog access.

Use `traits.hidden` or `traits.catalog.permission` to restrict the item itself across groups and global search. The **All items** group and other groups containing the same item each have their own access rules. Receiving an item still requires `blocksadder.get`. [Search](catalog-search.md) · [Recipe inspection](recipe-catalog.md)

The release tests cover nine Spigot and Paper builds, using two protocol players for navigation and the public showroom bundle for configuration checks. [Exact builds and results](catalog-groups-verification.json)
