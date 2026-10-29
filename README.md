# NCD — No Confirmation Dialogs

Click a command in chat and run it immediately, without Minecraft's **Confirm Command Execution** screen.

NCD is a drop-in plugin for **Paper, Leaf, Velocity and BungeeCord**. One JAR works on all three plugin platforms. Players use ordinary Minecraft clients. No ProtocolLib, PacketEvents, database, Redis, client mod, or changes to other plugins' messages are required.

**Status: testing candidate, `0.1.0-SNAPSHOT`. A public release is pending the maintainer's in-game acceptance test.**

## Installation

1. Stop the server and put `NoConfirmationDialogs-0.1.0-SNAPSHOT.jar` in `plugins/`.
2. Start the server. NCD attaches automatically to each player's connection.
3. Click an existing chat command, for example a teleport request's accept button.

For a network, install **the same JAR on the proxy and each Paper/Leaf backend**. This covers messages created on either side and preserves proxy command events before backend dispatch. No shared credentials, plugin messaging setup, or external storage is needed. Each proxy handles its own connected players; multiple proxies work independently.

On a standalone server, install only on Paper/Leaf. Installing only on a proxy covers proxy messages and backend system-chat packets, but signed/decorated player chat also needs the backend installation.

NCD has no configuration files, player messages, permission grants, metrics, update downloads or management commands. There is nothing to merge into existing plugin configuration. Restart after installing, updating or removing it; reconnect to refresh old chat buttons.

## Compatibility

- Minecraft releases with this confirmation screen: **1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11, 26.1.1, 26.1.2, 26.2 and 26.3**.
- Paper: native packet probes are run across every listed release. Leaf uses the Paper adapter; see [verification](docs/VERIFICATION.md) for the tested build.
- Velocity and BungeeCord must themselves support the player's Minecraft version. Native proxy adapters use the installed proxy's protocol registry rather than fixed outgoing chat packet IDs.
- Java 21 bytecode; run the Java version required by your server/proxy (Java 25 for current Minecraft 26.x).
- Spigot and Bukkit without Paper, Folia, snapshots and future Minecraft protocols are not claimed as supported.
- Clients older than 1.21.6 do not need this feature. Proxy handlers skip them; Paper skips them when a locally installed ViaVersion reports their protocol. Translation setups with ViaVersion only on a different machine are not part of the verified matrix.

Support for a version means tested native packet layouts, not every third-party plugin combination. See [verification](docs/VERIFICATION.md) for the exact automated tests and remaining manual acceptance test.

## What is converted

NCD inspects outgoing **chat** packets, including ordinary plugin messages, `/tellraw`, nested text components, Adventure/Bungee messages, unsigned decorations on player chat, disguised chat, and bundled chat packets on Paper. Colors, text, hover information and other click actions remain intact. A namespaced command such as `/huskhomes:tpaccept Alice` works without changing its plugin's locale file.

Only `run_command` actions are replaced. URL prompts, `suggest_command`, clipboard actions, unrelated custom clicks, and purpose-built confirmation menus stay as they are. Written-book item contents and modal dialog definitions are outside this chat-focused candidate's coverage.

Normal command authorization remains in effect. NCD removes the extra client confirmation; it does not grant permission or execute as console. Commands requiring client-signed message arguments remain subject to the server's unsigned-command restrictions. Proxy/backend plugins that reject commands may still reject a click.

## How it works

The server replaces a command click with a native `custom` click carrying an unpredictable identifier. A bounded, per-connection in-memory registry maps that identifier to the original command. When clicked, NCD converts it into the normal unsigned command path. Paper and Velocity use their native command packets; BungeeCord fires `ChatEvent`, uses its permission-aware command dispatcher, and forwards unhandled commands to the current backend.

With NCD at both levels, a proxy adopts server-authored backend click metadata and issues its own session token. The eventual command therefore passes through proxy command events and backend command handling. No command supplied in an incoming payload is trusted. Old backend tokens naturally become invalid after reconnecting or switching to a new backend connection.

Registries retain at most 4,096 distinct commands per connection for 12 hours, deduplicate repeated commands and accept at most 20 resolved clicks per second. Expired or evicted buttons require a fresh message. Unknown identifiers owned by another NCD layer are forwarded to that layer.

## Build and testing

```sh
mvn clean verify
```

The JAR is written to `target/NoConfirmationDialogs-0.1.0-SNAPSHOT.jar`. Dependencies are compile-time APIs supplied by the host; they are not shaded or downloaded at runtime.

See [qa/README.md](qa/README.md) for isolated Paper, proxy and synthetic-client checks, and [research notes](docs/RESEARCH.md) for the upstream sources. CI builds testing artifacts but does not publish GitHub Releases or tags.

## Credits

The native custom-click approach was inspired by [DrexHD/remove-dialog-warning](https://github.com/DrexHD/remove-dialog-warning), a Fabric server mod. NCD is a separate implementation for the Paper and proxy plugin ecosystems; no Fabric code or runtime is bundled.
