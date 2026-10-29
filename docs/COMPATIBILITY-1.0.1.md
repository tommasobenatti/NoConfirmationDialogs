# InteractiveChat / PacketEvents compatibility — 1.0.1-stable

Date: 2026-10-29. NCD SHA-256: `a5e10a3feb035a6385492326ad1de4910a66d2e4baf2830d7069223dfeba609d`.

## Reproduction and correction

The original `1.0-stable` JAR was loaded on Leaf 26.3 build 13 with actual InteractiveChat 2026.1.3.0, InteractiveChat-PacketEvents 1.3.1 and PacketEvents 2.14.0. The probe encoded NCD's chat using the native server codec, decoded it with PacketEvents, then called InteractiveChat's real native/shaded Adventure converter. It reproduced the reported root exception: `Unknown tag type: BinaryTagType[EndBinaryTag 0]`.

The absent custom-click payload was represented by PacketEvents as an END tag, which its SNBT string conversion could not serialize. NCD now emits the tiny explicit JSON compatibility value `payload: "{}"`. Native/Adventure serializers may normalize its textual NBT representation; its semantic content is inert, bounded, and never used to select a command. The command remains solely in the server registry and directional backend registration channel. No InteractiveChat or PacketEvents binary/configuration was patched.

## Actual distribution checks

| Check | Result |
| --- | --- |
| Original 1.0-stable, exact plugins, native conversion | Reported END-tag failure reproduced |
| Corrected 1.0.1-stable, same plugins, Leaf 26.3 | 41 assertions passed |
| Additional IC → native → PE → IC re-encoding | Three further passes for each command; token stable and payload bounded |
| Command cases | Accept, decline and maximum 32767-character command |
| Unrelated URL action | Preserved |
| Actual outbound pipeline/client on Paper 1.21.6 | Passed with all exact plugins required enabled |
| Same backend through Velocity and BungeeCord | Both passed |
| Target warning in live backend log | Absent |
| Registry integrity, fake registration/payload, permissions and proxy cancellation | Existing network assertions passed |

The network test used PlaceholderAPI 2.12.3 and Vault 1.7.3-b 131 as InteractiveChat's required dependencies. It allocated 1536 MiB to this disposable full-stack backend; the minimal 512 MiB fixture had been insufficient for InteractiveChat's classpath scan. No production heap/configuration settings were changed. The Leaf converter probe used 768 MiB and warmed the normal message handler while the plugin was enabled, avoiding shutdown-before-first-message fixture artifacts.

Official distribution inputs: [PacketEvents 2.14.0](https://github.com/retrooper/packetevents/releases/tag/v2.14.0), [InteractiveChat Maven distribution](https://repo.loohpjames.com/repository/com/loohp/InteractiveChat/2026.1.3.0/), [IC PacketEvents addon 1.3.1](https://github.com/TerraByteDev/InteractiveChat-PacketEvents/releases/tag/1.3.1). Exact hashes and captured version/PASS markers are retained in [compatibility-results-1.0.1.json](compatibility-results-1.0.1.json). Test sources and instructions are in [qa/README.md](../qa/README.md). Third-party JARs are not included in NCD or committed to this repository.

## Limits and rollout

Tests exercise real codecs, plugin conversion and an actual synthetic-client packet pipeline. They do not replace a visual Minecraft test or claim every plugin/version combination. Existing token ownership, command permissions, rate/native packet limits and directional registrations are unchanged.

Replace NCD with the same 1.0.1-stable JAR wherever installed, restart and reconnect. No new client mod, other-plugin update or message/configuration edit is required. The original 1.0-stable tag/release is preserved.
