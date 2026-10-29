# Research and implementation decisions

Research checked on 2026-10-29.

- [Mojang: snapshot 25w20a](https://www.minecraft.net/en-us/article/minecraft-snapshot-25w20a): introduced confirmation for command click actions that cannot be parsed or require elevated permission, and the native custom click action. The feature shipped in Minecraft 1.21.6.
- [DrexHD/remove-dialog-warning](https://github.com/DrexHD/remove-dialog-warning/tree/e0491aab86acb982c987b1100ec59cc16ff5bdf9): server-side Fabric implementation converts command actions into custom actions. Its component mixin also applies to chat. NCD uses this protocol idea with independently written adapters, rather than depending on Fabric mixins.
- [Paper dialog/custom-click API](https://docs.papermc.io/paper/dev/dialogs/): documents native custom actions and responses. The public API alone does not intercept messages sent by every other plugin, and is newer than the earliest target release, so NCD uses native connection interception.
- [Velocity command event signing states](https://jd.papermc.io/velocity/4.2.1/com/velocitypowered/api/event/command/CommandExecuteEvent.SignedState.html): ordinary clickable commands use the unsigned command route. NCD preserves that route rather than executing as console or bypassing command events.
- [Velocity StateRegistry](https://github.com/PaperMC/Velocity/blob/dev/4.0.0/proxy/src/main/java/com/velocitypowered/proxy/protocol/StateRegistry.java): system chat is encode-only in its registry and PLAY custom clicks pass through as raw bytes. NCD handles both decoded proxy messages and these forwarded buffers, using each player's protocol.
- [BungeeCord Protocol](https://github.com/SpigotMC/BungeeCord/blob/master/protocol/src/main/java/net/md_5/bungee/protocol/Protocol.java): system chat is encode-only; custom clicks are decoded. PLAY custom-click IDs are 0x41 for protocols 771–774 and 0x44 for 775–777. Outgoing IDs are resolved from the installed registry.
- [BungeeCord UpstreamBridge](https://github.com/SpigotMC/BungeeCord/blob/master/proxy/src/main/java/net/md_5/bungee/connection/UpstreamBridge.java): native command handling fires ChatEvent, uses dispatchCommand and forwards unhandled commands. NCD follows this sequence because Player.chat rejects modern client protocols.
- [CMI compatibility request](https://github.com/Zrips/CMI/issues/9827): documents the same user-facing friction and proposes custom actions; the issue is closed as not planned. It is not an available general-purpose fix.
- [No Command Confirm](https://modrinth.com/mod/nocc) and [remove-dialog-warning](https://modrinth.com/mod/remove-dialog-warning): Fabric mods, not drop-in Paper plugins.
- [SpigotMC Command Confirmation](https://www.spigotmc.org/resources/command-confirmation-1-8-1-16-1-add-gui-confirmation-to-any-command.81426/): adds command confirmations, rather than removing Minecraft's native screen.
- [Leaf downloads](https://www.leafmc.one/en/download) and [source](https://github.com/Winds-Studio/Leaf/tree/ver/26.3): Paper-derived server; validated with the Paper adapter.

No maintained, verified plugin matching the entire requested Paper + proxy automatic chat-conversion workflow was found in the checked GitHub, SpigotMC and Modrinth results. This is a scoped research result, not proof that no such project exists.

## Tradeoffs

The production JAR has no bundled packet library and needs no other installed plugin. Small reflective adapters avoid linking the JAR to one NMS version. Native packet names/layouts can still change, so future releases require validation rather than an unlimited compatibility promise. Proxy raw inbound custom-click IDs are explicitly bounded to tested protocol families.

The first candidate focuses on chat, which is the reported HuskHomes problem. Supporting book item data and arbitrary modal dialog definitions would require additional packet/data traversal and is not advertised here.
