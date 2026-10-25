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

Dynamic player-skin shaders, special hand/hat equipment slots, original pre-generated scale variants and precise original player pose correction offsets remain unimplemented. The new renderer supplies display scaling directly instead of selecting exported geometry variants. Importing the metadata is narrower than verifying identical rendering of every original exported model.

Observed legacy camelCase roles (`hatPivot`, `leftHandPivot`, `rightHandPivot`), `mount`/`locator` names and empty normal roles are normalized and retained. Bind-pose positions and frame positions both use the export's world units. Equipment pivots do not generate references to nonexistent static geometry. Legacy geometry authored for armor-stand HEAD display context and the original private correction offsets still require explicit parity work; these parser fixes do not certify identical visuals.

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

Player disguises use plugin-scoped `hidePlayer`/`showPlayer` for other viewers; this avoids changing other plugins' independent visibility decisions. The owner sees their normal first-person avatar unless `self-visible` is enabled for the model's helpers. The renderer updates viewer membership by world, range and explicit exclusions. Fixed Blockbench skin textures work as ordinary model textures. Fetching/adapting arbitrary live player skins is not implemented.

Entity damage triggers a short red tint on generated model geometry and optionally a `hurt` animation. A `death` animation may finish after a nonplayer target dies; player death restores the original player immediately. Hitbox attacks delegate damage to the underlying living entity, preserving its Bukkit damage event. Generated geometry supports custom-model-data tint colors; arbitrary external model assets need their own compatible tint sources.

The runtime uses one shared tick task while rigs exist, and enforces `models.max-active-rigs` (default 128) and `models.max-total-bones` (default 4096). It does not load distant chunks to animate them. Mount pivots can accept passengers and lock voluntary dismounts. Controlled vehicle movement, fuel, precise animated-seat following and player input steering remain separate unverified work; this API does not establish those features as complete.

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
