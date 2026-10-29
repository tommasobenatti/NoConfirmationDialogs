# Security model

NCD intentionally removes Minecraft's second confirmation for server-provided chat command clicks. Server owners should install it only when that behavior is desired.

- Commands execute as the clicking player, with normal platform permissions and command events.
- Incoming custom-click payloads never supply the command to execute. Commands are looked up using random, session-bound identifiers registered from outgoing server messages.
- A proxy imports backend commands only from directional server-origin registration messages. Click components contain an identifier with no command echo payload; client-supplied registration messages are not imported.
- Paper publishes token registrations on the native `ncd:register` backend-to-proxy channel before chat delivery. Proxy aliases are imported only on outgoing server-origin traffic. Unknown Paper-scoped tokens are consumed at the proxy rather than forwarded around its command policy.
- Identifiers from another connection, unknown identifiers, expired entries and excessive click rates cannot execute a registered command.
- Registries are bounded and cleared on channel close or plugin disable. Packet buffers are released when consumed or replaced.
- Limits include 262,144 retained command characters, 4,096 entries, a 12-hour validity lifetime, 20 resolved clicks per fixed one-second window, and 100 consumed requests / 1 MiB request data per fixed one-second window. Paper uses a conservative native decoded-NBT size estimate when the original wire buffer is unavailable.
- Invalid native envelopes are disconnected. Overlong/overflowing VarInts cannot hide a click from proxy policy. Commands are not converted outside the host's PLAY phase.
- Player chat signatures and signed bodies are preserved; only unsigned display components are rewritten.

Report vulnerabilities privately through the repository's GitHub security reporting feature when available. Do not publish live command tokens or server credentials in issues. The plugin has no credentials, database, HTTP client, telemetry or automatic updater.

Scope: commands and payload metadata from trusted server/plugin code are nominated actions, not an authority grant. A client may replay its own valid token within the limits, but must still pass current command permissions and cancellation. Host authentication, forwarding configuration, transport protection and permissions remain server-owner responsibilities. No exploit-free guarantee is made. See [the 1.0 audit](docs/AUDIT.md) for findings corrected before the first stable release.
