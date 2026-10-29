package io.github.tommasobenatti.ncd;

import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.nio.charset.StandardCharsets;

/** Runs against each real proxy distribution, outside the production JAR. */
public final class ProxyProbe {
    private static int assertions;
    private static final String JSON = "{\"text\":\"click\",\"color\":\"gold\",\"click_event\":{\"action\":\"run_command\",\"command\":\"/server lobby\"}}";
    public static void main(String[] args) throws Exception {
        for (int protocol = 771; protocol <= 777; protocol++) {
            if (args[0].equals("velocity")) {
                Class<?> versionClass = Class.forName("com.velocitypowered.api.network.ProtocolVersion");
                Object supported = versionClass.getMethod("getProtocolVersion", int.class).invoke(null, protocol);
                if ((int) Reflect.call(supported, "getProtocol") == protocol) velocity(protocol);
            } else bungee(protocol);
        }
        System.out.println("NCD_PROXY_PASS " + args[0] + " assertions=" + assertions);
    }

    private static void velocity(int protocolNumber) throws Exception {
        var adapter = new VelocityPackets();
        Class<?> protocol = Class.forName("com.velocitypowered.api.network.ProtocolVersion");
        Object version = protocol.getMethod("getProtocolVersion", int.class).invoke(null, protocolNumber);
        Class<?> holder = Class.forName("com.velocitypowered.proxy.protocol.packet.chat.ComponentHolder");
        Object content = holder.getConstructor(protocol, String.class).newInstance(version, JSON);
        Class<?> type = Class.forName("com.velocitypowered.proxy.protocol.packet.chat.ChatType");
        Object chatType = type.getField("SYSTEM").get(null);
        Class<?> packetType = Class.forName("com.velocitypowered.proxy.protocol.packet.chat.SystemChatPacket");
        Object packet = packetType.getConstructor(holder, type).newInstance(content, chatType);
        var clicks = new CommandClicks(() -> 0, 100, 10000, "proxy");
        Object fixed = adapter.rewrite(packet, clicks);
        check(fixed != packet, "proxy message rewritten");
        Object next = Reflect.call(fixed, "getComponent");
        String json = (String) Reflect.call(next, "getJson");
        var event = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("click_event");
        String token = event.get("id").getAsString();
        check(event.get("action").getAsString().equals("custom"), "custom click");
        check(clicks.resolve(token).orElseThrow().equals("server lobby"), "proxy command");
        check(adapter.rewrite(fixed, clicks) == fixed, "idempotent");
        // Native binary component roundtrip; exercises the format actually sent over the wire.
        Reflect.call(next, "getBinaryTag");
        check(Reflect.call(next, "getComponent") != null, "native component valid");
        var errors = new java.util.ArrayList<Exception>();
        var channel = new EmbeddedChannel(new ClickHandler(adapter, clicks, errors::add));
        Class<?> custom = Class.forName("com.velocitypowered.proxy.protocol.packet.ServerboundCustomClickActionPacket");
        var bytes = Unpooled.buffer();
        byte[] id = token.getBytes(StandardCharsets.UTF_8);
        int len = id.length;
        while ((len & ~127) != 0) { bytes.writeByte((len & 127) | 128); len >>>= 7; }
        bytes.writeByte(len).writeBytes(id).writeByte(0);
        Object input = custom.getConstructor().newInstance();
        custom.getMethod("replace", io.netty.buffer.ByteBuf.class).invoke(input, bytes);
        channel.writeInbound(input);
        Object command = channel.readInbound();
        check(command != null && command.getClass().getSimpleName().equals("UnsignedPlayerCommandPacket"), "native command path");
        check(Reflect.call(command, "getCommand").equals("server lobby"), "command preserved");
        check(bytes.refCnt() == 0, "incoming buffer released");
        check(errors.isEmpty(), "no errors");
        channel.finishAndReleaseAll();
        // Paper metadata is adopted at the proxy, so proxy command events still see backend clicks.
        var paper = new CommandClicks(() -> 0, 100, 10000, "paper");
        String paperJson = paper.rewrite(JsonParser.parseString(JSON)).toString();
        Object backend = packetType.getConstructor(holder, type).newInstance(holder.getConstructor(protocol, String.class).newInstance(version, paperJson), chatType);
        check(adapter.rewrite(backend, clicks) != backend, "proxy adopts backend click");
        var raw = Unpooled.buffer();
        raw.writeByte((int) Reflect.get(adapter, "systemChatId")); // 1.21.6 system chat packet ID
        Object direction = Class.forName("com.velocitypowered.proxy.protocol.ProtocolUtils$Direction").getField("CLIENTBOUND").get(null);
        Reflect.method(packetType, "encode", 3).invoke(backend, raw, direction, version);
        int reader = raw.readerIndex();
        check(adapter.rewrite(raw, clicks).getClass() == packetType, "raw backend system chat is decoded and adopted");
        check(raw.readerIndex() == reader, "original raw buffer untouched");
        raw.release();
    }

