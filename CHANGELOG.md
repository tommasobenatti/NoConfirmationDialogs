# Changelog

## 1.0.1-stable — 2026-10-29

- Fix InteractiveChat/PacketEvents conversion errors (`Unknown tag type: EndBinaryTag`) caused by custom clicks with an absent payload.
- Emit a tiny explicit serializable value; never include or trust command text in the client payload. Token ownership, limits and command routing are unchanged.
- Add a real native-NBT → PacketEvents 2.14.0 → InteractiveChat 2026.1.3.0/addon1.3.1 regression fixture, repeated roundtrips and live-client checks.
- Preserve the original `1.0-stable` release and tag. Upgrade the same JAR on each installed NCD layer; no other plugin or configuration changes are required.

## 1.0-stable — 2026-10-29

First stable release, created and maintained by Tommaso Benatti (`tommasobenatti`).

- One native JAR for Paper/Leaf, Velocity and BungeeCord; automatic chat command conversion with no external plugins or storage.
- Backend-to-proxy token registration covers system, disguised and unsigned decorated player chat while preserving proxy command checks.
- Commands are resolved from per-connection server registries, with no client command echo metadata.
- Fixed pre-release proxy-policy and consumed-request packet-budget gaps. Added strict framing, PLAY guards, native packet-counter accounting, request/data caps and aggregate memory bounds.
- Fixed attach/disable races and close-listener retention; cached native packet layouts and reduced component allocations.
- Added adversarial, lifecycle, resource and actual native-host probes, plus installation coverage comparison, author attribution, download/view badges and MCExp server showcase.

See [audit](docs/AUDIT.md), [verification](docs/VERIFICATION.md) and [server showcase](docs/SERVERS.md).
