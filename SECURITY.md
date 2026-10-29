# Security model

NCD intentionally removes Minecraft's second confirmation for server-provided chat command clicks. Server owners should install it only when that behavior is desired.

- Commands execute as the clicking player, with normal platform permissions and command events.
- Incoming custom-click payloads never supply the command to execute. Commands are looked up using random, session-bound identifiers registered from outgoing server messages.
- A proxy may adopt command metadata from backend messages it is sending to its client. It does not adopt metadata from incoming client packets.
- Identifiers from another connection, unknown identifiers, expired entries and excessive click rates cannot execute a registered command.
- Registries are bounded and cleared on channel close or plugin disable. Packet buffers are released when consumed or replaced.
- Player chat signatures and signed bodies are preserved; only unsigned display components are rewritten.

Report vulnerabilities privately through the repository's GitHub security reporting feature when available. Do not publish live command tokens or server credentials in issues. The plugin has no credentials, database, HTTP client, telemetry or automatic updater.
