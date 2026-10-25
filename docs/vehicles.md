# Vehicles

Vehicles use public Bukkit entities, a shared bounded update task and native `PlayerInputEvent` controls. Configure a namespaced `vehicles` entry and select an item visual or a skeletal model. A custom item can place that vehicle with `vehicle: namespace:id`.

```yaml
namespace: workshop
items:
  car:
    material: MINECART
    display-name: Workshop Car
    max-stack-size: 1
    vehicle: workshop:car
vehicles:
  car:
    mode: LAND
    item: workshop:car
    max-speed: 8
    reverse-speed: 4
    acceleration: 8
    braking: 20
    coast-friction: 0.92
    turn-rate: 120
    jump-speed: 8.4
    step-height: 0.6
    seat-height: 0.8
    small: true
    look-steering: true
    fixed-rotation: false
    sprint-multiplier: 1.5
    permission: blocksadder.vehicle.ride
    hitbox:
      width: 0.8
      length: 1.2
      height: 1.2
    fuel:
      capacity: 100
      start: 50
      per-second: 1
      items:
        minecraft:coal:
          amount: 10
        workshop:fuel_can:
          amount: 25
          return-item: minecraft:bucket
    smoke:
      amount: 2
      offset:
        x: 0
        y: 0.2
        z: -0.8
```

Remove the fuel-can entry unless that custom item exists. An omitted fuel capacity means unlimited fuel and exposes `-1` through the API. Capacity and start are fuel units; consumption is fuel units per second of powered movement intent. Fuel items cannot overflow the tank. Dropping and placing a vehicle retains its fuel.

Canonical speed, reverse speed, jump speed and vertical speed use blocks per second. Acceleration and braking use blocks per second squared. Turn rate uses degrees per second. Dimensions, seat height, draft and offsets use blocks. Friction is a per-tick multiplier between zero and one. All values are finite and bounded during registry validation.

`LAND` uses native gravity and collision. `AIR` disables gravity while powered, uses `vertical-speed` for jump/sneak ascent and descent, and descends slowly with an empty tank. `WATER` requires water beneath the carrier and follows its surface with `water-draft`, default `0.1`. Model visuals use `model: namespace:model` instead of `item`; see [entity-models.md](entity-models.md). `fixed-rotation` keeps the visual's original yaw while the carrier steers.

Right-click the vehicle to mount or use a configured fuel item on it to refuel. Forward/backward control acceleration, sprint increases the target speed, and look direction steers by default. Set `look-steering: false` to steer with left/right. Land vehicles jump once per key press. Sneak normally exits; air vehicles default to sneak descent and jump plus sneak permits exit. The API and command dismount route explicitly exits any mode.

`blocksadder.vehicle.place`, `blocksadder.vehicle.ride` and `blocksadder.vehicle.break` govern placement, riding and removal. A sneaking attack removes a vehicle when permitted. Placement raises native `EntityPlaceEvent` and cancellable `VehicleActionEvent`. Integrations must cancel `VehicleActionEvent` for custom movement and fuel restrictions; its actions are `PLACE`, `MOUNT`, `MOVE`, `REFUEL` and `REMOVE`.

The persistent carrier retains the vehicle ID, fuel and initial visual yaw. Temporary visuals and seats are rebuilt on chunk load and reload, and removed on unload or shutdown. Riders are detached on removal, quit and service shutdown. Updates only inspect tracked loaded carriers and collision blocks near each carrier. They do not load destination chunks. `vehicles.max-loaded` in the plugin configuration bounds the active set, default 256 and maximum 4096.

`VehicleService` exposes `spawn(Location, id)`, `mount(Player, carrierUUID)`, `dismount(Player)`, `remove(carrierUUID)`, fuel getter/setter, tracked identity lookup, definition IDs and inspection. Operations require the server thread. `identify` accepts carriers and their temporary helper seats. `control` is a trusted API input override with a short heartbeat expiry; ordinary players are controlled through the native input event. A vehicle carrier keeps native AI physics enabled while disabling awareness, so vanilla goals do not compete with steering. Damage remains cancellable through Bukkit; the service cancels accepted damage and handles permitted removal separately.

## Legacy vehicle configurations

The importer recognizes `behaviours.vehicle`, `specific_properties.vehicle` and a structured item `vehicle` section. It derives a vehicle with the item's namespaced ID and uses the same item as its visual. A canonical and legacy definition cannot share an ID.

Observed original fields are mapped as follows:

| Original field | Canonical behavior |
| --- | --- |
| `speed.drive` | Forward velocity multiplied by 20; reverse velocity multiplied by 10 |
| `speed.jump` | Jump velocity multiplied by 20 |
| `speed.fly` | Positive values select AIR and set forward/vertical speed after multiplication by 20 |
| `step_height`, `sit_height` | Step and seat heights |
| `small`, fallback `small_hitbox` | Small item visual |
| `fixed_rotation` | Fixed visual yaw |
| `hitbox.length/width/height`, fallback `size.x/y/z` | Collision dimensions |
| `fuel.max`, `fuel.start` | Capacity and initial fuel |
| `fuel.items.<id>.fuel_amount` | Fuel amount |
| `fuel.items.<id>.return_item` | Returned container |
| Scalar `fuel.items.<id>` | Fuel amount |
| `smoke.amount`, `smoke.offset.x/y/z` | Exhaust particles |

Original velocity units were established from static parser and control-flow inspection of the supplied artifact. Implementation and art were independently authored. The new controller adds acceleration and braking; this is not a claim of identical original frame-by-frame vehicle physics.

## Verification status

The isolated server probe passed 41 checks in each of four processes: Spigot 1.21.11 with Java 21, a complete Spigot restart, Paper 26.3 with Java 25, and a complete Paper restart. Checks include actual displacement in all three modes, air ascent, flotation, first-cancelled-event onward movement protection, wall collision, empty-fuel stopping, original schema conversion, native damage-event cancellation without health loss, reload deduplication, actual chunk unload/load restoration and persistent identity/fuel across restarts. Each mode moved 1.32 blocks during the initial twelve controlled ticks. The tested artifact SHA-256 was `8406fdf0b8c01606b3d75b4bae947609c22d5edd57a1bb6150ed655c06f77adb`. These tests use the trusted API input route; native protocol input is verified separately.

The combined Spigot protocol-client scenario subsequently passed 74 checks, including native input displacement, native passenger following, seat height, fuel, target-filtered movement protection, protected sneak attacks and permitted vehicle removal. Paper protocol-client results are recorded in the overall verification report when completed. Rendered visuals are reserved for the user's own Minecraft client checks. Temporary test evidence stays ignored locally because automatic approval review rejected its deletion; cleanup is pending.
