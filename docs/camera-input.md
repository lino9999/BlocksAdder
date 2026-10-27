# Native model camera input

Development preview 13 separates incoming camera input from outgoing equipment masking. This addresses the Paper 26.3 disconnect observed with the tested official ProtocolLib 5.5.0-SNAPSHOT artifact. The production adapter continues to use the public ProtocolLib API.

## Ownership and threading

Camera start, stop and position updates stay on the server thread. Starting a camera publishes one session for the exact player object, with an atomic immutable rotation. The incoming listener only resolves that identity, cancels owned movement packets and records finite look angles. It never calls Bukkit player, inventory or world methods. Yaw wraps into [-180, 180); pitch clamps into [-89.9, 89.9]. Non-finite rotations leave the previous rotation intact. Packets from players without a camera pass through unchanged.

The concurrent identity map compares player references directly, without invoking player equality or hash methods. Ending a camera removes that session before restoring native view packets. A callback already holding a removed rotation can update only that detached value; it cannot recreate the session or change a later session. Reconnects with the same UUID do not inherit an old player object's input.

Outgoing equipment and inventory masking has its own ordinary ProtocolLib listener. It keeps the provider's default server-thread dispatch and does not declare `ASYNC`. Existing public inventory access and the exact synchronous self-only totem display exception remain in that path. No scheduled packet queue, broad player scan, version-specific NMS bridge or executable download was added.

## Diagnostic controls

The isolated Paper 26.3 controls installed only ProtocolLib and a small verification plugin; BlocksAdder was absent. An otherwise empty receiving movement listener with `ListenerOptions.SYNC` reproduced `Invalid move player packet received`, both alone and combined with outgoing inventory types. The same receiving types without `SYNC` passed ten checks, including native movement, interaction, health and unchanged equipment. The earlier ProtocolLib-only control without a receiving listener passed nine checks.

These controls identify the failing registration path for these exact artifacts. They do not establish the provider's internal cause or make a statement about every ProtocolLib build. The corrected BlocksAdder camera is verified with real native protocol packets, including actual look input and movement cancellation while active, rather than only idle connection checks.

The public provider contracts are [ListenerOptions](https://github.com/dmulloy2/ProtocolLib/blob/master/src/main/java/com/comphenix/protocol/events/ListenerOptions.java) and [PacketAdapter](https://github.com/dmulloy2/ProtocolLib/blob/master/src/main/java/com/comphenix/protocol/events/PacketAdapter.java). `SYNC` requests server-thread receiving callbacks; `ASYNC` declares outgoing callbacks thread-safe. Splitting the listeners allows each direction to retain its own threading contract.

## Evidence and limits

See [the exact candidate report](camera-input-verification.json) and [distribution audit](camera-input-audit.json). Older preview-12 reports retain their original failure disclosure and identify a different artifact.

The user owns rendered camera movement, avatar alignment, skin/shader, equipment and animation appearance checks. Native packets and server-state assertions establish only their recorded scenarios. Full reference-plugin parity, arbitrary third-party providers and all model/emote combinations remain open in the [development tracker](parity-progress.md).
