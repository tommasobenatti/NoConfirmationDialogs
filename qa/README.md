# Reproducing the checks

Run `mvn clean verify` first. Tests exercise nested components, malformed commands, immutable originals, token isolation, expiry, bounded storage, throttling, proxy adoption, forwarding, buffer ownership and conversion failures.

## Paper matrix

Use Java 25 on PATH (needed by 26.x), Python 3.9+, and a disposable work directory:

```sh
python3 qa/paper_matrix.py --work-dir /tmp/ncd-tests --accept-eula
```

`--accept-eula` is required because the script creates local Minecraft test servers. Downloads come from Paper's official API and are SHA-256 verified. Each server binds to loopback, has its own directory and shuts itself down after runtime assertions. The script writes `paper-matrix.json` with distribution and candidate hashes.

The separate `NcdRuntimeProbe` fixture is packaged from test classes; it is never included in NCD's production JAR. Runtime probes cover native JSON codecs, system/player/disguised chat, Adventure messages, bundles, preservation of other packet fields, custom-click roundtrips and the unsigned command packet path. They do not render a Minecraft UI.

Do not run two checks against the same runtime directory at once.

## Proxy packets

Download official Velocity and BungeeCord distributions to your work directory as `velocity.jar` and `BungeeCord.jar`. Use versions which support Minecraft 1.21.6 or later. Then:

```sh
javac --release 21 -cp target/classes:/tmp/ncd-tests/velocity.jar:/tmp/ncd-tests/BungeeCord.jar -d /tmp/ncd-tests/probe-classes qa/ProxyProbe.java
java -Dterminal.jline=false -cp /tmp/ncd-tests/probe-classes:target/classes:/tmp/ncd-tests/velocity.jar io.github.tommasobenatti.ncd.ProxyProbe velocity
java -cp /tmp/ncd-tests/probe-classes:target/classes:/tmp/ncd-tests/BungeeCord.jar io.github.tommasobenatti.ncd.ProxyProbe bungee
```

These probes use the actual proxy distribution classes, including serializers, raw forwarded chat and retained buffers. No proxy source is copied into the production JAR.

## Network command routing

1. Finish the Paper matrix so `runtime-1.21.6` and its fixture exist.
2. Install the exact test dependency from `qa/package.json` in the work directory (for example with pnpm). The script expects `node_modules/minecraft-protocol` there.
3. Compile proxy fixtures:

```sh
javac --release 21 -cp /tmp/ncd-tests/velocity.jar -d /tmp/ncd-tests/proxy-qa-classes qa/VelocityProbePlugin.java
javac --release 21 -cp /tmp/ncd-tests/BungeeCord.jar -d /tmp/ncd-tests/proxy-qa-classes qa/BungeeProbePlugin.java
python3 qa/network_smoke.py --work-dir /tmp/ncd-tests --node /absolute/path/to/node
```

The script starts an offline loopback backend, then tests a direct connection and connections through both proxies. It checks proxy-created and backend-created buttons, a permitted backend command, a denied backend permission, a cancelled proxy event, a proxy command, forged identifiers and forged command payloads. The client completes configuration, sends settings and acknowledges its initial teleport before clicking. The disguised-chat command is allowed on the standalone backend but denied by each proxy: the test asserts it executes exactly once across all three connections. Results are written to `network-results.json`.

`client.cjs` writes custom-click packets explicitly because the tested minecraft-data definition omitted the native length prefix around the NBT payload. This workaround is in the test client only.

For coverage, set `NCD_JACOCO_AGENT` to the local JaCoCo runtime agent JAR before running the Python scripts and use `-javaagent` for the standalone proxy probes (write `target/proxy-velocity.exec` and `target/proxy-bungee.exec`, including `io.github.tommasobenatti.ncd.*`). Then run `mvn -Pintegration-coverage verify` to merge execution files.

Paper and Bungee remap platform classes before loading, which changes their JaCoCo class IDs. For the full source-line report, run `python3 qa/native_coverage.py --jacoco-cli /path/to/org.jacoco.cli-0.8.14-nodeps.jar`. It uses the exact executed, dumped adapter classes for those platforms and the original shared classes for the rest. It fails below 80% line coverage. Reports and all execution files stay under ignored `target/`.

## Manual acceptance

For each release, the maintainer should test a normal Minecraft client on the intended network: HuskHomes accept/decline, plugin chat menus, proxy commands, a backend change/reconnect, permission denial, and unchanged URL/suggest-command actions. Server-version probes and a synthetic client do not replace that visual test. Publishing a release requires the maintainer's explicit approval.

## Resource microbenchmark

Compile `qa/PerfProbe.java` with the same dependency classpath as the project and run it against the baseline and release classes. It warms each path, reports per-thread allocation and nanoseconds per operation, and inserts 4,096 distinct commands with 8 KiB bodies. Repeat in three fresh JVMs with `-Xms128m -Xmx512m`. This measures a small component/registry workload; it is not a server TPS or network latency result. The stable run must keep admitted text under the aggregate budget. See `docs/performance-results.json` for retained measurements.

## InteractiveChat / PacketEvents integration

`InteractiveChatProbe.java` is a separate test plugin, never included in NCD's JAR. It uses the real native system-chat encoder, PacketEvents wrapper/decoder, InteractiveChat native/shaded Adventure converters, and three additional native re-encode passes. It checks exact token/command preservation, unrelated URL actions, bounded inert payloads, and maximum-length commands.

Provide official JARs in a disposable `WORK/plugins-source`: InteractiveChat 2026.1.3.0, InteractiveChat-PacketEvents 1.3.1, PacketEvents 2.14.0 (Spigot), plus required PlaceholderAPI/Vault dependencies. Compile the probe against Paper's dependency classpath and the PacketEvents JAR into `WORK/probe-classes`. Run `qa/interactivechat_smoke.py --help` for explicit host/cache/JAR arguments. Use the original 1.0-stable JAR with `--expect-red` to reproduce the END failure; use 1.0.1-stable without that flag for the corrected run. `--accept-eula` is required.

The baseline passes only if it reproduces the original END-tag failure; the corrected run must pass compatibility assertions with real plugins enabled and no target conversion warning. It records artifact hashes and exact plugin versions. Dependencies are test inputs; they are neither modified nor bundled in NCD.

For a real outbound pipeline/client test, stage the same third-party JARs in the disposable `runtime-1.21.6/plugins` and run the network fixture with `--interactivechat --backend-heap 1536M`. It requires the exact three plugins enabled before continuing. The higher heap is only for the developer fixture's full stack: the minimal 512 MiB test heap was insufficient for InteractiveChat's initial classpath scan. No production memory setting changes. Remove staged third-party JARs before running the pure NCD version matrix again.
