# Find an item's sources

Open `/ba recipe <item>` and hover over the icons beneath the recipe controls. Each icon describes one way the displayed item can appear in the world, drop as a reward, or, for a vehicle, accept fuel. A recipe that uses the inspected item can produce something else. The icons follow the result in the output slot. Vanilla results and custom items without configured sources use the compact recipe window.

## Try the river bundle

Save the example in `plugins/BlocksAdder/contents/`, run `/ba validate` and `/ba reload`, then enter `/ba recipe river:pearl`. Its first page shows a clay block reward and a fishing reward. The fishing rule can give one or two pearls when a player catches cod or salmon. Select **Next recipe** to see the pearl clasp made with an iron nugget. The clasp's own source is a drowned drop. [Download the example YAML](https://github.com/lino9999/BlocksAdder/releases/download/v1.1.0-dev.24/river-finds.yml) · [View the bundle](examples/river-finds.yml)

| Indicator | What it describes |
| --- | --- |
| Block rewards | Rewards for breaking native or custom blocks, including configured amounts, chance and applicable restrictions. |
| Ore deposits | Automatic fields that place the selected custom block, with world, height, replacement and attempt settings. |
| Surface features and Cave features | The placed custom block and its placement conditions. Cave floor and ceiling rules identify their support position. |
| Wild trees | Automatic tree fields that use the result as trunk, leaves, or both. |
| Crop produce | The plantable seed's name and ID. Conditional entries identify the event and permitted crop ages. |
| Creature rewards | Configured native or custom creature rewards, including direct creature drops. |
| Fishing rewards | Rewards selected by the material of the caught item. |
| Accepted fuel | Usable fuel portions for the vehicle placed by the displayed item. The tooltip includes custom fuel names, item IDs, portion sizes and any container returned to the player. |

The notes describe configured possibilities. Player permissions, tools, events, world conditions and available tank space still govern gameplay. A player who can inspect the recipe can read its notes. The icons are read-only. **More source details** and **Previous source details** page through longer notes while keeping the same recipe open. Shorter categories keep their last page available.

Custom world-generation notes require both `generation.enabled` and `world.enabled`. A crop's stage models supply the plant's appearance. Its configured produce supplies the source notes. The vehicle's fuel list includes only portions that can fit in an empty tank.

## Change an icon or label

Use the optional `recipe-sources` section in the server's `config.yml`. Each entry accepts a label, an icon or both. The icon may be a vanilla item material, a `minecraft:` item ID or the full ID of a registered custom item. BlocksAdder gives the display copy its own caption and lore and keeps the original item's model.

```yaml
recipe-sources:
  fishing:
    label: '&bRiver finds'
    icon: minecraft:fishing_rod
  block-drop:
    icon: river:clasp
```

The available keys are `block-drop`, `ore`, `surface`, `cave`, `tree`, `crop`, `entity`, `fishing` and `fuel`. Unspecified keys use the built-in appearance. Validate and reload after editing the settings or the content that supplies the rewards.

## Verification

The release report identifies the exact Minecraft server builds and plugin JAR used to check inventory contents, permissions, navigation, source text and the river example. It also records the direct and rule-based rewards observed on a creature death. [Builds and results](recipe-sources-verification.json)
