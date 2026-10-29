# Verification — testing candidate

Date: 2026-10-29. Version: `0.1.0-SNAPSHOT`. No GitHub release or tag has been published.

## Results

| Check | Result |
| --- | --- |
| Maven compile, package and tests on Java 21 | PASS |
| JUnit behavior tests | 8 passed, 0 failed, 0 skipped |
| Paper native runtime probes | 10/10 released versions, 22 assertions each |
| Leaf 26.3 build 13 | PASS, 22 assertions |
| Velocity 4.3.1 native proxy probes | PASS, 84 assertions across protocols 771–777 |
| BungeeCord build 2107 native proxy probes | PASS, 56 assertions across protocols 771–777 |
| Older Velocity releases | 3.4.0, 3.5.1 and 4.0.0 passed their supported protocol probes |
| Loopback client → Paper | PASS |
| Loopback client → Velocity → Paper | PASS |
| Loopback client → BungeeCord → Paper | PASS |
| Production source lines executed, including host-remapped adapters | 339/367, 92.4% |
| Production branches executed | 199/268, 74.3% |
| Whitespace/diff and public-content review | PASS |

Paper builds: 1.21.6 #48; 1.21.7 #32; 1.21.8 #60; 1.21.9 #59; 1.21.10 #130; 1.21.11 #132; 26.1.1 #29; 26.1.2 #74; 26.2 #135; 26.3 #229. Runtime probes use Java 25. Proxy protocol probes use the actual distribution JARs and native codecs, not replicas of their classes.

Machine-readable distribution hashes and candidate hashes are in [test-results.json](test-results.json).

## Behavioral evidence

The network fixture sends real chat packets from both a backend and proxy. A synthetic Minecraft 1.21.6 client receives native `custom` actions, clicks them, and checks the resulting server behavior. Exactly one permitted backend command runs on each of three independent connections. The backend permission denial remains effective, proxy command events see backend clicks, a proxy command runs locally, and a proxy event cancellation is respected. Fake identifiers and an incoming payload naming a different command do not execute that command.

Packet probes cover system chat, disguised chat, unsigned display components on signed player chat, Adventure messages, bundle contents, nested click events, preservation of original packet fields, immutable source objects, and native serialization. Token tests cover isolation between connections, expiry, bounded storage, deduplication and rate limiting. Native proxy probes additionally cover forwarded raw chat buffers and buffer release.

The initial failing tests used inert implementations of the registry, then passed after implementation. A separate failing proxy-adoption test required backend buttons to pass through proxy command events; it passed after adoption metadata was added. Network tests caught encode-only/raw packet handling in both proxy implementations before the candidate was finalized.

Coverage combines unit tests, real distribution probes and actual network sessions. Paper/Bungee runtime remapping changes class IDs; the report uses the exact executed bytes for their platform adapters, retaining source line mappings. The original-only Maven report therefore understates native coverage; see `qa/native_coverage.py`.

## Remaining acceptance test and scope

No Minecraft rendering/UI automation or test on the production network is claimed. The maintainer must personally test the original HuskHomes button, proxy messages, backend switching/reconnecting, permission denial and unchanged URL/suggest actions before approving a release. Multi-proxy deployments use independent local registries; no shared database is involved, but a live multi-proxy failover was not exercised.

The candidate targets command clicks in chat. Written books, arbitrary modal dialog definitions, Spigot-only servers, Folia, future protocols, every possible third-party plugin combination, and cross-machine protocol translation configurations are not part of the verified scope.
