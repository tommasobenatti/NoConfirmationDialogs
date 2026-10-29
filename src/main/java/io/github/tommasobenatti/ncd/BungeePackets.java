package io.github.tommasobenatti.ncd;

import com.google.gson.JsonElement;
import io.netty.channel.ChannelHandlerContext;
import io.netty.buffer.ByteBuf;
import java.lang.reflect.Method;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;

final class BungeePackets implements PacketAdapter {
    private static final String PREFIX = "net.md_5.bungee.protocol.";
    private final ProxiedPlayer player;
    private final Object serializer;
    private final Method toJson, fromJson;
    private final Class<?> component = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
    private final Class<?> commandClass = Class.forName(PREFIX + "packet.UnsignedClientCommand");
    private final Class<?> systemChat = Class.forName(PREFIX + "packet.SystemChat");
    private final Object direction;
    private final int protocol;
    private final int systemChatId;
    private final Class<?> pluginMessage = Class.forName(PREFIX + "packet.PluginMessage");
    private final int pluginMessageId;
    private final java.lang.reflect.Field nativeLimiter;
    private final Method incrementLimiter;

    BungeePackets(ProxiedPlayer player) throws ReflectiveOperationException {
        this.player = player;
        protocol = player.getPendingConnection().getVersion();
        serializer = Class.forName(PREFIX + "ChatSerializer").getMethod("forVersion", int.class).invoke(null, protocol);
        toJson = serializer.getClass().getMethod("toJson", component);
        fromJson = serializer.getClass().getMethod("deserialize", JsonElement.class);
        commandClass.getConstructor(String.class);
        Class<?> registry = Class.forName(PREFIX + "Protocol");
        Object outbound = registry.getField("TO_CLIENT").get(registry.getField("GAME").get(null));
        Method getId = outbound.getClass().getDeclaredMethod("getId", Class.class, int.class);
        getId.setAccessible(true);
        systemChatId = (int) getId.invoke(outbound, systemChat, protocol);
        pluginMessageId = (int) getId.invoke(outbound, pluginMessage, protocol);
        direction = Class.forName(PREFIX + "ProtocolConstants$Direction").getField("TO_CLIENT").get(null);
        java.lang.reflect.Field limiter = null; Method increment = null;
        try {
            limiter = Reflect.field(Class.forName("net.md_5.bungee.netty.HandlerBoss"), "limiter");
            increment = limiter.getType().getMethod("incrementAndCheck", int.class);
        } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException ignored) {
            // Older hosts have no generic limiter; the independent NCD limits still apply.
        }
        nativeLimiter = limiter; incrementLimiter = increment;
    }

    private Object unwrap(Object packet) throws ReflectiveOperationException {
        return packet.getClass().getName().equals(PREFIX + "PacketWrapper") ? Reflect.get(packet, "packet") : packet;
    }

    @Override public Object rewrite(Object original, CommandClicks clicks) throws ReflectiveOperationException {
        Object packet = unwrap(original);
        if (original instanceof ByteBuf || (packet == null && original.getClass().getName().equals(PREFIX + "PacketWrapper"))) {
            ByteBuf raw = original instanceof ByteBuf buffer ? buffer : (ByteBuf) Reflect.get(original, "buf");
            if (!raw.isReadable() || raw.getUnsignedByte(raw.readerIndex()) != systemChatId) return original;
            raw = raw.duplicate();
            int id = (int) Class.forName(PREFIX + "DefinedPacket").getMethod("readVarInt", ByteBuf.class).invoke(null, raw);
            if (id != systemChatId) return original;
            packet = systemChat.getConstructor().newInstance();
            systemChat.getMethod("read", ByteBuf.class, direction.getClass(), int.class).invoke(packet, raw, direction, protocol);
            if (raw.isReadable()) throw new IllegalArgumentException("Unexpected system chat bytes");
        }
        if (packet == null || !packet.getClass().getName().equals(PREFIX + "packet.SystemChat")) return original;
        Object message = Reflect.call(packet, "getMessage");
        JsonElement source = (JsonElement) toJson.invoke(serializer, message);
        JsonElement fixed = clicks.rewrite(source);
        if (source == fixed) return original;
        Object next = fromJson.invoke(serializer, fixed);
        return packet.getClass().getConstructor(component, int.class).newInstance(next, Reflect.call(packet, "getPosition"));
    }

    @Override public String clickId(Object original) throws ReflectiveOperationException {
        Object packet = unwrap(original);
        return packet != null && packet.getClass().getName().equals(PREFIX + "packet.CustomClickAction")
                ? (String) Reflect.call(packet, "getId") : null;
    }

    @Override public void execute(ChannelHandlerContext context, String text) throws ReflectiveOperationException {
        // Bungee's public Player.chat() rejects modern clients, so use its standard event and command routing.
        var target = player.getServer();
        ChatEvent event = new ChatEvent(player, target, "/" + text);
        ProxyServer.getInstance().getPluginManager().callEvent(event);
        if (event.isCancelled() || !event.getMessage().startsWith("/") || player.getServer() != target) return;
        String command = event.getMessage().substring(1);
        if (ProxyServer.getInstance().getPluginManager().dispatchCommand(player, command) || target == null) return;
        Object channel = Reflect.call(target, "getCh");
        Reflect.method(channel.getClass(), "write", 1).invoke(channel, commandClass.getConstructor(String.class).newInstance(command));
    }

    @Override public boolean control(Object original, CommandClicks clicks) throws ReflectiveOperationException {
        Object packet = unwrap(original);
        if (original instanceof ByteBuf || (packet == null && original.getClass().getName().equals(PREFIX + "PacketWrapper"))) {
            ByteBuf input = (original instanceof ByteBuf raw ? raw : (ByteBuf) Reflect.get(original, "buf")).duplicate();
            if (!input.isReadable() || input.getUnsignedByte(input.readerIndex()) != pluginMessageId) return false;
            int id = (int) Class.forName(PREFIX + "DefinedPacket").getMethod("readVarInt", ByteBuf.class).invoke(null, input);
            if (id != pluginMessageId) return false;
            packet = pluginMessage.getConstructor().newInstance();
            pluginMessage.getMethod("read", ByteBuf.class, direction.getClass(), int.class).invoke(packet, input, direction, protocol);
        }
        if (packet == null || !pluginMessage.isInstance(packet) || !RegistrationFrame.CHANNEL.equals(Reflect.call(packet, "getTag"))) return false;
        byte[] data = (byte[]) Reflect.call(packet, "getData");
        if (!clicks.importRegistration(RegistrationFrame.decode(data))) throw new IllegalArgumentException("Invalid registration");
        return true;
    }

    @Override public void release(Object packet) {
        if (packet.getClass().getName().equals(PREFIX + "PacketWrapper")) {
            try { Reflect.call(packet, "trySingleRelease"); }
            catch (ReflectiveOperationException ex) { throw new IllegalStateException("Could not release packet", ex); }
        } else PacketAdapter.super.release(packet);
    }

    @Override public int requestBytes(Object packet) throws ReflectiveOperationException {
        return packet.getClass().getName().equals(PREFIX + "PacketWrapper")
                ? ((ByteBuf) Reflect.get(packet, "buf")).readableBytes() : PacketAdapter.super.requestBytes(packet);
    }

    @Override public boolean account(ChannelHandlerContext context, Object packet) throws ReflectiveOperationException {
        if (nativeLimiter == null || incrementLimiter == null) return true;
        Object boss = context.pipeline().get("inbound-boss");
        if (boss == null) return true;
        Object limiter = nativeLimiter.get(boss);
        boolean allowed = limiter == null || (boolean) incrementLimiter.invoke(limiter, requestBytes(packet));
        if (!allowed) context.close();
        return allowed;
    }
}
