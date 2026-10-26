# Projectile action plans

Item-bearing bundle records can define `traits.events.flight.launch`, `flight.block` and `flight.entity`. These independent BlocksAdder names select native launch, block collision and entity collision events. They use the ordinary ordered action engine, including permissions, statistics, cooldowns, flow, cancellation and bounded delays.

```yaml
format: blocksadder/bundle-1
bundle: tempest
entries:
  - key: spark_arrow
    kind: artifact
    item: {base: ARROW, label: Spark Arrow, variant: 12001}
    traits:
      events:
        flight:
          launch:
            play_sound: {name: minecraft:block.note_block.chime, volume: 0.5}
          block:
            play_particle: {name: FLAME}
            play_particle_late: {name: FLAME, delay: 10}
          entity:
            damage_entity: {damage: 3}
            target_potion_effect: {type: GLOWING, duration: 40}
            cancel: {}
```

The source is the native projectile's own item, independent of the shooter's current inventory. Bow/crossbow ammunition identifies arrow plans; `bow_shot` remains a separate weapon-item event. Changing hands, replacing held items or consuming the last throwable does not turn another inventory item into the projectile source. Native launch and consumption remain server operations; BlocksAdder does not create a second projectile or refund an item itself.

Configured damage uses the public `DamageSource` builder with the projectile as direct entity and the player as causing entity. Arrow, trident and thrown-projectile damage types preserve native protection and killer credit. Projectile effects do not masquerade as melee attacks from a newly held item. Configured explosions also retain the projectile entity as their source; native explosion protection still applies.

The public `AbstractArrow` and `ThrowableProjectile` item APIs cover arrows, spectral arrows, tridents, snowballs, eggs, ender pearls, experience bottles and thrown potions. Tipped arrows retain their native potion payload. A projectile without one of these item carriers, or without a player shooter, does not borrow a source from nearby hands. Gun/script providers remain separate integration work.

At launch, spatial actions use the retained launch point. A block impact anchors them to the hit block; an entity impact anchors them to the entity location and exposes a living victim to target actions when available. Particle actions use the projectile event anchor and retained flight direction. Other triggers keep their existing particle-origin behavior. World edits and configured damage still pass through their ordinary native/custom protection paths.

An impact retains an independent item receipt for delayed actions after a snowball or other native projectile disappears. This receipt cannot edit an unrelated inventory stack. A launch delay requires its projectile to remain valid. Pending work shares the ordinary 128-job player limit and is cancelled on reload/disconnect; unloaded anchor chunks are skipped without loading or generating them. Living targets must remain valid in the impact's world. A shooter moving to another world does not make another entity or inventory item become the source.

While an event source remains live, complete payload comparisons reject foreign changes between action steps. Known action writes update that source expectation. Reload invalidates an in-progress source before later steps. Item-property actions edit the projectile payload or the closed impact receipt, subject to native item normalization. Removing the last payload unit retires the source, removes its live projectile and prevents subsequent source actions. During launch this also cancels native spawning; source inventory conservation remains the native server's responsibility.

Cancellation exposes the actual Bukkit event. Pre-cancelled native events are ignored. An ordered action plan is a preparation callback: arbitrary effects already performed cannot be rolled back by a later listener. Native impact cancellation has the [public API's collision limits](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/event/entity/ProjectileHitEvent.html); it does not universally suppress block collision or separate explosion/potion events. Configure those native effects through their own protection hooks when applicable.

The retained static reference inventory identifies three projectile event families and the corresponding item-carrying entity types. The [official reference event list](https://wiki.itemsadder.com/adding-content/items/item-properties/events/events-list/) corroborates launch and block/entity impacts. These are feature requirements; the configuration, source receipts, lifecycle guards and public-API implementation were designed independently. The reference plugin is never executed or loaded by this module, and no reference configuration or implementation class is bundled.

## Verification

The [exact artifact report](projectile-actions-verification.json) records 118 targeted native/API/data assertions on each of nine Spigot/Paper builds, 1,062 total. It includes real native material/hand/Multishot inputs, scoped particle packet observations and separately identified API callbacks. The same JAR passed area-mining, conditional-change, player/environment and bundle regressions: 4,133 scoped assertions across 42 accepted server processes. The [distribution audit](projectile-actions-audit.json) retains empty collision lists across 358 own classes. Wider enchantment, pickup/restart, helper and named integration combinations remain open; rendering belongs to the user.
