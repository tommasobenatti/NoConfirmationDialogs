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
    private final Class<?> pluginMessage = Class.forName(PREFIX + "PluginMessagePacket");
    private final int pluginMessageId;

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
        pluginMessageId = (int) Reflect.method(registry.getClass(), "getPacketId", 1).invoke(registry, pluginMessage.getConstructor().newInstance());
    }

    @Override public Object rewrite(Object packet, CommandClicks clicks) throws ReflectiveOperationException {
        Object original = packet;
        if (packet instanceof ByteBuf raw) {
            if (!raw.isReadable() || raw.getUnsignedByte(raw.readerIndex()) != systemChatId) return original;
            ByteBuf input = raw.duplicate();
            if (readVarInt(input) != systemChatId) return original;
            packet = systemChat.getConstructor().newInstance();
            Reflect.method(systemChat, "decode", 3).invoke(packet, input, direction, version);
            if (input.isReadable()) throw new IllegalArgumentException("Unexpected system chat bytes");
        }
        if (!systemChat.isInstance(packet)) return original;
        Object holder = Reflect.call(packet, "getComponent");
        String json = (String) Reflect.call(holder, "getJson");
        if (!json.contains("run_command")) return original;
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
            if (!buffer.isReadable()) throw new MalformedClickException("Truncated VarInt");
            int part = buffer.readUnsignedByte();
            if (shift == 28 && (part & 0xf0) != 0) throw new MalformedClickException("Overflowing VarInt");
            result |= (part & 127) << shift;
            if ((part & 128) == 0) {
                if (shift > 0 && part == 0) throw new MalformedClickException("Noncanonical VarInt");
                return result;
            }
        }
        throw new MalformedClickException("Invalid VarInt");
    }

    @Override public String clickId(Object packet) throws ReflectiveOperationException {
        ByteBuf buffer;
        if (packet instanceof ByteBuf raw) {
            if (!raw.isReadable()) return null;
            int first = raw.getUnsignedByte(raw.readerIndex());
            // Never let an overlong packet ID hide a custom click from proxy policy.
            if ((first & 128) == 0 && first != customClickId) return null;
            buffer = raw.duplicate();
            if (readVarInt(buffer) != customClickId) return null;
        } else {
            if (!packet.getClass().getName().equals(PREFIX + "ServerboundCustomClickActionPacket")) return null;
            buffer = ((ByteBuf) Reflect.call(packet, "content")).duplicate();
        }
        // Velocity keeps this packet as a retained wire buffer. Read a duplicate without advancing it.
        int size = readVarInt(buffer);
        if (size < 1 || size > 32767 || size > buffer.readableBytes()) throw new MalformedClickException("Invalid click identifier length");
        boolean ncd = size >= 4 && buffer.getByte(buffer.readerIndex()) == 'n' && buffer.getByte(buffer.readerIndex() + 1) == 'c'
                && buffer.getByte(buffer.readerIndex() + 2) == 'd' && buffer.getByte(buffer.readerIndex() + 3) == ':';
        if (size > 128) {
            if (ncd) throw new MalformedClickException("Oversized NCD identifier");
            return null; // Preserve foreign plugin identifiers without allocating their strings.
        }
        String id = buffer.readCharSequence(size, StandardCharsets.UTF_8).toString();
        if (ncd) {
            if (id.chars().anyMatch(c -> c > 127)) throw new MalformedClickException("Invalid NCD identifier");
            int payload = readVarInt(buffer);
            if (payload < 1 || payload > 65536 || payload != buffer.readableBytes()) throw new MalformedClickException("Invalid click payload size");
        }
        return id;
    }

    @Override public void execute(ChannelHandlerContext context, String text) throws ReflectiveOperationException {
        Object command = commandClass.getConstructor().newInstance();
        Reflect.field(commandClass, "command").set(command, text);
        // Let Velocity's normal command handler fire CommandExecuteEvent and route unknown commands.
        context.fireChannelRead(command);
    }

    @Override public boolean control(Object original, CommandClicks clicks) throws ReflectiveOperationException {
        Object packet = original;
        boolean temporary = false;
        try {
            if (original instanceof ByteBuf raw) {
                if (!raw.isReadable() || raw.getUnsignedByte(raw.readerIndex()) != pluginMessageId) return false;
                ByteBuf input = raw.duplicate();
                if (readVarInt(input) != pluginMessageId) return false;
                packet = pluginMessage.getConstructor().newInstance(); temporary = true;
                Reflect.method(pluginMessage, "decode", 3).invoke(packet, input, direction, version);
            }
            if (!pluginMessage.isInstance(packet) || !RegistrationFrame.CHANNEL.equals(Reflect.call(packet, "getChannel"))) return false;
            ByteBuf data = (ByteBuf) Reflect.call(packet, "content");
            if (data.readableBytes() > RegistrationFrame.MAX_BYTES) throw new IllegalArgumentException("Registration too large");
            byte[] bytes = new byte[data.readableBytes()]; data.getBytes(data.readerIndex(), bytes);
            if (!clicks.importRegistration(RegistrationFrame.decode(bytes))) throw new IllegalArgumentException("Invalid registration");
            return true;
        } finally { if (temporary) ReferenceCountUtil.release(packet); }
    }

    @Override public void release(Object packet) { ReferenceCountUtil.release(packet); }

    @Override public int requestBytes(Object packet) throws ReflectiveOperationException {
        return packet.getClass().getName().equals(PREFIX + "ServerboundCustomClickActionPacket")
                ? ((ByteBuf) Reflect.call(packet, "content")).readableBytes() : PacketAdapter.super.requestBytes(packet);
    }
}
