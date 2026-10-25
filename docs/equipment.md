# Equipment assets

Development version: 1.1.0-SNAPSHOT.

Equipment families generate native equipment JSON and place the supplied textures in the corresponding humanoid and leggings directories. Item references use their own namespace when no namespace is specified.

```yaml
namespace: example
equipments:
  crystal:
    renderer: armor
    layer_1: armor/crystal_layer_1
    layer_2: armor/crystal_layer_2.png
    dyeable: true
items:
  crystal_chestplate:
    material: LEATHER_CHESTPLATE
    equipment:
      slot: CHEST
      id: crystal
```

Place the two PNG files under the namespace's `textures/armor/` directory. Texture paths may include the `textures/` prefix and `.png` suffix. Generated files use `assets/example/equipment/crystal.json` and `assets/example/textures/entity/equipment/{humanoid,humanoid_leggings}/crystal.png`. A supplied equipment JSON takes precedence. Missing custom textures fail the pack build with their exact expected path. Matching `.png.mcmeta` files are retained.

`armors_rendering` and `legacy_armor_renderings` also produce equipment families and require their original `color` field. The `specific_properties.armor.custom_armor` item reference selects the family. Legacy armor slot inference selects the corresponding leather piece. Colors, slots, equip sounds and supported equippable flags use public item components.

The native asset compiler does not yet reproduce the original shader-based animation and emissive armor effects. Copying texture metadata is not evidence that those effects render correctly. Their independent implementation remains on the parity checklist. Rendered appearance belongs to the user's own checks.

Server probes verify native JSON layer references, exact texture/metadata copying, explicit asset precedence, missing-texture errors and path rejection. Candidate hashes and endpoint results are recorded in [development verification](development-verification.json).
