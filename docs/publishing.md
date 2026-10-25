# Publishing assets

The GitHub repository is `lino9999/BlocksAdder`. Project branding and runtime author metadata are BlocksAdder and Lino. The source repository contains only independently written implementation and project-owned publishing assets.

## Repository metadata

Suggested About description:

> Custom items, blocks, furniture, crops, recipes, HUDs and resource packs for Minecraft Spigot/Paper 1.21.11–26.3. Java plugin by Lino.

Topics: `minecraft`, `minecraft-plugin`, `spigot`, `spigot-plugin`, `paper`, `paper-plugin`, `custom-items`, `custom-blocks`, `furniture`, `resource-pack`, `custom-recipes`, `crops`, `hud`, `java`, `blocksadder`.

## SpigotMC

Title: **BlocksAdder — Custom Items, Blocks, Furniture & Resource Packs**

Short description: **Create custom Minecraft items, blocks, furniture, crops, recipes and HUDs with YAML and resource packs.**

Copy `spigot-description.bbcode` into the resource description and `spigot-documentation.bbcode` into the documentation tab. Use `assets/blocksadder-icon.png` as the icon source. It was created with the built-in imagegen tool; the selected prompt is retained at `assets/icon-prompt.txt`.

Publish the actual release JAR. Select only Minecraft versions represented in the verification report, and retain the release-scope paragraph. Do not describe incomplete skeletal models, disguises, vehicles, fluid/fire simulation or adapters as available features. SpigotMC publication itself requires access to the owner's resource submission form and is separate from supplying these requested publishing files.

## Release discipline

Build the release with Java 21 bytecode. Verify the same JAR on the target servers, then calculate its SHA-256 and include a checksum file with the downloadable asset. Release notes should link the verification report and feature comparison. The first independent implementation is published as a prerelease while broader parity and interactive client acceptance remain incomplete.

The supplied original artifact, extracted resources, decompiled files, temporary test plugins and disposable servers must never be included in a Git commit or release asset. `.gitignore`, `.ignore` and `.aiignore` exclude them. Delete temporary testing files after recording verification results, retaining the user's original input JAR in its original folder.
