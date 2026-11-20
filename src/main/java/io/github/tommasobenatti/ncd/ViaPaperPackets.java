package io.github.tommasobenatti.ncd;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.packet.provider.PacketTypesProvider;
import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CodecException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

/** Handles client-protocol packets on legacy Paper, on the client side of ViaVersion's codecs. */
final class ViaPaperPackets implements PacketAdapter {
    private static final CodecException CANCELLED = new CodecException();
    private final UserConnection user;
    private final String decoder;
    private final int systemChat, disguisedChat, playerChat, customClick, chatCommand, customPayload;

    static void attach(UUID player, Channels channels) {
        var user = Via.getAPI().getConnection(player);
        if (user == null || user.isClientSide() || !user.shouldTransformPacket()) return;
        var info = user.getProtocolInfo();
        var client = info.protocolVersion();
        var server = info.serverProtocolVersion();
        if (client.getVersion() < NcdPlugin.CUSTOM_CLICK_PROTOCOL || client.getVersion() > NcdPlugin.LAST_TESTED_PROTOCOL) return;
        var manager = Via.getManager();
        var path = manager.getProtocolManager().getProtocolPath(client, server);
        if (path == null || path.isEmpty()) return;

        var injector = manager.getInjector();
        var encoder = injector.getEncoderName();
        var decoder = injector.getDecoderName();
        var channel = user.getChannel();
        if (channel.pipeline().get(encoder) == null || channel.pipeline().get(decoder) == null) return;
        var packets = new ViaPaperPackets(user, path.getFirst().protocol().getPacketTypesProvider(), decoder);
        // Separate anchors keep both handlers between ViaVersion and the respective compression codecs.
        channels.attach(channel, decoder, encoder, packets, "paper", packets::playing);
    }

    ViaPaperPackets(UserConnection user, PacketTypesProvider<?, ?, ?, ?> types, String decoder) {
        this.user = user;
        this.decoder = decoder;
        systemChat = id(types.mappedClientboundType(State.PLAY, "SYSTEM_CHAT"), "SYSTEM_CHAT");
        disguisedChat = id(types.mappedClientboundType(State.PLAY, "DISGUISED_CHAT"), "DISGUISED_CHAT");
        playerChat = id(types.mappedClientboundType(State.PLAY, "PLAYER_CHAT"), "PLAYER_CHAT");
        customPayload = id(types.mappedClientboundType(State.PLAY, "CUSTOM_PAYLOAD"), "CUSTOM_PAYLOAD");
        customClick = id(types.unmappedServerboundType(State.PLAY, "CUSTOM_CLICK_ACTION"), "CUSTOM_CLICK_ACTION");
        chatCommand = id(types.unmappedServerboundType(State.PLAY, "CHAT_COMMAND"), "CHAT_COMMAND");
    }

    private static int id(PacketType type, String name) {
        return Objects.requireNonNull(type, "Missing ViaVersion packet type " + name).getId();
    }

    private boolean playing() {
        var info = user.getProtocolInfo();
        return info.getClientState() == State.PLAY && info.getServerState() == State.PLAY;
    }

    @Override public Object rewrite(Object packet, CommandClicks clicks) {
        if (!(packet instanceof ByteBuf raw) || !raw.isReadable()) return packet;
        int id = raw.getUnsignedByte(raw.readerIndex());
        // The supported chat IDs fit in one byte.
        if (id != playerChat && id != systemChat && id != disguisedChat) return packet;
        if (!playing() || !ClickWire.hasCommand(raw)) return packet;
        ByteBuf input = raw.duplicate();
        input.skipBytes(1);
        if (id == playerChat) {
            skipSignedChatFields(input);
            if (!input.readBoolean()) return packet; // Unsigned decoration is optional.
        }
        int start = input.readerIndex();
        Tag component = Types.TAG.read(input);
        if (!rewriteComponent(component, clicks)) return packet;

        ByteBuf output = raw.alloc().buffer(raw.readableBytes());
        try {
            output.writeBytes(raw, raw.readerIndex(), start - raw.readerIndex());
            Types.TAG.write(output, component);
            return output.writeBytes(input);
        } catch (RuntimeException ex) {
            output.release();
            throw ex;
        }
    }

