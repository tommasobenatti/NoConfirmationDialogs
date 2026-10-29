# Audit and resource review — 1.0-stable

Completed on 2026-10-29, before the first stable release. The creator and maintainer is [Tommaso Benatti](https://github.com/tommasobenatti).

## Scope and result

An independent baseline review read all 35 files tracked in the original candidate. A separate architecture review and targeted native-wire review traced modified-client inputs through Paper/Leaf, Velocity and BungeeCord. The parent validated the source paths, applied fixes and ran new regression/runtime tests. Supporting native host code was checked offline. This is an engineering audit, not an exploit-free certification.

Two conditional security-control gaps in the unreleased candidate were fixed:

| Finding | Prerequisite and impact | Stable control and verification |
| --- | --- | --- |
| Proxy command-policy bypass | A legitimate Paper token in decorated/disguised chat could execute a backend-permitted command without a proxy-only command denial seeing it. No arbitrary command replacement or backend permission escalation. | Native backend-to-proxy token registrations independent of chat type; proxy resolves imported aliases and consumes unknown Paper tokens. A real disguised-chat button is allowed standalone but denied through both proxies. |
| Consumed-request packet-budget bypass | Unknown NCD token floods were consumed before Bungee's generic packet/byte counter and did not consume the valid-click limiter. No service-outage threshold was claimed. | Every consumed request has independent NCD count/byte limits; Bungee's exact native limiter is also charged. Paper's native global/custom-click limits and DROP/KICK policies are preserved, including valid tokens dropped before conversion. |

Additional hardening corrected an attach/disable race, close-future listeners retaining disabled managers, count-only command-text storage, incomplete deep-copy budgets, independent proxy alias eviction, and protocol-probe fixtures that previously reused protocol 771. A separate regression rejects overlong raw packet IDs such as C1 00/C4 00 before they can bypass proxy routing. No client-provided registration frame or command payload is trusted. Client click components carry only a token; removing command-sized echo metadata also avoids native NBT echo limits for long commands.

## Bounds and lifecycle

- Per connection: at most 4,096 entries and 262,144 retained command characters. Pending exports reuse canonical command strings. The text bound is at most 512 KiB of UTF-16 character data, excluding map/token overhead; it is not a whole-heap measurement.
- Tokens have a 12-hour validity lifetime and lazy expiry. Expired entries can remain allocated within the bound until later registration/resolution or session cleanup.
- At most 20 resolved clicks and 100 consumed requests / 1 MiB request data per fixed one-second window. Paper estimates decoded NBT size conservatively. Invalid envelopes or excess requests disconnect that client.
- Native host packet limits remain relevant and are charged for consumed traffic. Generic global Paper counting is deferred only when a converted command will actually enter Connection; DROP paths charge the original request.
- JSON transformation is bounded to 64 levels, 8,192 nodes and 262,144 aggregate string characters before recursive copying. Exceptional larger components retain their original form.
- No production worker pool, timer, database, HTTP client or telemetry is introduced. Channel removal clears registries; closeFuture callbacks no longer retain disabled managers. Deterministic tests cover concurrent close and listener cleanup, including 200 attach/disable cycles.

## Measured resource comparison

The same component/registry harness was run in three fresh sequential JVMs per version, on an idle host with Corretto Java 25.0.5, macOS arm64, `-Xms128m -Xmx512m`. Baseline and stable artifact hashes, all runs and medians are retained in [performance-results.json](performance-results.json); the harness is [qa/PerfProbe.java](../qa/PerfProbe.java).

| Metric, median | Candidate baseline | 1.0-stable |
| --- | ---: | ---: |
| Plain component | 19.6 ns/op | 19.8 ns/op |
| Clickable component | 479.2 ns/op | 279.6 ns/op |
| Clickable component allocation | 1774.8 B/op | 1133.9 B/op |
| Unknown-token lookup | 26.3 ns/op | 25.8 ns/op |
| Command characters retained after 4,096 distinct 8 KiB commands | 33,594,282 | 254,262 |
| Entries retained in that stress case | 4,096 | 31 |

The admitted text in this stress case fell by **99.24%**. In this workload, clickable-component median time fell by 41.7% and measured allocation by 36.1%; plain-component time stayed within short-run variation. Removing command echo metadata offset the additional traversal checks. This microbenchmark excludes host serialization, native packet publication, full-server TPS, network latency and total retained JVM heap. It is not a production capacity estimate. Native packet metadata is cached to avoid repeated reflective layout discovery; unrelated raw packet IDs avoid unnecessary buffer duplicates.

## Limits of assurance

Server/plugins able to author chat actions remain trusted message sources. Clients may inspect and replay their own valid tokens within the limits, but cannot nominate a replacement command through an incoming payload. Current platform permissions, command events and signed-argument restrictions remain authoritative.

Tests use disposable loopback hosts and a synthetic protocol client; they do not prove all third-party combinations, future protocols, every protocol-translator placement or multi-proxy failover. The user personally confirmed the original chat behavior in-game before authorizing stable publication; the additional fixes were verified through regressions and host probes. No production configuration was changed by this audit.
