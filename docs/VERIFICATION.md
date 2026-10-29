# Verification — 1.0-stable

Date: 2026-10-29. Maintainer: [Tommaso Benatti](https://github.com/tommasobenatti). SHA-256: `b9ff787c2cb54bd934746b8b3d077e4a52e1931a39535320c07bde1f4cc97bad`.

| Check | Result |
| --- | --- |
| Java 21 build and JUnit | 24 passed; no failed, errored or skipped tests |
| Paper native runtime probes | 10/10 supported released versions; 29 assertions per version |
| Leaf 26.3 build 13 | 29 assertions passed |
| Velocity 4.3.1 native protocol probes | 175 assertions; actual protocols 771–777 |
| BungeeCord build 2107 native protocol probes | 105 assertions; actual protocols 771–777 |
| Older Velocity | 3.4.0, 3.5.1 and 4.0.0 passed their supported protocol probes |
| Direct client → Paper | PASS |
| Client → Velocity → Paper | PASS |
| Client → BungeeCord → Paper | PASS |
| Source lines executed, including actual host-remapped adapters | 571/609, 93.8% |
| Branches executed | 370/512, 72.3% |

Paper builds: 1.21.6 #48; 1.21.7 #32; 1.21.8 #60; 1.21.9 #59; 1.21.10 #130; 1.21.11 #132; 26.1.1 #29; 26.1.2 #74; 26.2 #135; 26.3 #229. Java 25 runs the isolated native hosts. [Machine-readable results](test-results.json) retain distribution and plugin hashes.

## What was verified

The real loopback client receives existing chat actions, sends a manipulated command payload, forges a registration message for an otherwise permitted command and clicks its fake token. These inputs do not nominate a command. Exactly one legitimate backend command executes on each connection; permissions still reject a restricted command.

A disguised-chat command is permitted by the standalone backend but denied by each proxy. With both layers installed it passes through native proxy command events and never executes at the backend. Proxy-created commands execute normally and cancelled events remain cancelled.

Native probes use each actual protocol object, both raw and decoded traffic, native codecs and retained buffers. They cover 0x41/0x44 packet IDs, malformed/overflowing/overlong VarInts, a live backend alias behind an overlong packet ID, control-frame imports, reader-index preservation and release. Bungee probes preserve a stricter native packet limit for junk tokens. Paper probes preserve a configured custom-click DROP policy, keep the client connected for DROP and charge its global counter exactly once.

Unit regressions cover cross-session tokens, expiry, aggregate text bounds, malformed registrations, independent alias eviction, phase changes, request count/data budgets, deep/oversized trees, concurrent attach/disable, close-listener cleanup and 200 lifecycle cycles. Maximum-length commands do not create a client echo payload.

Paper probes preserve packet fields and cover system chat, disguised chat, unsigned display content on player-chat packets, Adventure components, bundles and registration-before-chat ordering. Signed message bodies and signatures are preserved; this is not a cryptographic validation of every signed-chat scenario.

## Coverage and limits

Paper/Bungee remap classes before loading, changing their JaCoCo IDs. The report uses exact dumped runtime bytes for platform-only adapters with original source line mappings; shared classes use their normal build bytes. See `qa/native_coverage.py`. This is test coverage, not a security guarantee.

[Audit and performance details](AUDIT.md), [raw performance data](performance-results.json), and [reproduction commands](../qa/README.md) document limits. No server TPS, service-outage threshold, full GUI rendering matrix, production deployment, live multi-proxy failover or every third-party/translator combination is claimed. The maintainer personally confirmed the original chat behavior in-game and explicitly authorized stable publication after this audit.
