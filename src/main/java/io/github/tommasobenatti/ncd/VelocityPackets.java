package io.github.tommasobenatti.ncd;

import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;

final class VelocityPackets implements PacketAdapter {
    private static final String PREFIX = "com.velocitypowered.proxy.protocol.packet.";
    private final Class<?> commandClass = Class.forName(PREFIX + "chat.session.UnsignedPlayerCommandPacket");
    private final Object version;
    private final Object direction;
    private final Class<?> systemChat = Class.forName(PREFIX + "chat.SystemChatPacket");
    private final int systemChatId;
    private final int customClickId;

    VelocityPackets() throws ReflectiveOperationException {
        this(Class.forName("com.velocitypowered.api.network.ProtocolVersion").getMethod("getProtocolVersion", int.class).invoke(null, 771));
    }

    VelocityPackets(Object version) throws ReflectiveOperationException {
        this.version = version;
        int protocol = (int) Reflect.call(version, "getProtocol");
        // PLAY custom clicks are deliberately passed through undecoded by Velocity.
        // Current released protocol families: 771–774 use 0x41; 775–777 use 0x44.
        // These mappings are also present in BungeeCord's native Protocol registry.
        if (protocol < 771 || protocol > 777) throw new IllegalArgumentException("Untested client protocol: " + protocol);
        customClickId = protocol >= 775 ? 0x44 : 0x41;
        commandClass.getConstructor();
        Class<?> dir = Class.forName("com.velocitypowered.proxy.protocol.ProtocolUtils$Direction");
        direction = dir.getField("CLIENTBOUND").get(null);
        Class<?> state = Class.forName("com.velocitypowered.proxy.protocol.StateRegistry");
        Object registry = state.getMethod("getProtocolRegistry", dir, version.getClass()).invoke(state.getField("PLAY").get(null), direction, version);
        systemChatId = (int) Reflect.method(registry.getClass(), "getPacketId", 1).invoke(registry, systemChat.getConstructor().newInstance());
    }

    @Override public Object rewrite(Object packet, CommandClicks clicks) throws ReflectiveOperationException {
        Object original = packet;
        if (packet instanceof ByteBuf raw) {
            ByteBuf input = raw.duplicate();
            if (readVarInt(input) != systemChatId) return original;
            packet = systemChat.getConstructor().newInstance();
            Reflect.method(systemChat, "decode", 3).invoke(packet, input, direction, version);
            if (input.isReadable()) throw new IllegalArgumentException("Unexpected system chat bytes");
        }
        if (!systemChat.isInstance(packet)) return original;
        Object holder = Reflect.call(packet, "getComponent");
        String json = (String) Reflect.call(holder, "getJson");
        if (!json.contains("run_command") && !json.contains("ncd:paper/")) return original;
        var source = JsonParser.parseString(json);
        var fixed = clicks.rewrite(source);
        if (fixed == source) return original;
        Object version = Reflect.get(holder, "version");
        Object replacement = holder.getClass().getConstructor(version.getClass(), String.class).newInstance(version, fixed.toString());
        Object chatType = Reflect.call(packet, "getType");
        return packet.getClass().getConstructor(holder.getClass(), chatType.getClass()).newInstance(replacement, chatType);
    }

    private static int readVarInt(ByteBuf buffer) {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int part = buffer.readUnsignedByte();
            result |= (part & 127) << shift;
            if ((part & 128) == 0) return result;
        }
        throw new IllegalArgumentException("Invalid packet ID");
    }

    @Override public String clickId(Object packet) throws ReflectiveOperationException {
        ByteBuf buffer;
        if (packet instanceof ByteBuf raw) {
            buffer = raw.duplicate();
            if (readVarInt(buffer) != customClickId) return null;
        } else {
            if (!packet.getClass().getName().equals(PREFIX + "ServerboundCustomClickActionPacket")) return null;
            buffer = ((ByteBuf) Reflect.call(packet, "content")).duplicate();
        }
        // Velocity keeps this packet as a retained wire buffer. Read a duplicate without advancing it.
        int size = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (!buffer.isReadable()) throw new IllegalArgumentException("Truncated click identifier");
            int part = buffer.readUnsignedByte();
            size |= (part & 127) << shift;
            if ((part & 128) == 0) {
                if (size < 0 || size > 32767 || size > buffer.readableBytes()) throw new IllegalArgumentException("Invalid click identifier length");
                return buffer.toString(buffer.readerIndex(), size, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("Invalid click identifier");
    }

    @Override public void execute(ChannelHandlerContext context, String text) throws ReflectiveOperationException {
        Object command = commandClass.getConstructor().newInstance();
        Reflect.field(commandClass, "command").set(command, text);
        // Let Velocity's normal command handler fire CommandExecuteEvent and route unknown commands.
        context.fireChannelRead(command);
    }

    @Override public void release(Object packet) { ReferenceCountUtil.release(packet); }
}
