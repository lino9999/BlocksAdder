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

## Distribution preparation

The JAR contains project classes and three independently authored YAML resources. Bukkit/Spigot and optional ProtocolLib dependencies remain provided dependencies, with their own authorship. Administrator-supplied pack assets are not redistributed by this repository. Publishing a content pack requires rights to all of its assets.

[Spigot's resource rules](https://www.spigotmc.org/wiki/spigot-rules/) prohibit republishing another author's plugin with minor changes and require inspectable implementations. This project keeps readable packages and classes, publishes source and records the audit rather than hiding the implementation. The build contains no activation/revocation endpoint, telemetry, runtime dependency installer or executable downloader from the reference. These checks prepare a reviewable independent distribution; they do not guarantee a marketplace decision.

The historical 1.0.0 preview remains a separate release. Development changes and their exact validation scope must be described accurately before publishing another downloadable release. User-owned visual checks are separate from code, data and server verification.
