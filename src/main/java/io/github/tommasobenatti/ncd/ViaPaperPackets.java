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
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

/** Handles client-protocol packets on legacy Paper, on the client side of ViaVersion's codecs. */
final class ViaPaperPackets implements PacketAdapter {
    private static final String TOKEN_PREFIX = "ncd:paper/";
    private static final int MAX_COMPONENT_DEPTH = 64;
    private static final int MAX_COMPONENT_NODES = 8192;
    private final UserConnection user;
    private final int systemChat, disguisedChat, playerChat, customClick, chatCommand, customPayload;

    static void attach(UUID player, Channels channels) {
        var user = Via.getAPI().getConnection(player);
        if (user == null || user.isClientSide() || !user.shouldTransformPacket()) return;
        var info = user.getProtocolInfo();
        var client = info.protocolVersion();
        var server = info.serverProtocolVersion();
        if (client.getVersion() < NcdPlugin.CUSTOM_CLICK_PROTOCOL) return;
        var manager = Via.getManager();
        var path = manager.getProtocolManager().getProtocolPath(client, server);
        if (path == null || path.isEmpty()) return;

        var injector = manager.getInjector();
        var encoder = injector.getEncoderName();
        var decoder = injector.getDecoderName();
        var channel = user.getChannel();
        if (channel.pipeline().get(encoder) == null || channel.pipeline().get(decoder) == null) return;
        var packets = new ViaPaperPackets(user, path.getFirst().protocol().getPacketTypesProvider());
        // Separate anchors keep both handlers between ViaVersion and the respective compression codecs.
        channels.attach(channel, decoder, encoder, packets, "paper", packets::playing);
    }

    private ViaPaperPackets(UserConnection user, PacketTypesProvider<?, ?, ?, ?> types) {
        this.user = user;
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
        if (!(packet instanceof ByteBuf raw) || !raw.isReadable() || !playing()) return packet;
        ByteBuf input = raw.duplicate();
        int id = Types.VAR_INT.readPrimitive(input);
        if (id == playerChat) {
            skipSignedChatFields(input);
            if (!input.readBoolean()) return packet; // Unsigned decoration is optional.
        } else if (id != systemChat && id != disguisedChat) {
            return packet;
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
            if (++visited > MAX_COMPONENT_NODES || node.depth() > MAX_COMPONENT_DEPTH) return false;
            int depth = node.depth() + 1;
            if (node.tag() instanceof CompoundTag compound) {
                var click = compound.getCompoundTag("click_event");
                if (click != null && "run_command".equals(click.getString("action"))) {
                    var command = click.getStringTag("command");
                    if (command != null) actions.add(new Action(compound, command.getValue()));
                }
                if (compound.size() > MAX_COMPONENT_NODES - visited - nodes.size()) return false;
                for (var entry : compound.entrySet()) {
                    characters += entry.getKey().length();
                    nodes.push(new Node(entry.getValue(), depth));
                }
            } else if (node.tag() instanceof ListTag<?> list) {
                if (list.size() > MAX_COMPONENT_NODES - visited - nodes.size()) return false;
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
        if (!(packet instanceof ByteBuf raw) || !raw.isReadable() || !playing()) return null;
        ByteBuf input = raw.duplicate();
        if (Types.VAR_INT.readPrimitive(input) != customClick) return null;
        try {
            int length = Types.VAR_INT.readPrimitive(input);
            if (length < 1 || length > input.readableBytes()) throw new MalformedClickException("Invalid click identifier");
            if (length < TOKEN_PREFIX.length()
                    || !TOKEN_PREFIX.contentEquals(input.getCharSequence(input.readerIndex(), TOKEN_PREFIX.length(), StandardCharsets.US_ASCII))) {
                return null;
            }
            if (length > 128) throw new MalformedClickException("Oversized NCD identifier");
            String id = input.readCharSequence(length, StandardCharsets.US_ASCII).toString();
            int payload = Types.VAR_INT.readPrimitive(input);
            if (payload < 1 || payload > 65536 || payload != input.readableBytes()) {
                throw new MalformedClickException("Invalid click payload size");
            }
            return id;
        } catch (IndexOutOfBoundsException | IllegalArgumentException ex) {
            throw new MalformedClickException("Invalid custom click framing");
        }
    }

    @Override public boolean account(ChannelHandlerContext context, Object packet, boolean commandWillRun) {
        // The normal Via decoder accounts for converted commands. Charge consumed clicks here.
        return commandWillRun || user.checkServerboundPacket(((ByteBuf) packet).readableBytes());
    }

    @Override public void execute(ChannelHandlerContext context, String command) {
        ByteBuf packet = context.alloc().buffer();
        try {
            Types.VAR_INT.writePrimitive(packet, chatCommand);
            Types.STRING.write(packet, command);
        } catch (RuntimeException ex) {
            packet.release();
            throw ex;
        }
        // Via translates the unsigned command, then Paper handles it with the player's permissions.
        context.fireChannelRead(packet);
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