    private static void skipSignedChatFields(ByteBuf input) {
        Types.VAR_INT.readPrimitive(input); // Global message index
        input.skipBytes(16); // Sender UUID
        Types.VAR_INT.readPrimitive(input); // Sender message index
        if (input.readBoolean()) input.skipBytes(256); // Signature
        int length = Types.VAR_INT.readPrimitive(input);
        if (length < 0) throw new IllegalArgumentException("Invalid chat message length");
        input.skipBytes(length); // Plain message
        input.skipBytes(16); // Timestamp and salt
        int seen = Types.VAR_INT.readPrimitive(input);
        if (seen < 0 || seen > 20) throw new IllegalArgumentException("Invalid last-seen message count");
        for (int i = 0; i < seen; i++) {
            if (Types.VAR_INT.readPrimitive(input) == 0) input.skipBytes(256);
        }
    }

    private record Node(Tag tag, int depth) { }
    private record Action(CompoundTag parent, String command) { }

    private static boolean rewriteComponent(Tag component, CommandClicks clicks) {
        var nodes = new ArrayDeque<Node>();
        var actions = new ArrayList<Action>();
        nodes.push(new Node(component, 0));
        int visited = 0, characters = 0;
        while (!nodes.isEmpty()) {
            var node = nodes.pop();
            if (++visited > CommandClicks.MAX_COMPONENT_NODES || node.depth() > CommandClicks.MAX_COMPONENT_DEPTH) return false;
            int depth = node.depth() + 1;
            if (node.tag() instanceof CompoundTag compound) {
                var click = compound.getCompoundTag("click_event");
                if (click != null && "run_command".equals(click.getString("action"))) {
                    var command = click.getStringTag("command");
                    if (command != null) actions.add(new Action(compound, command.getValue()));
                }
                if (compound.size() > CommandClicks.MAX_COMPONENT_NODES - visited - nodes.size()) return false;
                for (var entry : compound.entrySet()) {
                    characters += entry.getKey().length();
                    nodes.push(new Node(entry.getValue(), depth));
                }
            } else if (node.tag() instanceof ListTag<?> list) {
                if (list.size() > CommandClicks.MAX_COMPONENT_NODES - visited - nodes.size()) return false;
                for (Tag child : list) nodes.push(new Node(child, depth));
            } else if (node.tag() instanceof StringTag string) {
                characters += string.getValue().length();
            }
            if (characters > CommandClicks.MAX_CHARACTERS) return false;
        }

        boolean changed = false;
        for (var action : actions) {
            var token = clicks.register(action.command());
            if (token.isEmpty()) continue;
            var replacement = new CompoundTag();
            replacement.putString("action", "custom");
            replacement.putString("id", token.get());
            replacement.putString("payload", "{}");
            action.parent().put("click_event", replacement);
            changed = true;
        }
        return changed;
    }

    @Override public String clickId(Object packet) {
        if (!(packet instanceof ByteBuf raw) || !raw.isReadable()) return null;
        int first = raw.getUnsignedByte(raw.readerIndex());
        if (((first & 128) == 0 && first != customClick) || !playing()) return null;
        ByteBuf input = raw.duplicate();
        return ClickWire.readVarInt(input) == customClick ? ClickWire.readClickId(input) : null;
    }

    @Override public boolean account(ChannelHandlerContext context, Object packet, boolean commandWillRun) {
        return user.checkServerboundPacket(((ByteBuf) packet).readableBytes());
    }

    @Override public void execute(ChannelHandlerContext context, String command) {
        var destination = context.pipeline().context(decoder);
        if (destination == null) throw new IllegalStateException("Missing ViaVersion decoder");
        ByteBuf packet = context.alloc().buffer();
        try {
            Types.VAR_INT.writePrimitive(packet, chatCommand);
            Types.STRING.write(packet, command);
            if (user.shouldTransformPacket()) user.transformServerbound(packet, ignored -> CANCELLED);
        } catch (RuntimeException ex) {
            packet.release();
            if (ex == CANCELLED) return;
            throw ex;
        }
        // Via has translated the command and already charged the original click.
        destination.fireChannelRead(packet);
    }

    @Override public void publish(ChannelHandlerContext context, CommandClicks clicks) {
        for (var registration : clicks.drainExports()) {
            ByteBuf packet = context.alloc().buffer();
            try {
                Types.VAR_INT.writePrimitive(packet, customPayload);
                Types.STRING.write(packet, RegistrationFrame.CHANNEL);
                packet.writeBytes(RegistrationFrame.encode(registration));
            } catch (RuntimeException ex) {
                packet.release();
                throw ex;
            }
            context.write(packet);
        }
    }
}
