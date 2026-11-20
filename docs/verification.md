# Verification

## Preview 20 recipe permission feedback

The preview 20 JAR passed 5,347 server assertions in 30 accepted processes. A survival-mode protocol client exercised locked and permitted crafting, anvil and smithing operations on nine Spigot and Paper builds. It decoded the item's model, name and lore, the exact window and slot, chat and the expiry update. Two additional runs exercised chat fallback with ProtocolLib absent. Each build repeated the core bundle checks. Spigot 1.21.11 and Paper 26.3 also repeated startup recipe exclusions and restoration across three boots. The offline parser and transport checks are counted separately. Both endpoints also repeated recipe-book discovery and tested competing recipes with different permissions, including a permission grant before a pending hint was delivered. [Builds and results](recipe-feedback-verification.json) · [Configuration](recipe-feedback.md) · [Distribution audit](recipe-feedback-audit.json)

## Preview 19 recipe controls

The 1,288,284-byte preview 19 JAR passed 6,840 server assertions in 38 accepted processes. Each of nine Spigot and Paper builds ran three cold starts on the same isolated world, with configurations staged by live reload. Native crafting, smelting, anvil collection, ingredient and experience retention, namespace isolation and restored registry definitions were exercised. Nine builds repeated core bundle regressions. Spigot 1.21.11 and Paper 26.3 also repeated preview 18 recipe discovery. The release's station parser passed 364 separate checks. [Exact builds and results](recipe-controls-verification.json) · [Recipe controls](recipe-controls.md) · [Distribution audit](recipe-controls-audit.json)

## Preview 18 recipe book discovery

The 1,275,574-byte JAR passed 1,575 assertions across 21 isolated server processes. Nine Spigot/Paper builds ran the native player discovery suite and the core bundle regression with the published YAML example. One additional Paper 1.21.11 process verified the preview 17 configuration fallback. Spigot 1.21.11 and Paper 26.3 also exercised the individual cooking, cutting and smithing inputs. The packaged policy parser passed 274 separate Java 21 assertions. The player tests inspect server knowledge after actual pickup, inventory clicks, reload and reconnect. Rendered menus and toasts await user review. [Exact results](recipe-book-verification.json) · [Recipe book guide](recipe-book.md) · [Distribution audit](recipe-book-audit.json)

Preview 17 added six-face area mining, upward columns, preservation filters and protected additional reward policies. Its exact candidate passed 2,049 scoped assertions in 20 accepted server processes on nine builds, including native player input, public event/API cases, the earlier area regression fixture and core/bundle checks. See [the guide](area-mining.md), [exact report](area-surface-verification.json) and separately counted packaged geometry/proxy evidence. Historical evidence below retains its original scope; user visual acceptance remains separate.

This page preserves the historical **1.0.0** matrix. Current **1.1.0-SNAPSHOT** evidence is in [development verification](development-verification.md), including the unchanged preview-6 JAR's [native 26.x player matrix](native-26x-verification.md). The historical limitations below describe the 1.0.0 artifact and must not be read as the current implementation status.

Date: **25 October 2026**. The same release JAR passed **18 isolated server runs across 9 builds**, with **2416 successful assertions** and zero failed assertions in the final matrix. Every build completed two full process starts. This establishes the executed headless scenarios, not full reference-plugin parity or client acceptance.

## Artifact and build

- File: `BlocksAdder-1.0.0.jar`, 171,864 bytes.
- SHA-256: `75250f89823771d35d2227ee926a26c8eb88301571a4016d901c6b9637c0121a`.
- Java class-file major version: 65 (Java 21).
- Build dependency: Spigot API `1.21.11-R0.1-SNAPSHOT`.
- Maven 3.9.11 compilation/package: PASS with Microsoft JDK 21.0.7 and Temurin JDK 25.0.4.1.
- Runtime: Java 21 on Minecraft 1.21.11; Java 25 on the 26.x builds.
- Independently compiled test probe against both Spigot and actual Paper 1.21.11 APIs. Probe SHA-256: `29cff6b58a009f78b6c183cff6a4d6f4cc22831cb0bb420e29fac0e2c52c8401`.

## Executed server matrix

