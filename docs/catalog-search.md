# Find custom items in game

Run `/ba search crystal` to find items whose namespaced ID or display name contains “crystal”. Use a namespace such as `/ba search kiln:` to see that bundle's visible items. The shortcuts `/basearch` and `/bsearch` accept the same text. Every player has `blocksadder.search` by default.

With a compatible ProtocolLib installed, click the **Search items** compass in the catalog or run `/ba search` to open a private sign editor. Its four lines form one query in reading order. To find “blue tile” across two lines, finish the first line with a space after “blue” and put “tile” on the next. Submit a blank sign to see all searchable items. The editor expires after 1,200 server ticks (one minute at 20 TPS). Opening another inventory, teleporting, disconnecting or reloading content ends the request. If the editor is unavailable, enter the text directly after the command.

## Read the results

Each page holds up to 45 items sorted by full ID. Matching ignores capitalization and display-name colors. Search punctuation and spaces are literal. For example, `kiln:tile` matches by ID even if the item has a different display name. To browse a category such as pottery, use `/ba browse pottery`. If a query has no matches, the compass reports that the list is empty and lets you start another search.

Left-click a result to inspect its recipes. **Back** in the recipe browser returns to the same query and page. **Back to catalog** on a results page opens the catalog for players with `blocksadder.browse`. A new search starts at the first page.

## Control access

| Setting or permission | Effect |
| --- | --- |
| `blocksadder.search` | Allows the command and compass. |
| `traits.hidden: true` | Omits the item from both browsing and search. |
| `traits.catalog.permission` | Requires this item-specific node for a result to appear. |
| `blocksadder.category.<category>` | Controls browsing that category. Global search includes eligible items across categories, so use the item-specific permission for a restricted listing. |
| `traits.catalog.inspect: false` | Keeps the item searchable and disables opening its recipe pages. |
| `blocksadder.get` | Allows right-clicking a result to receive one item, or shift-right-clicking for a stack. |

Search uses the existing bundle records. The pottery example includes three item definitions and shows how to restrict the glaze listing. [Pottery bundle](examples/recipe-catalog.yml) · [Recipe navigation](recipe-catalog.md)

The release tests include nine Spigot and Paper builds with the sign editor and two builds using typed search without ProtocolLib. [Exact builds and results](catalog-search-verification.json)
