# Recipe reconstruction

The supplied original JAR was inspected statically, never executed. Observations below come from `td`, `vm`, `vb`, `vc`, `wc`, `wd` and the modern `ajd` adapter. Production implementation and tests are independent; no original source or assets are included.

## Observed contracts

`td` loads original crafting-table patterns, replacement items, durability decrement/increment entries and optional return sounds. `vm` applies those returns to consumed ingredients and intercepts result clicks. Its original crafter protection rejects flagged custom items and can destroy a hopper-fed crafter; BlocksAdder never reproduces that destructive behavior.

Original `recipes.anvil_repair` entries contain `item`, `ingredient` and permission fields. `vc` repairs the input item while retaining its metadata, consumes repair materials in quarter-durability units and increases repair penalties. BlocksAdder uses bounded quarter repair with the minimum required ingredient count, avoiding the original's rounding and extra-point edge cases.

Original smithing `result.move_attributes` defaults are name/lore false, enchants true, durability/custom_model_data/material false and other_nbt true. `wc` prepends base lore, retains the stronger enchantment level and transfers the fraction of remaining durability. `ajd.f` merges custom data, rather than copying every modern item component. BlocksAdder preserves the result's stable custom identity when moving persistent data.

## Return-item definitions

```yaml
recipes:
  carve:
    type: shapeless
    result: example:carved_gem
    ingredients: [example:chisel, AMETHYST_SHARD]
    return_items:
      replace:
        AMETHYST_SHARD: GLASS_BOTTLE
      decrement_durability:
        chisel:
          item: example:chisel
          amount: 1
      play_sound:
        name: minecraft:block.note_block.pling
        category: PLAYERS
        volume: 0.3
        pitch: 1.0
        stop_previous: true
```

`increment_durability` has the same entry structure and repairs its returned tool. The configured amount must be positive. Replacement and durability rules cannot overlap for the same ingredient. Unqualified custom return references resolve relative to the recipe namespace. Legacy Bukkit sound names such as `BLOCK_NOTE_BLOCK_PLING` resolve through the public sound registry; namespaced custom sounds are accepted.

Crafting returns use an isolated transaction plan. Ingredients, returned containers, output inventory capacity and cursor capacity are validated before inventory changes commit. Shift crafting performs at most 64 complete crafts and stops when ingredients, tool durability or destination capacity runs out. Full inventories leave ingredients intact. Normal left/right clicks, an empty numbered hotbar destination and compatible offhand destinations are supported. Drop-key actions dispatch PlayerDropItemEvent before committing input consumption; cancellation removes the tentative output entity and preserves every ingredient.

Durability-bearing custom inputs use native material choices for recipe matching and an independent namespaced identity predicate in the service. Their names, lore and damage may change without losing recipe identity. Ordinary custom exact ingredients and structured potion predicates retain exact matching. Candidate selection resolves native material-choice collisions before output or consumption is accepted.

## Native crafters

Spigot 1.21.11 exposes `CrafterCraftEvent.getRemainingItems()`. Its list corresponds to the trimmed ingredient rectangle, which may contain fewer than nine cells. BlocksAdder updates those native remainders and leaves consumption, ejection and destination handling to the server. Reusable tools are ejected as remainders, following native crafter behavior; a hopper system can return them to the machine. Exhausted tools produce no tool remainder. Native recipe permissions with no player actor remain enforced.

Paper 26.3 build 218 lacks that public method. The capability bridge uses cached public reflection to avoid linkage failures and cancels automatic crafting of return-item recipes on that runtime. Inputs and machine remain intact; the console reports the unsupported capability once. Player crafting remains available. `RecipeService.supportsNativeCrafterReturns()` exposes the distinction. This is an explicit endpoint limitation, not a claim of equivalent automated behavior.

The upstream API contract is documented by [Spigot CrafterCraftEvent](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/event/block/CrafterCraftEvent.html). Actual endpoint behavior was measured instead of assuming Paper implements every Spigot method.

## Smithing and repair

Normalized smithing configuration uses `move-attributes` with the original flag names or hyphenated equivalents. Moving name copies a present base display name. Moving lore prepends base lore to result lore. Enchantments retain the larger level. Durability transfers the remaining fraction, rounded up, against the result's own maximum. Model data copies the full public component. Material movement changes the physical material after metadata selection.