| Server | Java | First start checks | Second start checks | Result |
| --- | --- | --- | --- | --- |
| spigot-1.21.11 | 21 | 134 | 135 | PASS |
| spigot-26.1.2 | 25 | 134 | 135 | PASS |
| spigot-26.2 | 25 | 134 | 135 | PASS |
| spigot-26.3 | 25 | 134 | 135 | PASS |
| paper-1.21.11 | 21 | 134 | 135 | PASS |
| paper-26.1.1 | 25 | 132 | 135 | PASS |
| paper-26.1.2 | 25 | 132 | 135 | PASS |
| paper-26.2 | 25 | 132 | 135 | PASS |
| paper-26.3 | 25 | 135 | 135 | PASS |

Spigot 1.21.11 in this matrix is its cached Unobfuscated build (`4598-Spigot-56165ca-d74c5d8`); the ordinary Paper 1.21.11 build was also exercised. Paper builds were 1.21.11-132, 26.1.1-29, 26.1.2-74, 26.2-134 and 26.3-218. Exact server-reported build strings and per-run checksums are retained in [verification-results.json](verification-results.json). A literal 26.1 server process and Spigot 26.1/26.1.1 processes were not available and were not run; the target range must be read with this evidence boundary.

## Scenarios exercised

- Actual Bukkit item construction: material, names/lore, persistent identity, clone/YAML serialization, model component, float model data, stack size, durability and custom PAPER food/consumable components; vanilla PAPER remains nonconsumable.
- Invalid IDs, amounts, colors, duplicate/ambiguous short identifiers and disabled content.
- Bukkit service API registration, definition lookup, glyph lookup and alias expansion.
- Real registered shaped/shapeless/furnace recipes, exact ingredient choices, successful custom crafting matrix lookup and rejection of plain material lookalikes.
- Deterministic generated ZIPs, actual SHA-1/SHA-256, safe/sorted entries, modern item/model/font files, raw asset merging, excluded YAML and enforced pack size limits.
- Identical pack hashes across separate JVM starts when production binary/content inputs are identical.
- Real loopback HTTP GET/HEAD serving, exact ZIP bytes/hash/type/length, query parameters, unknown/prefix/traversal routes returning 404 and unsupported POST returning 405.
- Invalid YAML/content, recipe ingredients, stat bounds, gravity collision blocks, crop references and settings rejected without replacing the live registry/settings/recipes.
- Console command routing and English error paths.
- Real world placements, collision blocks, ItemDisplay/Interaction helpers, cancellable custom placement events, per-chunk limits, chunk PDC, reload preservation and no helper duplication.
- Block burn/fluid protection events, farmland crop requirements/initial stage, custom living entity metadata/AI/limits, actual death events/custom drops and real tree block placement.
- Full server restart preserves a dedicated custom block identity/collision and recreates its helper. Deferred checks require exact helper counts, valid global UUID lookup and spatial visibility.

## Method and resolved findings

Servers ran in disposable, loopback-bound directories with generated flat worlds, offline mode and only BlocksAdder plus a temporary verification plugin. Existing user servers and worlds were not modified. Fixture-owned chunk tickets modeled resident chunks during entity-visibility assertions and were released afterward; production does not force every content chunk to remain loaded.

Tests exposed and resolved two production defects: UUID-only cleanup leaked fresh display helpers during same-tick reloads, and direct Spigot consumable calls were absent on Paper. Production now keeps direct references to owned temporary helpers and adapts the two public consumable APIs. Test-only issues, including Spigot's normalized recipe symbols and no-player chunk visibility, were corrected without removing the relevant assertions.

Some 26.2/26.3 server launches emitted OSHI/JNA Windows performance-counter warnings from the host's Perflib registry. These were outside BlocksAdder and did not produce failed plugin assertions. No host registry changes were made.

## Still requiring interactive acceptance

No real Minecraft player/client was connected. Client texture/model/font/sound rendering, pack acceptance/failure prompts, inventory click/drag/shift behavior, recipe permissions and shift/automated crafting, anvil/smithing player consumption/XP, survival placement/breaking/harvesting, seating, HUD presentation, emotes and named protection-plugin adapters were not certified by these runs. Several of their data/API paths were exercised, but that is narrower than end-to-end player verification. Custom mining-time simulation and the advanced missing families listed in [feature-parity.md](feature-parity.md) are unimplemented.

The user requested deletion of test files after testing. Automatic approval review rejected both recursive cleanup of the verified workspace-local `.work` directory and deletion of its single temporary verification JAR, returning `blocked by policy`. Consequently temporary verification source/JAR, disposable servers, private decompiler output and build-check copies remain locally in the ignored `.work` directory. They were never committed or uploaded to the release. The original input JAR remains in its original ignored directory. Future contributors should preserve the distinction between compilation, executed server paths and interactive client acceptance.
