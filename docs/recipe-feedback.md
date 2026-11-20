# Tell players when a recipe is locked

When a player's ingredients match a custom recipe but the player lacks its permission, BlocksAdder can explain why the result is unavailable. The default is a short preview in that player's empty result slot. It keeps the output's item name and model and adds `Recipe locked` as the first line of lore. The server keeps the real result empty.

## Choose the feedback

In `plugins/BlocksAdder/config.yml`, set the channels you want and run `/ba validate` followed by `/ba reload`. For example, to enable both the slot preview and a chat message, use the following setting.

```yaml
recipe-feedback:
  channels: [result-slot, chat]
```

| Channels | What the player receives |
| --- | --- |
| `[result-slot]` | A private slot preview. If the optional ProtocolLib integration cannot send it, the player receives the chat message once. This is also the default when upgrading. |
| `[chat]` | The message “You do not have permission to use this recipe.” |
| `[result-slot, chat]` | Both forms of feedback when the slot preview is available. |
| `[]` | The result stays locked silently. |

To enable slot previews, install the ProtocolLib build for your server and start the server with it present. Chat works with BlocksAdder alone. Omitting the entire section selects the default. A section with a missing list, an unknown channel or a duplicate fails validation.

## Protect a recipe

The recipe uses the permission you put in its bundle record. For example, this recipe lets players with `workshop.craft.paper` make paper from two leaves. Other players see the configured hint when they click its empty result.

```yaml
format: blocksadder/bundle-1
bundle: workshop
entries:
  - key: pressed_sheet
    kind: recipe
    settings:
      type: shapeless
      ingredients: [oak_leaves, oak_leaves]
      result: paper
      permission: workshop.craft.paper
```

## When feedback appears

| Station | Trigger | Delivery |
| --- | --- | --- |
| Player crafting grid or crafting table | The player clicks the empty result of a matching locked shaped or shapeless recipe. | On the next server tick, after other plugins can cancel the click. |
| Anvil | The inputs match a locked custom repair or fixed-result operation. | Configured chat at preparation and the slot preview on the next tick. |
| Smithing table | The template, base and addition match a locked transform. | Five ticks after preparation. |

The slot preview lasts up to 20 server ticks. Taking the result still follows the server's normal permission and ingredient checks. Changing inputs, closing the window or reloading the recipe registry invalidates a pending preview. Repeated preparation of the same anvil or smithing operation produces one notice until the operation changes.

Custom ingredients are matched by identity. A vanilla lookalike does not trigger the hint, and a permitted recipe with the same ingredients takes precedence. Recipe-book discovery, cooking and stonecutting have no permission notice from this setting. Recipe discovery and workstation availability have their own controls. [Recipe book progression](recipe-book.md) · [Workstation controls](recipe-controls.md)

On the tested Spigot and Paper builds, a survival-mode protocol client received and decoded the private item and its later removal. It also clicked the displayed result while the server tracked the real inputs, cursor and experience. The optional-dependency checks cover Spigot 1.21.11 and Paper 26.3 with ProtocolLib absent. [Exact builds and verification results](recipe-feedback-verification.json)
