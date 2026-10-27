# PlaceholderAPI in configured commands

An item event's `execute_commands` entry can opt in to resolving PlaceholderAPI values in command arguments. This is an incoming integration for administrator-authored command templates. The separate [export expansion](placeholders.md) exposes BlocksAdder glyphs and meters to other plugins.

Install a compatible official PlaceholderAPI build separately and restart the server. BlocksAdder does not download the provider or its expansions. Install or configure each expansion required by your chosen tokens through its normal process. Expansion callbacks run on the server thread with the event's real player context.

## Opt in per command

Set `placeholder_api: true` inside each named command entry that needs external values. It is a strict YAML boolean, defaults to false and does not enable other command entries. Quoted `"true"`, numbers and other types fail candidate validation.

```yaml
format: blocksadder/bundle-1
bundle: garden
entries:
  - key: stamina
    kind: meter
    settings: {min: 0, max: 100, default: 75}
  - key: mirror
    kind: meter
    settings: {min: 0, max: 100, default: 0}
  - key: meter_copy
    kind: artifact
    item: {base: PAPER, label: Meter Snapshot, stack: 1}
    traits:
      all_actions_permission: garden.meter-copy
      events_cooldown: 20
      events:
        interact:
          right:
            execute_commands:
              copy_value:
                command: 'ba stat write {player} garden:mirror %blocksadder_meter_garden:stamina%'
                as_console: true
                silent: true
                placeholder_api: true
```

Save this independently designed bundle under `plugins/BlocksAdder/contents/garden/definitions.yml`, run `/ba validate` and `/ba reload`, and give yourself `garden:meter_copy` with `/ba get garden:meter_copy`. Grant the intended users the example's `garden.meter-copy` permission. A permitted right-click copies that player's current stamina value into the separate mirror meter through BlocksAdder's registered command. It does not consume the item. Use `/ba stat read <player> garden:mirror` to inspect it with the normal command permission.

This example uses BlocksAdder's own outgoing meter expansion, so it requires that expansion to be registered. Incoming support itself is independent of `integrations.placeholders` and ownership of the outgoing `blocksadder` prefix: other installed, trusted expansions can be resolved while BlocksAdder's exports are disabled or their prefix is unavailable. The optional outgoing expansion and its generated resource-pack font references are configured separately.

Static commands, including entries with `placeholder_api: true` but no actual external tokens, do not require a running provider. Without opt-in, nonempty paired `%...%` expressions remain validation errors; ordinary lone percentage literals retain their previous behavior. With opt-in, every percent delimiter must form a valid token, so a lone percentage or `%%` is rejected.

## Template and result limits

The command name must be a fixed literal label matching `[A-Za-z0-9_.:-]+`. A leading slash is optional and surrounding ASCII spaces are trimmed; only one leading slash is removed. Namespaced labels such as `blocksadder:ba` follow the same rule. Neither built-in `{...}` placeholders nor external `%...%` tokens can supply any part of the command label. Dynamic content belongs after the literal ASCII space separating the label from its arguments.

| Input | Contract |
| --- | --- |
| Raw configured template and final rendered command | At most 8192 Java UTF-16 code units each. |
| External tokens per command | At most 32 occurrences, including repeated selectors. |
| Selector between percent delimiters | 1–512 ASCII characters, matching `[A-Za-z0-9_][A-Za-z0-9_:./+\-]*`. |
| Each provider replacement | 1–1024 ASCII characters, matching `[A-Za-z0-9_.:+\-]{1,1024}`. |

Commands reject control characters, Unicode formatting characters, line/paragraph separators, malformed surrogate text and whitespace other than ordinary ASCII space. Input is checked before trimming, so leading or trailing tabs and line breaks are invalid. Provider values must also satisfy the stricter replacement grammar: spaces, slashes, backslashes, quotes, braces, percent signs, formatting codes and free text are rejected.

Safe scalar shapes include a player identifier such as `Player_7`, a namespaced identifier such as `garden:stamina`, numbers such as `75.0`, `-8` or `+12`, and a UUID such as `00000000-0000-0000-0000-000000000001`. These examples illustrate character acceptance. The receiving command remains responsible for the value's meaning, numeric bounds, identifier existence and permissions. A syntactically accepted string is not automatically a valid number or an authorized target.

