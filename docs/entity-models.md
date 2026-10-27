# Skeletal entity and player models

Model definitions live in a namespaced content file under `models`. The runtime uses public Bukkit `ItemDisplay` entities for drawable bones and an `Interaction` hitbox. It keeps a parent-ordered transform graph, evaluates animation tracks and follows a target entity. Core rendering has no packet-library dependency; orbit cameras use the optional public ProtocolLib API.

## Blockbench source

```yaml
namespace: my_pack
models:
  robot:
    file: contents/my_pack/models/robot.bbmodel
    scale: 1.0
    view-range: 48
    hide-base: true
    self-visible: false
    hitbox:
      width: 0.8
      height: 1.8
```

Paths are relative to `plugins/BlocksAdder/`, must remain inside that directory and must name a regular `.bbmodel`, `.iaentitymodel` or `.player_animations` file no larger than 16 MiB. Symbolic links and traversal outside the data directory are rejected.

The direct Blockbench importer reads cube/locator elements, outliner groups, pivots, static rotations, faces/UVs, embedded PNG textures, animation tracks and loop modes. Every cube becomes a drawable child bone. Locators retain position/rotation without generating geometry; locator-only models need no textures. Groups retain their parent-child hierarchy; individual cube rotations are applied in the runtime transform graph, avoiding Java model element rotation restrictions. Large elements are normalized into valid Java geometry coordinates and scaled back in the display transform.

Embedded PNGs are generated under `assets/<namespace>/textures/item/blocksadder_models/...`; each cube gets a model JSON and a modern item-model wrapper. The pack compiler adds these before administrator-supplied assets, allowing explicit resource files to override generated defaults. Embedded texture headers/dimensions are checked, and a texture is limited to 4096×4096. External texture files can be mapped through `textures.<index>: namespace:item/path` instead of embedding them.

Supported animation interpolation is `linear`, `step`, `catmullrom` and `bezier`. Position values use Blockbench pixels, rotations use degrees and scale values are multipliers. Tracks are evaluated at server tick time; hierarchy matrices compose pivot translation, base/animated rotation and scale. Blockbench transform data points retain separate incoming/outgoing values. Per-axis Bezier handles use time/value offsets and bounded numerical time inversion. Unknown interpolation/channels fail validation. Mesh geometry is not implemented.

Numeric Molang expressions in YAML vector values and Blockbench data points are compiled once into an independent bounded expression tree. Arithmetic, comparisons, short-circuit Boolean operators, ternary conditions, null coalescing, variable/temp aliases, assignments, return statements, blocks and bounded `loop`/`break`/`continue` are supported. Trigonometric functions use degrees. Math includes interpolation, angle normalization, random/dice functions and the sine/quad/cubic/quart/quint/expo/circ/back/elastic/bounce easing families in their in/out/in-out forms.

Supported queries expose animation/lifetime/delta time, health, water/rain and grounded state, player movement flags, age, riding state, horizontal/vertical/yaw speed, tracked horizontal distance, yaw/pitch, time of day and moon phase through public Bukkit state. `query.modified_move_speed` uses horizontal blocks per second and `query.modified_distance_moved` accumulates tracked horizontal displacement, excluding per-tick jumps over 16 blocks. Head/body yaw use the public target yaw. These Java-server query mappings are explicit; this is not a complete Bedrock engine or a guarantee of identical private Bedrock locomotion state. Unknown query names and unsupported string/array/structure operations fail validation.

