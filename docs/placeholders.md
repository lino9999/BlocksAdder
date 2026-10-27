# PlaceholderAPI exports

BlocksAdder can expose glyphs, font identifiers, pixel spacing and player meter values to plugins that consume [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI). These are outgoing, read-only exports. A consuming plugin must support PlaceholderAPI parsing in the particular field where you use them. This adapter does not add automatic parsing of arbitrary external placeholders to BlocksAdder commands, actions or HUD titles.

## Enable the adapter

Install a compatible official PlaceholderAPI build in the server's `plugins/` directory and restart the server. BlocksAdder does not bundle or download it. No expansion download from eCloud is required: BlocksAdder registers its own internal `blocksadder` expansion.

The optional setting in `plugins/BlocksAdder/config.yml` defaults to true:

```yaml
integrations:
  placeholders: true
```

After changing this setting, run `/ba reload`. Setting it to false detaches the expansion while ordinary BlocksAdder features continue working. With the setting enabled, registration requires an enabled, initialized PlaceholderAPI provider and an available `blocksadder` expansion identifier. An existing expansion owned by another plugin is preserved; BlocksAdder reports the conflict instead of replacing it. Remove the conflict through its owning plugin, then reload BlocksAdder to retry.

When registration succeeds, the console reports `BlocksAdder placeholders registered.` Build the resource pack with `/ba pack build` while the adapter is active, distribute the new ZIP and have clients accept it. Generated spacing support is captured when the build starts; an older ZIP does not gain it just because the adapter becomes active later.

## Placeholder families

The examples below assume registered glyph `garden:heart` and meter `garden:stamina` definitions. Define them using BlocksAdder's [bundle format](configuration.md); see [HUDs and meters](hud-meters.md) for glyph and meter examples.

| Placeholder | Result |
| --- | --- |
| `%blocksadder_glyph_garden:heart%` | The glyph's Unicode character followed by an independent negative-one-pixel spacing sequence. |
| `%blocksadder_code_garden:heart%` | The raw Unicode character, without the spacing suffix. |
| `%blocksadder_font_garden:heart%` | The configured namespaced font identifier, or `minecraft:default` when omitted. |
| `%blocksadder_move_-8%` | A scalar spacing sequence with a signed advance of minus eight pixels. |
| `%blocksadder_meter_garden:stamina%` | The selected online player's current bounded meter value as finite decimal text, for example `17.5` or `20.0`. |

`code` returns the character itself, not its numeric Unicode code point or an escaped Unicode string. A supplementary character occupies two Java UTF-16 code units while still representing one Unicode character.

`move` accepts decimal integers from `-1048576` through `1048576`, inclusive. A leading plus is allowed. Zero produces an empty string. Fractions, whitespace, missing numbers and integer overflow are invalid. The generated spacing changes the text cursor; it does not resize a glyph or change a consumer's layout configuration.

For a quick check with PlaceholderAPI's parse command:

```text
/papi parse me %blocksadder_meter_garden:stamina%
/papi parse me %blocksadder_font_garden:heart%
/papi parse me %blocksadder_glyph_garden:heart%
```

Use the consuming plugin's supported syntax for combining placeholders. Parsing a token successfully does not establish that the consumer selects the correct font or that the player accepted the pack.

## Identifiers and unresolved values

Canonical namespaced identifiers are recommended. A shorthand such as `%blocksadder_meter_stamina%` works only when that local name identifies exactly one registered meter. Glyph lookup has its own equivalent uniqueness check. If both `garden:heart` and `winter:heart` exist, use their complete identifiers; the ambiguous `heart` shorthand is unresolved.

Local keys retain underscores, dots and slashes. For example, `garden:status/heart_small.v2` can be used intact after `glyph_`, `code_` or `font_`. Identifiers are lowercase and follow the content identifier rules. The parser splits the family prefix once, so underscores inside a key are preserved.

| Request condition | Adapter result |
| --- | --- |
| Known meter with an available player snapshot | Current finite number using Java's locale-independent double representation. |
| Unknown, syntactically valid meter ID | `0.0`. |
| Null, offline or unresolved player context | `0.0` for a valid meter request. |
| First asynchronous request through a previously unknown `OfflinePlayer` wrapper | `0.0` while a bounded main-thread resolution is queued. |
| Ambiguous shorthand, malformed ID, unknown glyph/font/code target, invalid move or unknown family | No replacement (`null` from the expansion); PlaceholderAPI normally leaves the token unchanged. |
| Disabled or retired expansion | No replacement. |

Numbers are values, not formatted labels: there is no fixed decimal count, localization or percent conversion. Format them in the consuming plugin if needed. The meter's owning definition still supplies its bounds, defaults and mutation rules.

## Player snapshots and threading

Synchronous meter requests resolve the live player on the server thread and refresh the requested meter through BlocksAdder's normal meter read. Asynchronous requests read immutable content and concurrent value snapshots, without invoking Bukkit player, world or persistent-data getters on that thread. Committed BlocksAdder meter changes update the live snapshot, including API changes, `/ba stat`, held-item actions and automatic meter rules. Exporting a value does not set a meter, run an action or dispatch a command.