`other_nbt` moves public persistent-data entries while preserving already defined result entries and its BlocksAdder identity. Arbitrary unnamed third-party CUSTOM_DATA tags that public Bukkit does not expose are not certified. The adapter does not silently copy unrelated max-damage, attribute or item-model components under this flag.

```yaml
recipes:
  anvil_repair:
    chisel:
      item: example:chisel
      ingredient: IRON_INGOT
      permission: blocksadder.recipe.repair_chisel
```

The original family normalizes to `type: anvil-repair`, `base` and `addition`. Repair output retains the actual input's name, identity and persistent data. It consumes only the ingredients needed, sets native repair costs and increases the result's prior-work penalty. Renaming while repairing uses the accepted native text and charges one additional level; empty rename text removes the custom display name while retaining item identity. Invalid repair ingredients or undamaged inputs produce no custom repair plan. Explicit transform recipes remain available through `type: anvil`.

## Verification

The module compiles to Java 21 against Spigot 1.21.11. A module candidate is tested by replacing only recipe classes in a frozen integrated 1.1.0-SNAPSHOT JAR. This does not certify unrelated later changes or a new release artifact.

Candidate SHA-256 `e1c5c42b6ab3d864f5bf097059c71fc7662800e4718c40b04dd789efb9270b0f` passed 47 assertions on Spigot 1.21.11/Java 21 and 40 on Paper 26.3/Java 25. Tests use real runtime ItemStacks, custom metadata, registry registration, repair plans and smithing transfers. Shift/capacity/sound/offhand/drop checks execute real service transaction code with public-interface inventory fixtures and real dropped entities, including cancelled-drop rollback; these are not native player click sessions. Spigot additionally performs three actual redstone crafter activations and verifies ejected outputs, returned replacements, damaged tools and final tool exhaustion. Paper verifies native crafter cancellation without consuming inputs, output or machine destruction.

### Native protocol-player acceptance

Candidate SHA-256 `c800305ebcefa4e2af7370333ff5fade1b15cb123b3d6924925f8fb1ce702480` passed 131/131 assertions on both Spigot 1.21.11 and Paper 1.21.11, using Java 21 and an actual Minecraft 1.21.11 protocol player on isolated servers. The client acknowledges login/teleports, receives native menu/result/cost/sound packets, and sends real result-slot clicks. Assertions inspect the actual server player, menu inputs, cursor, offhand, hotbar, experience and dropped entities after collection. These checks do not use proxy inventories or a graphical client.

Verified routes include normal and shift collection, numbered hotbar and offhand collection, dropped output, cancelled drop rollback, full-inventory refusal, cancelled crafting, repeated result clicks, already damaged/renamed custom tools and vanilla lookalike rejection. Three shift crafts consume a reusable tool exactly at its maximum durability. Repeated result clicks can legitimately convert a final returned blaze rod into two vanilla blaze powder; the assertion checks conserved material units and exactly three custom outputs rather than misclassifying this native follow-up recipe as duplication. Successful return transactions send exactly one sound packet; refused/cancelled transactions send none.

Native anvil checks verify result collection, exactly three repair materials, matching client cost packets, one-time XP charges, rename/name removal, insufficient-XP refusal and cancellation. The initial player test exposed Spigot resetting repair cost after PrepareAnvilEvent; cost synchronization now runs one tick later only when the same view, identical inputs, authorization and intended result still hold. Native smithing checks verify metadata before consumption and after collection, correct result identity, lore order, enchantments, proportional durability, foreign persistent data and one-time consumption of all three inputs. Cancellation leaves those inputs intact.

Spigot reports its SmithItemEvent result slot as SlotType.CRAFTING rather than RESULT. Production smithing guards use the event type; tests identify raw result slot 3. Do not add a RESULT-only filter to those smithing guards.

Native protocol-player acceptance currently covers the two 1.21.11 forks. No supported 26.3 client codec is installed; the earlier Paper 26.3 server/transaction checks and public API linkage inspection remain narrower evidence. Named protection-plugin integration and arbitrary raw third-party custom-data movement remain acceptance gates. The user handles rendered appearance separately; no rendered client appearance is claimed by these probes.
