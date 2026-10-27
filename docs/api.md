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
| World content | `identify(Block)`, `place(location, id, player)`, `changeContent(location, match, replacement, player)`, `removeBlock(location, player, drops)`, `spawnEntity(location, id)` |
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

`CustomContentInteractEvent` prepares a player use of managed content and exposes its anchor, ID, kind, hand and optional helper entity. Cancellation prevents the native held adapter, placed plan and built-in furniture/crop operation. Event callbacks cannot transfer the outer operation to a replacement with the same ID. The native event is not exposed as a writable field; use Bukkit's native events for their own lower-priority protection checks. See [placed-content plans](world-plans.md) for timing, delays and side-effect limits.

For native managed clicks, the root forwards the actual input event to the held adapter after preparation protection. The internal `ContentActionHook` supplies native-use, held-interact and held-attack hooks; custom compositions must preserve exact target identity before physical effects. Offhand preparation exposes `OFF_HAND` but does not replay primary-hand physical behavior. API furniture use without a native event remains physical-only. See [held routing](held-world-interactions.md) for admission and source snapshots.

Custom break events prepare an operation; they are not committed-removal notifications. Held-item plans run after protection and supported-crop preparation, may still veto removal and are followed by an exact placement-instance check. Native `BlockBreakEvent.setDropItems(false)` cannot be re-enabled by a later custom callback. The internal `ContentActionHook.BreakAttempt` reports permission before mutation and applies custom tool wear once after saved removal. Its implementation belongs to BlocksAdder's service composition; integrations should use the documented world operations/events rather than replace that hook.

`CustomContentEnvironmentEvent` prepares environmental crop removal or a custom-block explosion removal after existing protection. It exposes the anchor, content ID, kind, cause and optional real player. Cancellation vetoes the operation; it is not a committed notification. The independent [environment rules](environment-removal.md) execute their configured effects only after saved removal. The internal `RemovalPlans` receipts and `ItemWorldAccess.Target` predicates belong to service composition; integrations should observe the event and use documented world operations instead of constructing their own source guards.

`changeContent` conditionally changes a matching native/custom block or furnishing and reports whether it committed. `CustomContentReplaceEvent` exposes the previous/next content IDs or native block-data strings. It retains cloned coordinates, optional actor and cancellation. Native break/place and custom placement protection remain binding. The [world-change guide](world-changes.md) specifies payload retention, rollback, physical ownership, temporary tickets and unsupported migration cases. This is a one-cell or protected furnishing replacement, not mining or a multi-block edit API.

## Native projectile integration

The core registers `ProjectileLaunchEvent` and `ProjectileHitEvent` adapters for native item-bearing projectiles with a player shooter. Configure `flight.launch/block/entity` item plans; see [projectile actions](projectile-actions.md). Server integrations should initialize the shooter and item in a public `World.spawn` pre-spawn consumer when launch actions are required. Changing an item after `Player.launchProjectile` returns cannot retroactively replay a launch; a later impact reads the native payload available then. Keep these operations on the server thread.

Typed particle action configuration is documented in [particle actions](particle-actions.md). It resolves data through the running server's public Bukkit particle types; it adds no binary dependency on newer geyser classes and never loads emission/destination chunks.

Native effects and custom item activation are described in [effects and totems](effects-and-totems.md). `boolean playTotem(Player player, String item)` uses a custom item ID, with `null` selecting vanilla. It requires the main thread, returns false for unknown IDs or unavailable/reentrant playback, and can reject a lossy component round trip. Custom playback sends a temporary self equipment view and restores the current hand without writing server inventory. Ordinary native item actions additionally retain complete source guards.
