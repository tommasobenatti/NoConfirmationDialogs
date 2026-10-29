# Verification — 1.0.1-stable

Date: 2026-10-29. SHA-256: `a5e10a3feb035a6385492326ad1de4910a66d2e4baf2830d7069223dfeba609d`. Maintainer: [Tommaso Benatti](https://github.com/tommasobenatti).

| Check | Result |
| --- | --- |
| Java 21 clean build and regression tests | 25 passed, no failures/errors/skips |
| Native Paper probes | All ten supported releases 1.21.6–26.3 passed,29 assertions each |
| Leaf 26.3 with exact IC/PE distribution set | 41 compatibility assertions passed |
| Native Velocity / Bungee protocol probes | 175 /105 assertions passed across protocols 771–777 |
| Older Velocity releases | 3.4.0,3.5.1 and 4.0.0 passed supported-protocol probes |
| Actual InteractiveChat/PE outbound pipeline | Direct Paper and through both proxies passed |
| Reported END-tag warning | Reproduced on 1.0-stable; absent with 1.0.1 |

[Compatibility report](COMPATIBILITY-1.0.1.md) documents actual plugin versions, repeated native conversions, long commands, fixture bounds and test limits. [Machine-readable results](test-results.json) bind checks to this artifact hash. Historical 1.0 security/resource audit and measurements remain in [AUDIT.md](AUDIT.md) and [performance-results.json](performance-results.json); their coverage/performance numbers are not new measurements of this corrective release.

No production server or third-party plugin configuration was changed. Current tests use real loopback hosts and a synthetic client, not full Minecraft GUI rendering. Books/modal definitions, pure Spigot/Folia, future protocols and arbitrary cross-machine translation placements remain outside supported scope.
