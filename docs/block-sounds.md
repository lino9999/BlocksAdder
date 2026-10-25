# Block sounds

Custom blocks support placement, breaking, mining hits, footsteps and falls. Cues are emitted after committed player operations; rollback and silent API generation do not produce placement or break sounds. Hits reach the mining player in the MASTER category. Other cues use BLOCKS and a 16-block radius. Sneaking suppresses footsteps. Falls follow the original FALL damage trigger, including cancelled damage.

```yaml
block:
  sound:
    place: {name: 'blocksadder:stone.place', volume: 1, pitch: 0.8}
    break: {name: 'blocksadder:stone.break', volume: 1, pitch: 0.8}
    hit: {name: 'blocksadder:stone.hit', volume: 0.25, pitch: 0.5}
    step: {name: 'blocksadder:stone.step', volume: 0.15, pitch: 1}
    fall: {name: 'blocksadder:stone.fall', volume: 0.5, pitch: 0.75}
```

Names accept Bukkit sound names or namespaced sound event keys. An empty name or zero volume disables a cue. Volume must be finite and between 0 and 16; pitch must be finite, positive and at most 4. The legacy flat `place-sound`, `break-sound`, `hit-sound`, `step-sound` and `fall-sound` fields remain accepted. Missing cues default to the corresponding vanilla stone event with the values above. Custom fire reads explicit `fire.sound` or `fire.block-options.sound`; its legacy imported block sound section is normalized into `fire.sound`.

## Resource pack and optional protocol bridge

Only a working optional ProtocolLib bridge enables vanilla sound rewriting. The compiler creates ten `blocksadder:vanilla/{wood,stone}/{place,break,hit,step,fall}` aliases and silences those ten original events in the generated pack. Aliases independently reference installed Minecraft sample paths: four dig samples for placement/breaking and six step samples for the remaining actions. No original sound files or bundled vanilla audio are copied. This handles client-predicted carrier audio while the server supplies the configured custom cues.

Outgoing named and entity sound packets using those ten original keys are redirected to audible aliases. Receiver, location or entity, category, volume, pitch and available random seed are preserved. Stop-sound packets retain their original optional category and target flags. Unrelated and unregistered custom sound keys remain unchanged. Vanilla footsteps, falls, digging, placement and breaking are replayed where client prediction would otherwise use a muted event. Vanilla placement replays only to the actor because the native server packet already reaches observers. Protection cancellation suppresses deferred placement/breaking replays.

If ProtocolLib is absent or packet capability checks fail, vanilla events are preserved in the pack. Custom cue emission still works, although locally predicted carrier sounds can coexist with it. Changing bridge availability requires regenerating and delivering the resource pack so clients and server share the same alias policy. The plugin never installs or downloads ProtocolLib.

The compiler merges existing namespace sound files after copied assets. Existing unrelated definitions and explicit alias overrides survive. Additive overrides of vanilla events retain native samples; replacement overrides retain their replacement samples. Event references inside moved definitions are redirected to the corresponding audible alias. Audio files for user-defined events remain the content author's responsibility.

## Architecture and evidence

`BlockSoundService` is the server-thread lifecycle and event service. `ContentSoundSink` connects committed world and mining operations without coupling them to protocol details. `ProtocolLibSoundProtocol` uses only the optional public SDK and is loaded behind `SoundProtocol`; `rewritesEnabled()` is safe for a captured pack-build decision. `SoundAssetCompiler.append(entries, registry, rewriteAllowed)` performs pure resource compilation on the pack worker. Reload closes old packet listeners and pending tasks before publishing validated profiles.

Static observations come from supplied JAR SHA-256 `cedc3ff88d57fb303e865b017d8e090da0f9f203d3922ff7887e27342f0c053d`: `si` cue parsing; `rf`/`sg` defaults, radius and categories; `lj` ten vanilla sound replacements; `lk` protocol rewriting; and ambient event listeners. The original JAR was never executed and its implementation or audio assets were not copied. Native sample names were checked independently against cached Mojang sound asset indexes. The implementation preserves the observed sound model while fixing global silencing without a usable bridge, packet conversion failures on unregistered Paper sounds and nullable stop category narrowing.

## Verification

Final isolated checks on 2026-10-25 used the same complete JAR member payload on every endpoint:

| Endpoint | ProtocolLib | Scope | Passed | Failed |
| --- | --- | --- | ---: | ---: |
| Spigot 1.21.11 | 5.5.0 snapshot | Two real protocol clients, compiler and server events | 36 | 0 |
| Paper 1.21.11 build 132 | 5.5.0 snapshot | Two real protocol clients, compiler and server events | 36 | 0 |
| Spigot 1.21.11 | Absent | Two real protocol clients and non-muting fallback | 28 | 0 |
| Paper 26.3 build 218 | 5.5.0 snapshot | Adapter packet-layout capability, compiler and validation | 8 | 0 |

No final sound adapter exceptions appeared. Real wire checks cover third-party named/entity sounds, preserved seed/category/position/volume/pitch, unregistered custom sounds, stop packets with and without categories, category-only stops, all five custom cues, sneaking, ordinary vanilla digging/placement/breaking and cancellation. The true absent-category fixture uses a public SDK packet because Bukkit's one-argument stop method already chooses MASTER. Paper 26.3 checks establish API linkage and adapter self-tests; no 26.3 client wire or listening result is claimed.

All final sound processes used the existing Java 25 runtime and Java 21 bytecode. The host OS had begun rejecting the existing Java 21 executable with AccessDenied; no host security settings were changed. Earlier independent core and module runs established Java 21 startup. Exact hashes, SDK variants and logs are retained in ignored `.work/sound-results.json`. Later root changes require the final combined artifact's regression. The user owns rendered pack and audible Minecraft acceptance; automated packet checks establish event names and parameters without claiming listening tests. Temporary server/client fixtures remain ignored under `.work/`.
