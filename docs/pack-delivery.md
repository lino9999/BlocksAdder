# Resource pack delivery

Use `/ba pack build` to generate `plugins/BlocksAdder/pack/BlocksAdder.zip`. Upload that ZIP to your HTTP(S) host or enable the built-in host. Set `resource-pack.url` to the public download address in `plugins/BlocksAdder/config.yml`. Run `/ba reload`, then `/ba pack send` to request the pack in game. The built-in host serves the path `/BlocksAdder.zip`. [Example server settings](examples/pack-delivery.yml) · [Hosting configuration](configuration.md#pack-delivery)

## Choose what happens while a player applies the pack

Set `resource-pack.delivery.pause` to any combination of the activities below. The default is an empty list. Restrictions begin when that player accepts the current request. The client can report that the bytes have downloaded before it finishes applying them. Restrictions continue through this interval.

| Activity | What pauses |
| --- | --- |
| `movement` | Walking and changes in facing reported through player movement events. |
| `teleports` | Bukkit player teleports, including portal teleports. |
| `commands` | Commands entered by the applying player. Staff can still use the console. |
| `combat` | Entity damage when the applying player is the event’s damager or victim. This includes an arrow hitting the player. An arrow fired by that player at an unprotected target can still deal damage. Environmental damage such as a fall follows normal rules. |

Use `notice.title` and `notice.subtitle` under `delivery` for a short on-screen message. Both default to empty strings and accept `&` color codes. BlocksAdder refreshes the message once per second while the pack applies. After the request ends it lets the last title fade, preserving any subsequent title from another plugin.

## Timing and permissions

| Setting under `resource-pack.delivery` | Default | Behavior |
| --- | --- | --- |
| `join-delay-ticks` | 20 | With `auto-apply: true`, wait this many ticks after the player joins. A player whose delay expires before the first successful pack build receives the pack when it becomes ready. |
| `self-cooldown-ticks` | 0 | Minimum time between successful uses of `/ba pack send` without a target. Named, all-player and automatic sends use separate paths. |
| `timeout-ticks` | 1200 | Stop waiting for an answer after this interval. The first acceptance starts a fresh interval for application. Expiry releases all restrictions and leaves the player connected. |
| `decline` | Inherits `resource-pack.required` | `allow` keeps a player connected after rejection. `kick` disconnects the player. |
| `failure` | Inherits `resource-pack.required` | Controls download failures, reload failures and invalid URLs. Setting it explicitly to `kick` also disconnects a player whose current request is discarded. |

The join delay and self cooldown accept integers from 0 to 72,000. The timeout accepts 1 through 72,000. At 20 ticks per second, 1,200 ticks is one minute. A duplicate acceptance does not extend the deadline.

`blocksadder.pack` grants pack commands. Building also requires `blocksadder.pack.build`. Sending to a named player or to `all` requires `blocksadder.others`. Grant `blocksadder.pack.exempt` to exempt a player from BlocksAdder’s disconnect policies. This permission defaults to false, including for operators. The selected activity restrictions still apply.

With `required: true`, players receive Minecraft’s mandatory prompt if decline enforcement applies to them. An explicit `decline: allow` or an exemption at send time uses the optional prompt. A permission change after sending cannot change a prompt already displayed by Minecraft. The server’s own `require-resource-pack` property operates independently.

## Replacements and recovery

Every send gets a fresh ID, including resends of the same ZIP. BlocksAdder replaces its own layer in the client’s pack stack. A response for a foreign pack leaves the player’s BlocksAdder request alone. A late acceptance of an earlier, superseded request triggers cleanup of that earlier layer.

Success, refusal, failure and discard release the affected player immediately. Death, disconnection, a successful BlocksAdder reload and plugin shutdown also end restrictions. Invalid settings leave the current request running under its previous policy. A build can finish while an earlier request is pending. The built-in host retains the exact earlier ZIP for that request until completion or expiry. An external host is responsible for serving the bytes matching the requested hash.

This delivery mode begins after the player joins the world. Pre-world configuration-phase delivery and managed upload providers remain open in the reconstruction. [Implementation progress](parity-progress.md)

## Tested versions

| Server | Minecraft versions |
| --- | --- |
| Spigot | 1.21.11, 26.1, 26.1.1, 26.1.2, 26.2, 26.3 |
| Paper | 1.21.11, 26.1.1, 26.1.2, 26.2, 26.3 |

Use Java 21 for Minecraft 1.21.11 and Java 25 for 26.x. The report identifies the exact server artifacts, pack JAR and protocol checks. [Preview 25 verification](pack-delivery-verification.json)