A live `Player` context is prepared on startup or join. A consumer may instead pass an `OfflinePlayer` wrapper that the adapter has never seen. Its first asynchronous request returns `0.0`; a later server tick resolves it to the exact current online player, after which requests through that wrapper can use the player's snapshot. Queues and aliases are bounded, so resolution may take longer under load and an unresolvable or unadmitted wrapper keeps the numeric fallback. An actual offline player is not loaded from disk to answer these exports.

Quit retires the player's contexts. A new connection receives a new context; retained objects from the old connection do not become authority for it. A successful content reload can replace the catalog and require wrapper resolution again. Failed candidate validation retains the current catalog and its existing aliases. Consumers should request fresh values rather than storing expansion objects or assuming an offline wrapper always has a ready snapshot.

## Fonts, spacing and resource packs

PlaceholderAPI returns scalar strings. A string cannot carry Minecraft component font metadata. `font` supplies the identifier separately; it does not apply that font. Configure the consumer to use the glyph's declared font, or use its component/font API if available. `minecraft:default` is suitable only for glyphs available in that font. Combining glyphs from different custom fonts requires a consumer capable of selecting fonts per text run.

Pack builds made while the adapter is active add a reference to the generated `blocksadder:layout` spacing font before the existing providers in `minecraft:default` and in each registered glyph's custom font that is present in the pack. Existing document fields, provider properties and provider order are preserved. When no default font document exists, the builder creates one with the standard Minecraft space/default/unifont references after the spacing reference. Available overlay font documents receive the same treatment. A missing custom font document is not invented: provide it yourself, or coordinate with the external pack supplying it.

The font identifier `blocksadder:layout`, path `assets/blocksadder/font/layout.json` and 42 supplementary private-use characters `U+F1000` through `U+F1029` are reserved for BlocksAdder spacing. Conflicting authored private-font files or explicit uses of these characters in participating glyph definitions, bitmap rows or space advances are rejected. Validation follows available in-pack font references with bounds and cycle detection. A byte-identical generated layout document can be reused.

The reserved spacing reference has priority in participating fonts. External or unavailable font references and TTF/unihex character coverage cannot be exhaustively checked by this validation. Overlay merging preserves the available documents; it does not prove every combination of external packs and simultaneously active overlays. Keep the reserved range free in your other assets, preserve the spacing reference when merging packs and verify the final pack in your consumer. Rendered appearance remains the user's visual check.

## Permissions and lifecycle

These exports are a trusted server-template interface. Glyph/code/font exports do not check the viewer's chat glyph permissions, and meter exports do not require `/ba stat` permission. The normal permission-aware chat, sign and book alias paths retain their own checks. Restrict who can edit consuming plugins' templates and apply any desired viewer permission conditions there. No new player command permission is required merely to export a value.

The internal expansion persists through `/papi reload`. BlocksAdder detaches when the provider is disabled and can connect when an initialized provider is available again. It releases only the exact expansion object it owns. A conflicting replacement expansion is left in place, and retired BlocksAdder expansion references remain inactive even after a later connection.

Ordinary plugin-manager disable/enable is not equivalent to a server restart. In the inspected official PlaceholderAPI 2.12.3 lifecycle, disabling clears its singleton, while initialization occurs in `onLoad`, which a plain enable does not replay. That sequence can leave PlaceholderAPI enabled but unavailable to this adapter. BlocksAdder reports the failed registration and keeps its core features running. Restart the server after changing the provider's installation or lifecycle. A private verification fixture may explicitly replay the provider's public `onLoad` while disabled before enabling it; that controlled setup is not a production repair mechanism or proof of arbitrary hot-reload support.

## Exact verification

Development preview 15's 1,206,863-byte JAR has SHA-256 `10b1b803609defa5148aeabaee4dc52c78044e1942486cc5c2b649a2ae803576`. It passed 2,100 scoped native/API/lifecycle/asset assertions in 22 accepted server processes: 133 real-provider checks on each of nine builds, 74 HUD regressions on Spigot 1.21.11 and Paper 26.3, 175 optional ProtocolLib callback checks on each endpoint and 45 core/bundle checks without PlaceholderAPI on each build. The provider runs use official PlaceholderAPI 2.12.3; the provided compilation SDK is 2.11.7, and older provider runtimes are not certified here. Java 21 is used for 1.21.11 and Java 25 for 26.x.

The [exact report](placeholder-verification.json) records build/provider/probe/client hashes and limits. Separately counted source-level checks cover 121 snapshot/selector/concurrency assertions, 67 font compiler assertions and all 2,097,153 supported signed shifts. Failed prototype fixtures and the initial provider-reenable assumption are excluded from the accepted total. The [distribution audit](placeholder-audit.json) covers 401 own classes and has empty collision/leak lists. No supplied original JAR or graphical client was executed. Scalar parsing and generated asset checks do not certify rendering or full functional parity. For the surrounding meter API and HUD behavior, read [HUDs and meters](hud-meters.md) and the [developer API](api.md).