    private static void bungee(int protocolNumber) throws Exception {
        Class<?> pending = Class.forName("net.md_5.bungee.api.connection.PendingConnection");
        Object connection = Proxy.newProxyInstance(pending.getClassLoader(), new Class<?>[]{pending}, (p, m, a) -> m.getName().equals("getVersion") ? protocolNumber : null);
        Class<?> player = Class.forName("net.md_5.bungee.api.connection.ProxiedPlayer");
        Object user = Proxy.newProxyInstance(player.getClassLoader(), new Class<?>[]{player}, (p, m, a) -> m.getName().equals("getPendingConnection") ? connection : null);
        var adapter = new BungeePackets((net.md_5.bungee.api.connection.ProxiedPlayer) user);
        Class<?> serializerClass = Class.forName("net.md_5.bungee.protocol.ChatSerializer");
        Object serializer = serializerClass.getMethod("forVersion", int.class).invoke(null, protocolNumber);
        Object message = serializer.getClass().getMethod("deserialize", String.class).invoke(serializer, JSON);
        Class<?> component = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
        Class<?> packetClass = Class.forName("net.md_5.bungee.protocol.packet.SystemChat");
        Object packet = packetClass.getConstructor(component, int.class).newInstance(message, 0);
        var clicks = new CommandClicks(() -> 0, 100, 10000, "proxy");
        Object next = adapter.rewrite(packet, clicks);
        check(packet != next, "proxy message rewritten");
        String json = serializer.getClass().getMethod("toJson", component).invoke(serializer, Reflect.call(next, "getMessage")).toString();
        var event = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("click_event");
        check(event.get("action").getAsString().equals("custom"), "custom survives native codec");
        String token = event.get("id").getAsString();
        check(clicks.resolve(token).orElseThrow().equals("server lobby"), "exact proxy command");
        check(adapter.rewrite(next, clicks) == next, "idempotent");
        Class<?> custom = Class.forName("net.md_5.bungee.protocol.packet.CustomClickAction");
        Object click = custom.getConstructor().newInstance();
        custom.getMethod("setId", String.class).invoke(click, token);
        check(adapter.clickId(click).equals(token), "incoming click ID");
        Class<?> wrapperClass = Class.forName("net.md_5.bungee.protocol.PacketWrapper");
        Class<?> defined = Class.forName("net.md_5.bungee.protocol.DefinedPacket");
        Class<?> protocol = Class.forName("net.md_5.bungee.protocol.Protocol");
        var buffer = Unpooled.buffer();
        Object wrapper = wrapperClass.getConstructor(defined, io.netty.buffer.ByteBuf.class, protocol).newInstance(packet, buffer, protocol.getField("GAME").get(null));
        var channel = new EmbeddedChannel(new ClickHandler(adapter, clicks, e -> { throw new AssertionError(e); }));
        channel.writeOutbound(wrapper);
        check(channel.readOutbound().getClass() == packetClass, "raw backend wrapper re-encoded");
        check(buffer.refCnt() == 0, "wrapper buffer released");
        channel.finishAndReleaseAll();
        var raw = Unpooled.buffer(); raw.writeByte((int) Reflect.get(adapter, "systemChatId"));
        Object direction = Class.forName("net.md_5.bungee.protocol.ProtocolConstants$Direction").getField("TO_CLIENT").get(null);
        packetClass.getMethod("write", io.netty.buffer.ByteBuf.class, direction.getClass(), int.class).invoke(packet, raw, direction, protocolNumber);
        check(adapter.rewrite(raw, new CommandClicks(() -> 0, 100, 10000, "proxy")).getClass() == packetClass, "raw forwarded backend chat decoded");
        raw.release();
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); assertions++; }
}
