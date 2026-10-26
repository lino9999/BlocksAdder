# Native 26.x player verification

The unchanged [development preview 6 JAR](https://github.com/lino9999/BlocksAdder/releases/tag/v1.1.0-dev.6) passed all three scoped player suites on seven 26.x server builds: **21 successful suite runs and 2,023 assertions**. Its SHA-256 is `e8f3de4be63d2e23844195c826ea39c0cf0e69445339c34be4431b017853cee6`, built from source commit `be58fde3e7cafcf1ec55567639a56dd3832c31f5`. No production changes were needed for these runs.

The [machine-readable report](native-26x-verification.json) records each server/probe checksum, actual client protocol, final log hashes and codec provenance. Together with this artifact's [previous 983 assertions](held-world-verification.json), there are 3,006 successful assertions in the stated scopes. These totals include API/data assertions and repeated scenarios on different endpoints; they are not counts of distinct native player interactions or proof of full functional parity.

## Executed matrix

| Endpoint | Java | Native protocol | Held/placed interactions | Held break/mining | Crop views |
| --- | --- | --- | --- | --- | --- |
| Paper 26.1.1 build 29 | 25 | 775 | 147 PASS | 56 PASS | 86 PASS |
| Paper 26.1.2 build 74 | 25 | 775 | 147 PASS | 56 PASS | 86 PASS |
| Spigot 26.1.2 | 25 | 775 | 147 PASS | 56 PASS | 86 PASS |
| Paper 26.2 build 134 | 25 | 776 | 147 PASS | 56 PASS | 86 PASS |
| Spigot 26.2 | 25 | 776 | 147 PASS | 56 PASS | 86 PASS |
| Paper 26.3 build 218 | 25 | 777 | 147 PASS | 56 PASS | 86 PASS |
| Spigot 26.3 | 25 | 777 | 147 PASS | 56 PASS | 86 PASS |

Each isolated, loopback-bound offline server loaded BlocksAdder and one temporary verification plugin. Two protocol clients connected directly using the endpoint's native protocol. No ProtocolLib, ViaVersion, translation proxy or production server was involved. The earlier report covers the same three suites on Spigot/Paper 1.21.11 with Java 21, as well as 45 core/bundle checks on all nine builds.

## Scope

- Held/placed suite: actual main/offhand block and managed helper/model packets, plus public API cases for shifted triggers, owned frames, hidden crops, cancellation, shared cooldown admission, exact source replacement and callback-safe placement, shortcut and bone-meal consumption.
- Held-break/mining suite: native start/abort dig requests, protected timed completion, custom/vanilla tools, tool wear, zero hardness, source validation, held break plans and delayed snapshots.
- Crop-view suite: two actual players, per-player selections, native entity spawn/removal state and reconnect, plus API/data cases for stage regeneration, distance/crowd/facing detail, commands, shared low budgets, unused-card reclamation and actual chunk unload/reload.

These are the same assertion sets described in [held interactions](held-world-interactions.md), [held break verification](world-actions-verification.json) and [crop views](crop-views.md). Other native-player subsystems and integration combinations retain their separate acceptance gaps.

## Private protocol tooling

The 26.1.x runs used `minecraft-protocol` 1.68.0 and `minecraft-data` 3.117.0. Their installed packet schema did not support 26.2/26.3, so those endpoints used a small private wire client implementing only the packets needed for these scenarios. It does not render or implement a complete Minecraft client.

Packet IDs came from `net.minecraft.data.Main --reports` on official Mojang server bundles. The cached bundle bytes matched the SHA-1 in Mojang's version metadata before report generation. Narrow inspection of official packet codecs established changed payloads; report/server hashes and primary metadata URLs are retained in the JSON report. This inspection and codec are private test tools. Production continues to use the public Bukkit/Spigot API without server implementation imports.

Initial fixture failures exposed protocol changes: modern attacks use a separate packet; 26.3 teleport acknowledgements include position/angles; movement batches end with `client_tick_end`; `punch` replaces `swing`; and a new dig-direction action shifts abort/stop enum values. The verification codec was corrected and the two failed 26.3 mining cases repeated successfully. No production condition or assertion was removed to obtain a pass.

## Remaining boundaries

The user owns visual checks. No graphical Minecraft client, launcher or screenshot automation was used. Pack rendering, perceived animation, crack appearance and every native input combination are not certified by this report. Full reconstruction remains active in the [parity tracker](parity-progress.md).

The supplied reference plugin was never executed. Original implementation/assets were not copied into the product. Temporary fixtures and servers remain in ignored local storage because automatic approval review previously rejected cleanup; they are absent from Git and release assets.
