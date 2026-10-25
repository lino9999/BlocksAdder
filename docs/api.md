# Developer API

Obtain the registered Bukkit service after BlocksAdder enables:

```java
BlocksAdderApi api = Bukkit.getServicesManager().load(BlocksAdderApi.class);
if (api == null) throw new IllegalStateException("BlocksAdder is unavailable.");
ItemStack item = api.createItem("example:crystal", 1);
```

Use the public interface in `io.github.lino9999.blocksadder.api`. Declare BlocksAdder as a dependency or soft dependency in your own plugin, according to whether it is required. Keep item/world/player/model operations on the server thread. Namespaced IDs provide stable identity; short IDs resolve only when unambiguous. Reacquire definitions after a content reload.

| Area | Methods |
| --- | --- |
| Definitions and items | `definitions()`, `find(id)`, `createItem(id, amount)`, `identify(ItemStack)` |
| World content | `identify(Block)`, `place(location, id, player)`, `removeBlock(location, player, drops)`, `spawnEntity(location, id)` |
| Liquid and fire | `placeLiquid`, `placeFire`, `identifyEnvironment` |
| Crops | `growCrop(location, stages, player)` |
| Stats | `stat(player, id)`, `stat(player, id, value)` |
| Skeletal models | `attachModel`, `playAnimation`, `detachModel`, `modelVariable`, `modelVariables` |
| Player models | `disguise`, `undisguise`, `playEmote`, `stopEmote`, `emoteState` |
| Model cameras | `modelCamera(instance, viewer, distance)`, `stopModelCamera(viewer)` |
| Vehicles | `spawnVehicle`, `mountVehicle`, `vehicleFuel` |

Placement and removal report whether an operation committed. Protection and custom-event cancellation can reject an operation. Model and vehicle instances use UUIDs; they differ from definition IDs. The `player` argument supplies player permission/protection context where supported. Null represents a server-initiated operation and does not bypass cancellable custom events.

Crop growth clamps to maturity and emits `CustomCropGrowEvent` with cause `API`. It preserves the current stage on cancellation or failed regeneration. Player emote commands/API route skeletal definitions to the model controller and retain frame-effect timelines for existing content. `stop-now`/immediate stop skips an optional end animation. Skeletal emote status is available through `emoteState`.

ProtocolLib is optional. Core item/world/model functionality must remain usable without it. Camera, equipment-view masking and typed tile-NBT predicates have adapter-specific requirements. Consult [model behavior](entity-models.md), [equipment](equipment.md) and [verification](development-verification.md) before depending on a development feature.

Cancellable public events live under `io.github.lino9999.blocksadder.api.event`; subsystem events also live alongside generation, environment, models and vehicles. Bukkit placement, break, growth, fertilizing, damage and inventory protection events are honored by the corresponding adapters. This API is independently defined and does not provide the reference plugin's binary package compatibility.
