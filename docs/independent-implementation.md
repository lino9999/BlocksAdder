# Independent implementation and distribution audit

BlocksAdder is authored as a separate project by Lino. The supplied binary is behavioral research material. Its implementation, classes, shaders, textures, bundled dependencies, message catalogs and configuration files are not product inputs. Changing author strings in a copied binary would not meet this project's requirement.

## Design boundary

Feature observations become requirements, then separate BlocksAdder services implement them. The source tree is organized around immutable registry snapshots, content validation, public Bukkit adapters, bounded scheduled queues and explicit lifecycle ownership. Item identity, world placement persistence, model rendering, generation, furniture footprints and resource-pack compilation have their own service boundaries and new API. No original package compatibility layer is exported.

The content document is `blocksadder/bundle-1`: a bundle identifier and a list of typed records with `key`, `kind`, `item`, `traits` and `settings`. It is not a renamed copy of the reference configuration. The former content-alias normalization class has been removed. The loader requires this format marker and rejects flat or external documents before activation. Packaged examples and the configuration editor use the same record model.

Public command and permission names belong to BlocksAdder. Shared platform vocabulary such as Minecraft material names, Bukkit types, `plugin.yml`, `config.yml` and ordinary short property names cannot usefully be treated as copied product expression. The audit distinguishes those from product branding, declared command/permission collisions and longer string literals.

## Reproducible checks

Build with `mvn -B clean package`, then run the offline audit against the supplied reference and the exact candidate:

```text
python tools/audit_distribution.py <reference.jar> target/BlocksAdder-1.1.0-SNAPSHOT.jar --output docs/distribution-audit.json
```

The audit executes neither JAR. It hashes every nonempty archive member, parses every JVM class constant pool, checks class/package paths, searches product-specific branding, compares declared command/permission keys and compares longer message literals. Its JSON records both artifact hashes and the exact findings. Temporary analysis, test inputs and external classes are rejected in the candidate.

The current report is [distribution-audit.json](distribution-audit.json). A passing result establishes those specific checks only. Different bytes and class names cannot establish independent authorship of every algorithm; source provenance and review remain relevant. It also does not prove every behavior or rendering mode works. Those acceptance requirements remain in [parity-progress.md](parity-progress.md).

Historical release reports retain their exact artifacts and original audit scope. The later [world-action audit](world-actions-audit.json) also compares short readable message literals from JVM constants, rather than only longer messages. It found no shared readable message literals. Common single-word/API identifiers and JVM string-concatenation recipe markers remain outside that text-message classification.

The [plant-view audit](crop-views-audit.json) applies the same checks to the later candidate containing 330 own-package classes and three YAML resources. All copied-file, branding, readable-message and declared command/permission collision lists are empty. Its per-player renderer is independently organized around a resident chunk index, incremental nearest-candidate queues and directly owned normal/facing display lifecycles. This records a concrete source/distribution boundary rather than claiming every algorithm's authorship can be inferred from byte differences.

The later [placed-plan audit](world-plans-audit.json) covers 332 own-package classes with the same empty collision lists. Its independently named `world.use/remove/harvest` plans use read-only physical representations, root preparation events and weak revision markers for delayed identity. No reference trigger alias or implementation class is introduced by this domain bridge. Behavioral results are separate in [world-plans-verification.json](world-plans-verification.json).

The [held-routing audit](held-world-audit.json) covers 333 own-package classes and retains empty copied-file, branding, command, permission and readable-message collision lists. Its native-event handoff, cached model ownership resolution, shared admission token and source comparisons extend the independently designed service architecture. The runtime [regression report](held-world-verification.json) compares earlier BlocksAdder behavior, never execution of the reference artifact.

## Distribution preparation

The JAR contains project classes and three independently authored YAML resources. Bukkit/Spigot and optional ProtocolLib dependencies remain provided dependencies, with their own authorship. Administrator-supplied pack assets are not redistributed by this repository. Publishing a content pack requires rights to all of its assets.

[Spigot's resource rules](https://www.spigotmc.org/wiki/spigot-rules/) prohibit republishing another author's plugin with minor changes and require inspectable implementations. This project keeps readable packages and classes, publishes source and records the audit rather than hiding the implementation. The build contains no activation/revocation endpoint, telemetry, runtime dependency installer or executable downloader from the reference. These checks prepare a reviewable independent distribution; they do not guarantee a marketplace decision.

The [environmental-rule audit](environment-removal-audit.json) covers 343 own classes, with the same empty copied-file, branding, readable-message and command/permission collision lists. `RemovalPlans` is an independently designed compiled cause/effect service with committed receipts, chunk-indexed jobs and retirement/backing-state guards. Its `traits.environment` records differ from the player action-plan schema and do not provide external trigger aliases. Its [runtime evidence](environment-removal-verification.json) remains separate from authorship and marketplace review.

The historical 1.0.0 preview remains a separate release. Development changes and their exact validation scope must be described accurately before publishing another downloadable release. User-owned visual checks are separate from code, data and server verification.

The [world-change audit](world-change-audit.json) covers 348 own classes and retains empty collision lists. `WorldChoice`, `WorldBlockEditor`, the `world_change` compiler and physical ownership authority form an independently designed conditional transaction. Public state snapshots, native payload escrow and explicit rollback are BlocksAdder's implementation choices; they are not translated reference classes. The [world-change report](world-change-verification.json) records exact runtime coverage separately from distribution/authorship evidence. It does not certify marketplace approval or complete feature parity.

The [area-mining audit](area-mining-audit.json) covers 353 own classes with empty collision lists. `SweepRegion` and `WorldSweeps` introduce BlocksAdder region/frame/select records, committed-origin scheduling, exact source/placement snapshots and a shared attempt budget. Native extra cells use public `Player.breakBlock`; custom cells reuse owned domain protection and mining profiles. The [runtime report](area-mining-verification.json) distinguishes new mining cases from repeated regression and core checks. No reference implementation class, configuration document or asset was translated or bundled.

The [projectile audit](projectile-actions-audit.json) covers 358 own classes with empty collision lists. `ProjectilePlans`, the flight origin and attributed-source interfaces independently connect native item carriers to bounded action plans. Owned item receipts, payload/epoch guards and public DamageSource builders replace any need for reference version adapters. [Runtime evidence](projectile-actions-verification.json) distinguishes native input, API cases and regressions, including a reproduced pre-fix held-melee attribution defect. No original implementation class, external configuration or asset is bundled.