Each rig owns its variables and previous pose for `this`. Integrations can use `ModelService.variable(rig, name, value)` and `variables(rig)`. Expressions are limited to 4096 characters, 2048 tokens, 512 syntax nodes, 64 nesting levels and 4096 evaluated operations. Loops and dice are bounded to 1024 iterations; a model owns at most 256 variables. Numeric results remain finite and bounded. Constant models skip live expression-query collection. Syntax and handle conventions follow the public [Blockbench keyframe schema](https://github.com/JannisX11/blockbench/blob/master/js/animations/keyframe.js); numeric language behavior follows Microsoft's [Molang syntax](https://learn.microsoft.com/en-us/minecraft/creator/documents/molang/syntax-guide?view=minecraft-bedrock-stable) and [math reference](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/molangreference/examples/molangconcepts/mathfunctions?view=minecraft-bedrock-stable).

## YAML-defined rigs

An alternative rig can reuse existing items or item-model assets:

```yaml
namespace: my_pack
models:
  robot:
    bones:
      body:
        item: my_pack:robot_body
        origin: [0, 8, 0]
      arm:
        parent: body
        model: minecraft:stick
        origin: [8, 12, 0]
        rotation: [0, 0, 0]
        scale: [1, 1, 1]
      mount_driver:
        parent: body
        origin: [0, 16, 0]
        mount: true
    animations:
      wave:
        length: 1.0
        loop: once
        bones:
          arm:
            rotation:
              - time: 0
                value: [0, 0, 0]
              - time: 1
                value: [0, 0, 90]
```

Origins are absolute rest-pose pivots in pixels; the loader calculates the local parent offset. A bone may reference `item` for a registered custom item or `model` for an existing modern item-model ID. For vanilla item models, use IDs such as `minecraft:stone`, rather than the model-geometry path `minecraft:item/stone`. A bone without either visual reference remains a transform/mount pivot.

Animation channels `position`, `rotation` and `scale` accept ordered records with `time`, three-value `value` and optional `interpolation`. Times are seconds. Loop modes are `once`, `loop` and `hold`. Definitions reject cyclic/missing parents, invalid numbers, duplicate channel times, unknown animation bones and frames beyond their declared duration. Limits are 512 bones per model, 256 animations and 4096 samples per channel.

## Legacy exported model import

```yaml
namespace: my_pack
models:
  imported:
    file: contents/my_pack/models/robot.iaentitymodel
    legacy-model-root: my_pack:entity/robot
    bone-models:
      body: my_pack:entity/robot/body
```

The supplied reference artifact's `itemsadder/m/ec` parser and `dx`, `ea`, `dt`, `ej`, `adq` runtime classes were inspected statically. They establish the exported fields `bones` (`name`, `boneType`, `parents`, `pos`, `rot`, `scales`) and `animations` (`name`, `loopMode`, `length`, optional loop start/end, per-bone `keyframes`, `pos`, `rot`, `scale_str`). The reference indexes exported pose arrays at 20 samples/second, divides rest bone positions by 16 and uses YXZ rotation order. Frame positions are added directly to world positions by the observed runtime.

The importer therefore treats these exported samples as absolute poses in world units, preserves their 20 Hz timing and YXZ rotations, and supports normal/head/pivot/mount/locator bone types. It does not reapply a parent animation to an already baked absolute pose. Legacy metadata contains no cube geometry. Supply the accompanying geometry and textures in your own resource-pack assets; the compiler creates modern item wrappers referencing `namespace:entity/<source-stem>/<bone>` by default. `legacy-model-root` and `bone-models` can override that convention. No original code, shaders or bundled model assets are incorporated.

Precise original player pose correction offsets remain under parity work. The new renderer supplies display scaling directly instead of selecting original exported scale variants. Importing the metadata is narrower than verifying identical rendering of every original exported model.

Observed legacy camelCase roles (`hatPivot`, `leftHandPivot`, `rightHandPivot`), `mount`/`locator` names and empty normal roles are normalized and retained. Bind-pose positions and frame positions both use the export's world units. Equipment pivots do not generate references to nonexistent static geometry. Legacy geometry authored for armor-stand HEAD display context and the original private correction offsets still require explicit parity work; these parser fixes do not certify identical visuals.

## Player equipment and head tracking

YAML bones can select `role: head`, `hatPivot`, `leftHandPivot` or `rightHandPivot`. Equipment pivots mirror cloned target equipment and cannot also select a static item/model. Left/right pivots follow the player's physical main-hand preference. Inventory changes update the displayed clones; tint/glint changes preserve the underlying native inventory. Hand displays use their corresponding third-person item contexts. Head bones follow public player pitch/yaw, bounded by `max-head-rotation-x` and `max-head-rotation-y` (defaults 70 and 45 degrees). Legacy `maxHeadRotX`/`maxHeadRotY` use the same limits. Child locators and hat pivots follow the resulting head matrix. Independent private body/head yaw is unavailable through the public Bukkit pose API.

Armor helmets select independently generated six-face geometry using the equipment family's humanoid armor UVs, rather than the flat inventory icon. Native and authored equipment layers, leather dye, overlays, model tint and glint retain separate rendering data. The rendering clone carries its trim-pattern selector; the native item, dye, trim and equipment component remain unchanged. Generated item models include the eight vanilla helmet families, all 18 vanilla trim patterns and their palettes, including same-material darker variants. Authored `equipments` and native `assets/<namespace>/equipment/<family>.json` humanoid layers feed the same compiler. Custom datapack trim materials/patterns and 26.3-specific equipment `trim_overrides` still require separate support.

The compiler preserves existing item-atlas sources. Base atlas palette paths support 1.21.11 through 26.2. A format-97 overlay supplies the new palette identifiers used by 26.3, preserving existing metadata, overlay registrations and applicable authored item-atlas sources. It copies authored palette pixels into their new directory when needed. This follows Mojang's [26.3 palette changes](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3) and [pack version/overlay rules](https://www.minecraft.net/en-us/article/minecraft-snapshot-25w31a). Generated geometry, palette selection and server metadata are testable without a graphical client; alignment, lighting and rendered armor/trim appearance remain user visual checks.

## Live player skins

```yaml
namespace: my_pack
models:
  player_rig:
    view-range: 48
    bones:
      head: {skin-part: head, origin: [0, 24, 0]}
      body: {skin-part: body, origin: [0, 24, 0]}
      left_arm: {skin-part: left_arm, origin: [5, 22, 0]}
      right_arm: {skin-part: right_arm, origin: [-5, 22, 0]}
      left_leg: {skin-part: left_leg, origin: [1.9, 12, 0]}
      right_leg: {skin-part: right_leg, origin: [-1.9, 12, 0]}
```

Skin parts use the target player's public `PlayerProfile` by default. A nonplayer target without a configured skin uses the game's built-in wide Steve texture. The optional model-level `skin` field accepts an HTTPS `textures.minecraft.net/texture/<hash>` URL or a bounded Base64 Minecraft texture property. A Base64 property retains `metadata.model: slim`. Ports, credentials, query strings, fragments, other hosts/protocols and oversized properties are rejected. The server does not fetch/upload skin images or invoke profile updates. URL-bearing profiles use public `SkullMeta`; texture-less profiles use generated special models pointing to the matching built-in slim/wide default texture. This avoids the name-completion lookup documented by the public [SkullMeta contract](https://hub.spigotmc.org/javadocs/bukkit/org/bukkit/inventory/meta/SkullMeta.html#setOwnerProfile(org.bukkit.profile.PlayerProfile)). No Minecraft textures are included in this plugin.

`/ba model skin <rig-UUID> <player|texture|reset>` and `ModelService.skin`/`resetSkin` replace or reset a rig's source. The public integration API offers `modelSkin` and `resetModelSkin`. Texture and UUID-only default overrides persist on the target and survive reload/restart. Emote state restoration preserves the previous override. Changes to a target's already available profile are checked once per second; this does not request a network profile update.

Each part has two directly owned profile-head displays for its base and outer layer. Arms use the profile's classic/slim width, while head and clothing overlays retain their independent vanilla dilation. The shader remaps native head-face UVs to the chosen body-part region. The six observed exported names (`phead_0`, `pbody_2`, `parm_left_3`, `parm_right_4`, `pleg_left_1`, `pleg_right_5`) select these parts during legacy import; they require no original static model files. Skin bones cannot simultaneously select a static item/model, an equipment pivot or a mount role.

The pack adds independent entity shaders only when a skin model exists. Separate overlays supply the interfaces used by 26.1/26.2 and 26.3; existing helmet palette and user overlay registrations remain present. Conflicting authored core entity shaders cause an explicit pack-build error. Owned skin geometry carries a bounded transform marker, restored before projection and fog calculations; native entity positions, hitboxes, locators, range checks and culling positions remain at the target. Skin bone translations and effective scale are bounded to 64 to preserve that encoding across the supported 256-block maximum viewer range. This path needs no ProtocolLib dependency and contains no Minecraft-internal imports.

Rendered appearance remains a user check. Exact legacy player pose retargeting, skin tint/glint behavior, custom datapack trim extensions and interoperability with separately authored core shaders remain open parity requirements; the server/protocol and shader-compiler checks below do not establish those as complete.

## Skeletal player emotes

```yaml
namespace: my_pack
emotes:
  dance:
    model: my_pack:player_rig
    animation: dance
    end-animation: dance_end
    can-player-move: false
    third-person-view: true
    hide-equipment:
      helmet: true
      mainhand: true
      offhand: true
    track-player-rotation:
      head: true
      body: false
    cancel-conditions:
      sneak: true
      take-damage: true
      deal-damage: true
      in-air: true
    on-stop:
      stop-sounds: true
      skip-emote-end: false
    background-music:
      sound:
        name: my_pack:dance_music
        category: MUSIC
        volume: 1
        pitch: 1
      repeat-interval-seconds: 10
```

The observed underscore aliases are accepted. Legacy `id: namespace:animation` resolves an animation in one unambiguous imported model. Explicit `model`/`animation` bindings resolve before registry activation. A configured end clip is optional; otherwise `<animation>_end` is inferred when present. `duration-seconds` can bound a looping emote. Optional `speed.movement`/`speed.flying` use Bukkit's range of -1 through 1, and `allow-flight` temporarily grants flight. The default permission is `blocksadder.emote`. A definition cannot mix a skeletal binding with `frames`; frame emotes remain handled by the gameplay timeline.

Starting an emote saves the prior model attachment/disguise, animation time/loop, variables, colors, glints, viewer exclusions, rotation tracking and pose history. Stop, natural completion and configured cancellation restore that state. Movement locks cancel actual position changes while permitting look updates. Accepted sneak/damage/attack/air conditions can run the end clip; teleport, game-mode changes, death, quit, reload and disable restore immediately. Existing walk/fly speeds and flight flags are restored with ownership checks so later changes by another plugin are preserved.

Camera and equipment masking require a matching optional ProtocolLib build and report an actionable error when absent. Equipment hiding rewrites outbound inventory/equipment packets and never edits the native inventory; held-slot changes refresh the hidden slot and restore the former slot. Ending a camera performs a guarded resync so an emote configured to ignore damage can continue after damage closes its camera. Background music repeats at its configured interval and stops for recorded listeners when requested. `/ba emote <id|list|info|stop|stop-now> [player]` and the public API use this controller; unknown skeletal IDs fall through to an attached rig clip or a frame emote.

## Animation effects

Legacy `effects.sounds.keyframes` and `effects.particles.keyframes` contain arrays of effect lists, one list per 20 Hz frame. Sound fields are `name`, `volume` and `pitch`; particle fields are `name`, `locator`, `speed`, `count`, `x_delta`, `y_delta` and `z_delta`. Particle locators resolve animated bone positions. Blockbench effect animators support `sound`, `particle` and numeric `timeline` script channels, resource names in `effect` or `name`, and named/UUID locators. Particle pre-effect `script` values update the same isolated rig variables before emission. Files selected in a Blockbench editor must have a resource effect identifier configured; arbitrary local audio files are not imported.

Canonical YAML animations can define an `effects` list:

```yaml
effects:
  - time: 0
    type: sound
    name: minecraft:entity.cow.ambient
    volume: 0.8
    pitch: 1
    category: MASTER
  - time: 0.25
    type: particle
    name: minecraft:dust
    locator: hand
    count: 4
    speed: 0
    x-delta: 0.05
    y-delta: 0.05
    z-delta: 0.05
    data:
      color: ff8800
      size: 1
  - time: 0.5
    type: script
    script: 'v.offset = 16;'
```

Effect times are seconds. Sound and particle ranges are respectively 64 and 32 blocks; explicit per-viewer exclusions apply. Starting another animation, stopping, natural completion, detaching and shutdown stop sounds for their recorded recipients. Hold animations do not replay effects while holding. Loop start/end crossings retain each scheduled cue once per crossing. A cancellable `ModelEffectEvent` exposes the rig, model, animation, target, effect and computed location before dispatch.

Particle data supports colors, dust size/transition colors, public spell power, block data, vanilla item materials, numeric values, and relative trail/vibration targets with bounded durations. Defaults provide valid white dust/colors, stone block/item particles and bounded timing. Unknown particles, unknown locators, invalid values and unsupported public data types fail validation. Animation effects are limited to 4096 cues, particle counts to 256 per cue and particle spread/speed to 16 blocks. This implementation independently reconstructs observed parser/timeline behavior and fixes invalid missing typed-particle data.

## Orbit cameras

`ModelService.camera(rig, viewer, true)` starts a four-block orbit; the `double` overload chooses a distance between one and sixteen blocks, and `camera(rig, viewer, false)` exits. `cameraPosition(viewer)` returns the active anchor location. `cameraAvailable()` and `cameraReason()` expose the optional capability. Install the official [ProtocolLib build matching the server](https://github.com/dmulloy2/ProtocolLib#readme) to use cameras; the core model system still loads without it.

The adapter uses public ProtocolLib CAMERA, MOUNT and game-state packets. The spectator view is client-only: server game mode, inventory and native passenger relationships remain unchanged. Accepted look packets orbit the private anchor while position packets are suppressed, matching the observed fixed-origin camera behavior. A block ray clips the camera before walls. Other players receive neither its private anchor nor camera/passenger/game-mode packets. Camera ownership temporarily enables the model's helpers for its owner, then restores the normal self-visibility setting.

Damage, teleport, world change, death, quit, sneak, explicit exit, rig removal, reload and disable restore camera state and remove the temporary anchor. A pending camera start is cancelled if its rig is removed before the next tracking tick. Camera setup waits one tick so its spawn reaches the client before CAMERA. The implementation contains no Minecraft-internal imports or reflection.

## Lifecycle and API

`ModelService` exposes model/animation/bone lookup, `attach`, `play`, `detach`, `disguise`, `undisguise`, per-viewer visibility, bone color/glint, mount passengers and mount locks. Model operations require the server thread. The public Bukkit service API/commands are composed by the main plugin.

Target entities retain a `blocksadder:model_id` or `blocksadder:disguise_id` tag. Helpers are nonpersistent and owned by direct references, including before Bukkit's global UUID index sees newly spawned entities. Chunk/entity unload, reload, target death/quit and plugin disable remove helpers and restore base-player visibility. Existing attachment tags allow restoration after entities load or after a server restart. Explicit detach removes the tag. Temporarily absent model definitions are retained as metadata so a content pack can be restored later.

Player disguises use plugin-scoped `hidePlayer`/`showPlayer` for other viewers; this avoids changing other plugins' independent visibility decisions. The owner sees their normal first-person avatar unless `self-visible` is enabled for the model's helpers. The renderer updates viewer membership by world, range and explicit exclusions. Fixed Blockbench textures and the live-profile skin path above are separate supported inputs.

Entity damage triggers a short red tint on generated model geometry and optionally a `hurt` animation. A `death` animation may finish after a nonplayer target dies; player death restores the original player immediately. Hitbox attacks delegate damage to the underlying living entity, preserving its Bukkit damage event. Generated geometry supports custom-model-data tint colors; arbitrary external model assets need their own compatible tint sources.

The runtime uses one shared tick task while rigs exist, and enforces `models.max-active-rigs` (default 128) and `models.max-total-bones` (default 4096). It does not load distant chunks to animate them. Mount pivots can accept passengers and lock voluntary dismounts. Controlled vehicle movement, fuel and player steering have their own implementation and verification scope in [vehicles.md](vehicles.md).

## Verification boundary

Pure Java loader/matrix tests passed 17 assertions covering Blockbench hierarchy/asset output, interpolation, pixel-to-block transforms, deterministic assets, conflicts/traversal/cycles and legacy 20 Hz absolute poses. Runtime server checks are recorded separately after executing the isolated model probe. A headless server can verify entity lifecycle and transforms; it cannot certify model visuals, skin appearance, player disguise visibility, first-person behavior, seating or client animation smoothness without a connected Minecraft client.

The isolated model probe subsequently passed four actual server processes:

| Runtime | First process | Complete restart |
| --- | --- | --- |
| Spigot 1.21.11, Java 21 | 31 checks, zero failures | 34 checks, zero failures |
| Paper 26.3, Java 25 | 31 checks, zero failures | 34 checks, zero failures |

These checks attached rigs to real cows, verified ItemDisplay/Interaction entities and persistent tags, evaluated moving keyframes and target following, applied tint/glint, exercised same-tick detach/reattach and reload deduplication, attached a legacy exported rig, inspected generated Blockbench/legacy pack entries, triggered real damage/death cleanup and restored a persistent target attachment after restarting the process. Fixture chunks were held resident through test-owned tickets. Model geometry rendering and all actual-player disguise/mount interactions were explicitly `NOT_RUN`.

The sound/particle runtime candidate `d6f644b67a95be7ae4ade876df7e51ba3f597d949213608c01b7bb7f093fdc08` passed 15 additional server assertions on Spigot 1.21.11 and 15 on Paper 26.3. These verify effect timing, wrap/hold/once behavior, terminal cues, locator coordinates, cancellation, explicit stop/detach and both import paths. Typed particle options validated on both actual APIs. They do not establish sound/particle network reception without connected players. Subsequent pure checks pass 17 baseline, 23 effect/import and 119 expression assertions.

The scoped interpolation/script build `66c97651105dcc07562319500107b597379de79c827aec848c8512e3c9c9c9e0` passed 15 actual assertions on Spigot 1.21.11 and 15 on Paper 26.3. Native coordinates respond to health, variables and animation time; `this` changes display scale; the Bezier quarter-time cue matches its analytic coordinate; timeline/pre-effect scripts loop, respect cancellation and stop with playback. The later complete model module `24dd58ba3ea666b00a1cd75ec26b92c106f7cc9e68c0d08924453259dc5e275c` passed 17 assertions on each endpoint with ProtocolLib absent, including the optional-dependency behavior.

Camera build `b20d15b7287bb876ba1c197c84462dc3d299c0e56d039fba1e2e6ec098aaa4c6` passed 37 native protocol-player checks on Spigot 1.21.11 and 37 on Paper 1.21.11 using the matching official ProtocolLib-Spigot development build. Five starts each arrived after their anchor spawn; private camera/mount/view-mode packets never reached the observer. Native look, suppressed movement, wall clipping and damage/teleport/detach/reload/quit restoration passed. The scoped builds contain independently compiled model classes over their documented combined-build base; final release artifacts still require the normal full Maven build. All rendered visual checks remain reserved for the user.

Emote/equipment build `0ca2e7c2c7d57488326b60f0b5cf2e70e0aeca59cc3c364abe5a3a4ea47ea75d` passed 73 native protocol-player checks on Spigot 1.21.11 and 73 on Paper 1.21.11, both on Java 25 with Java-21-targeted plugin classes. It layers the independently compiled model module over combined build `2bee4cb7cc832b3e5b72e3ea8d40088e12638f91f09d16ec162ff8742d3e34c8`. Real look, movement, held-slot and disconnect packets verified head clamps, movement locks, client inventory masks, old/new held-slot restoration and quit cleanup. Native inventory, helmet dye, trim and equipment components remained unchanged. Prior animation/variables/tint/glint, natural/graceful stops, speed/flight restoration, teleport/reload and the camera/emote cancellation interaction passed. Generated vanilla/custom helmet model IDs, clone-only trim selectors and noncompounding dye/tint passed. The frame-emote and existing-rig fallback regressions passed through the production root wrapper. Separate pure checks pass 13 emote-schema and 22 equipment/overlay assertions. This scoped runtime artifact predates the final 26.3 atlas-overlay addition; that overlay has the separate pure-data evidence above. Rendered helmets/skins, sound/particle reception and custom datapack trim extensions remain outside these assertions.

Full combined build `6249a35fff74b80454e6cc65aa858e510beb532e6c32c2c11f647ae803a9dd6b` passed 249 skin assertions in six actual server processes on Java 25: Spigot 1.21.11 (46 and 49), Paper 1.21.11 (49 and 49), and Paper 26.3 without ProtocolLib (28 and 28). Native protocol players were connected on both 1.21.11 servers; the 26.3 checks were headless server/API checks. The second process on every endpoint was a complete JVM restart. These checks cover 12 directly owned layers, native profile/default-model and transform packets, slim/outer dimensions, target following, persistent URL/UUID sources, same-tick helper removal, the skin command/public API, legacy part inference, actual pack entries and emote/disguise/reload/quit restoration. Fixed fallback runs produced no name-completion HTTP lookup warnings.

The current pure suite passes 275 assertions (17 baseline, 23 effects/import, 119 expressions, 13 emotes, 22 equipment/overlays and 81 skin/profile/matrix/UV checks). The official Khronos glslang 16.6.0 compiler also compiled and linked all 43 tested shader version/macro variants against the actual includes from Mojang's SHA-1-verified 1.21.11, 26.1, 26.2 and 26.3 client archives. The official shader variants passed the same 43 fixture controls, including 26.3 glint, dissolve and OIT phases. All 18 referenced built-in default texture resources exist in each archive. These were static asset/GLSL checks; no graphical client was launched and no rendered appearance is certified.

## Native camera input compatibility

Preview 13 separates direct, Bukkit-free receiving input from server-thread equipment masking. Camera input is owned by the exact player session and its atomic rotation; stop clears it even when the player is offline. Thirty-seven native camera checks pass on each of nine supported server builds, including Paper 26.3. Skeletal emote regressions pass on both 1.21.11 forks. See [design and limits](camera-input.md) and [exact candidate results](camera-input-verification.json). The user checks rendered camera, avatar, skin and equipment appearance.