Each original `%selector%` token is passed once to PlaceholderAPI. Built-in player/target/world/location substitutions apply only to the original literal template parts. Values inserted by either path are not parsed again for new placeholders. A provider returning an unresolved original token, another `%token%`, an empty value or an unsafe value makes the action fail. This path does not enable free-text external expansion in item names, messages, arbitrary actions or HUD titles.

## Execution, guards and flow

Resolution happens immediately before that command executes. If PlaceholderAPI is absent, unavailable or not initialized, an expansion is unknown, a provider throws, or a callback returns a rejected value, the command is not dispatched and its action outcome is false. Existing command-group flow conditions can observe that failure. This does not make every later action stop automatically; configure the event plan's flow when that is desired. Ordinary permission-denied steps retain their existing admission behavior.

Before and after each built-in or provider callback, and before dispatch, BlocksAdder checks the current player connection, player world, game mode, action and definition permissions, registry identity and action-service revision. It retains the current source and stack snapshots and the source's existing physical/projectile authority where applicable. Player inventory is also snapshotted for commands containing actual external tokens; detected inventory changes during resolution invalidate that command. Ordinary player contexts without explicit source authority additionally retain the selected hotbar slot. Provider instance, enabled state and lifecycle revision must remain current across resolution.

Snapshots begin when the command step starts. An earlier action in the same ordered plan may intentionally consume or replace its source; that alone does not prevent a following immediate command. The command retains the originally admitted item definition for permission checks and compares the state at command entry across its callbacks. Physical and flight plans retain their own source-retirement rules. Positive delays resolve placeholders when the delayed step fires, using the existing delayed-source, player, target and permission checks; reload, disconnect and shutdown retain their cancellation behavior.

`as_console`, `silent` and output predicates keep their existing meanings. Silent console execution and captured-output conditions require a registered Bukkit plugin command. Native commands require `silent: false` and no captured-output predicate. Player command output predicates remain unsupported. See [item event actions](item-behaviors.md) for the surrounding command-group and flow contracts.

## Trust and scope

Only trusted administrators should edit these templates or select expansion providers. The replacement grammar prevents structural injection through whitespace, line breaks, delimiters or a changed command label. It does not establish semantic security for privileged commands, nested administrator templates, selectors, subcommands or sensitive target arguments. Review the complete fixed command and every argument position; a safe-looking scalar can still select a powerful operation if the configured command interprets it that way. Console commands run with console authority, while player commands retain their normal dispatch permissions.

An installed expansion is executable plugin code. Its callback can perform side effects before returning. BlocksAdder can reject dispatch after detecting an invalidated state, but it does not roll back provider side effects, earlier event actions or effects of a dispatched command. The guards are checks around known callbacks, not a sandbox for external plugins or a claim that every possible external mutation is observed.

The official provider's lifecycle limitations described in the [export guide](placeholders.md#permissions-and-lifecycle) also affect incoming resolution. Use a normal server restart after provider installation/lifecycle changes; plain plugin-manager disable/enable is not generic hot-reload support.

## Exact verification

Development preview 16's 1,215,448-byte JAR has SHA-256 `3c63a399897aab1a049ea674654c2703d506af263a79ca30d0f56a7afd7be55e`. It passed 2,018 scoped assertions in 26 accepted isolated server processes. Each of nine supported builds passed 89 incoming integration checks with official PlaceholderAPI 2.12.3. Each run contains an actual native held-use input and quit/rejoin; bulk adversaries use public event/API invocation with real connected players. Two separate provider-absent native boots passed eight checks each. The same candidate passed outgoing export, held-interaction and projectile endpoint regressions plus nine core/bundle runs without PlaceholderAPI. Java 21 is used for 1.21.11 and Java 25 for 26.x.

The packaged template helper separately passed 170 assertions and 1,000 concurrent renders. The [exact report](command-placeholders-verification.json) records hashes, counts and limits; the [distribution audit](command-placeholders-audit.json) covers 404 own classes with empty collision/leak lists. Neither the supplied original JAR nor a graphical client was executed. Flight callback invalidation, mid-expansion quit identity, asynchronous public event invocation and every external provider combination are not certified by this new fixture. Private controlled provider initialization is not proof of arbitrary hot reload. A disk-full failure before incoming server launch and one interrupted bundle run are excluded; the bundle endpoint passed in a fresh directory after space recovery. Full parity and user visual acceptance remain separate.
