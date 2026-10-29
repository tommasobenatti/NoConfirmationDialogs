# Changelog

## 1.0-stable — 2026-10-29

First stable release, created and maintained by Tommaso Benatti (`tommasobenatti`).

- One native JAR for Paper/Leaf, Velocity and BungeeCord; automatic chat command conversion with no external plugins or storage.
- Backend-to-proxy token registration covers system, disguised and unsigned decorated player chat while preserving proxy command checks.
- Commands are resolved from per-connection server registries, with no client command echo metadata.
- Fixed pre-release proxy-policy and consumed-request packet-budget gaps. Added strict framing, PLAY guards, native packet-counter accounting, request/data caps and aggregate memory bounds.
- Fixed attach/disable races and close-listener retention; cached native packet layouts and reduced component allocations.
- Added adversarial, lifecycle, resource and actual native-host probes, plus installation coverage comparison, author attribution, download/view badges and MCExp server showcase.

See [audit](docs/AUDIT.md), [verification](docs/VERIFICATION.md) and [server showcase](docs/SERVERS.md).
