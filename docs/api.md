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
| Crops | `growCrop(location, stages, player)`, `plantView(player)`, `plantView(player, mode)` |
| Stats | `stat(player, id)`, `stat(player, id, value)` |
| Skeletal models | `attachModel`, `playAnimation`, `detachModel`, `modelVariable`, `modelVariables` |
| Player models | `disguise`, `undisguise`, `playEmote`, `stopEmote`, `emoteState` |
| Model cameras | `modelCamera(instance, viewer, distance)`, `stopModelCamera(viewer)` |
| Player skins | `modelSkin(instance, player)`, `modelSkin(instance, texture)`, `resetModelSkin(instance)` |
| Vehicles | `spawnVehicle`, `mountVehicle`, `vehicleFuel` |

Placement and removal report whether an operation committed. Protection and custom-event cancellation can reject an operation. Model and vehicle instances use UUIDs; they differ from definition IDs. The `player` argument supplies player permission/protection context where supported. Null represents a server-initiated operation and does not bypass cancellable custom events.

Crop growth clamps to maturity and emits `CustomCropGrowEvent` with cause `API`. It preserves the current stage on cancellation or failed regeneration. Player emote commands/API route skeletal definitions to the model controller and retain frame-effect timelines for existing content. `stop-now`/immediate stop skips an optional end animation. Skeletal emote status is available through `emoteState`.

`plantView` reads or persists `CropViewService.Mode.AUTO`, `FULL` or `HIDDEN`. It schedules incremental per-player crop visibility; it does not synchronously render every crop or change its saved growth. The API setter trusts the calling integration; command permissions are enforced by the command adapter. See [plant views](crop-views.md) for caps, budgets and hidden interactions.

`CustomCropDestroyEvent` exposes natural destruction separately from growth and harvesting. Cancelling it protects the crop and its native source operation where applicable. Skin APIs select existing player or configured texture profiles without implicit profile-update network requests. Live skin shader/runtime acceptance remains tracked in the model guide.

ProtocolLib is optional. Core item/world/model functionality must remain usable without it. Camera, equipment-view masking and typed tile-NBT predicates have adapter-specific requirements. Consult [model behavior](entity-models.md), [equipment](equipment.md) and [verification](development-verification.md) before depending on a development feature.

Cancellable public events live under `io.github.lino9999.blocksadder.api.event`; subsystem events also live alongside generation, environment, models and vehicles. Bukkit placement, break, growth, fertilizing, damage and inventory protection events are honored by the corresponding adapters. This API is independently defined and does not provide the reference plugin's binary package compatibility.

`CustomContentInteractEvent` prepares a player use of managed content and exposes its anchor, ID, kind, hand and optional helper entity. Cancellation prevents the placed plan and built-in furniture/crop operation. Event callbacks cannot transfer the outer operation to a replacement with the same ID. The native event is not exposed as a writable field; use Bukkit's native events for their own lower-priority protection checks. See [placed-content plans](world-plans.md) for timing, delays and side-effect limits.

Custom break events prepare an operation; they are not committed-removal notifications. Held-item plans run after protection and supported-crop preparation, may still veto removal and are followed by an exact placement-instance check. Native `BlockBreakEvent.setDropItems(false)` cannot be re-enabled by a later custom callback. The internal `ContentActionHook.BreakAttempt` reports permission before mutation and applies custom tool wear once after saved removal. Its implementation belongs to BlocksAdder's service composition; integrations should use the documented world operations/events rather than replace that hook.
