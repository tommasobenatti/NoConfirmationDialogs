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
        Class<?> protocol = Class.forName("com.velocitypowered.api.network.ProtocolVersion");
        Object version = protocol.getMethod("getProtocolVersion", int.class).invoke(null, protocolNumber);
        var adapter = new VelocityPackets(version);
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
        bytes.writeByte(len).writeBytes(id).writeByte(1).writeByte(0);
        Object input = custom.getConstructor().newInstance();
        custom.getMethod("replace", io.netty.buffer.ByteBuf.class).invoke(input, bytes);
        channel.writeInbound(input);
        Object command = channel.readInbound();
        check(command != null && command.getClass().getSimpleName().equals("UnsignedPlayerCommandPacket"), "native command path");
        check(Reflect.call(command, "getCommand").equals("server lobby"), "command preserved");
        check(bytes.refCnt() == 0, "incoming buffer released");
        check(errors.isEmpty(), "no errors");
        channel.finishAndReleaseAll();
        var rawClick = Unpooled.buffer(); rawClick.writeByte(protocolNumber >= 775 ? 0x44 : 0x41);
        rawClick.writeByte(id.length).writeBytes(id).writeByte(1).writeByte(0);
        check(adapter.clickId(rawClick).equals(token), "raw click uses actual client protocol");
        check(rawClick.readerIndex() == 0, "raw click reader index preserved");
        rawClick.release();
        for (byte[] malformed : new byte[][]{new byte[]{(byte)0x80}, new byte[]{(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,0x7f}, new byte[]{(byte)0x80,0}}) {
            var bad = Unpooled.buffer(); bad.writeByte(protocolNumber >= 775 ? 0x44 : 0x41).writeBytes(malformed);
            boolean rejected = false; try { adapter.clickId(bad); } catch (MalformedClickException expected) { rejected = true; }
            check(rejected, "malformed VarInt rejected"); bad.release();
        }
        var livePaper = new CommandClicks(() -> 0, 100, 10000, "paper");
        String liveToken = livePaper.register("must_pass_proxy_policy").orElseThrow();
        check(clicks.importRegistration(livePaper.drainExports().getFirst()), "overlong test imports a backend alias");
        check(clicks.resolve(liveToken).isPresent(), "overlong test starts with a valid capability");
        byte[] liveId = liveToken.getBytes(StandardCharsets.UTF_8);
        var overlong = Unpooled.buffer();
        overlong.writeByte((protocolNumber >= 775 ? 0x44 : 0x41) | 128).writeByte(0);
        overlong.writeByte(liveId.length).writeBytes(liveId).writeByte(1).writeByte(0);
        var protectedChannel = new EmbeddedChannel(new ClickHandler(adapter, clicks, ex -> { throw new AssertionError(ex); }));
        protectedChannel.writeInbound(overlong);
        check(!protectedChannel.isActive(), "overlong packet ID cannot bypass proxy policy");
        check(protectedChannel.readInbound() == null, "overlong packet never forwarded to backend");
        check(overlong.refCnt() == 0, "malformed packet released");
        protectedChannel.finishAndReleaseAll();
        var alias = new CommandClicks(() -> 0, 100, 10000, "paper");
        String aliasToken = alias.register("proxy_must_check").orElseThrow();
        var registration = alias.drainExports().getFirst();
        Class<?> plugin = Class.forName("com.velocitypowered.proxy.protocol.packet.PluginMessagePacket");
        Object frame = plugin.getConstructor(String.class, io.netty.buffer.ByteBuf.class).newInstance(RegistrationFrame.CHANNEL, Unpooled.wrappedBuffer(RegistrationFrame.encode(registration)));
        check(adapter.control(frame, clicks), "server registration accepted");
        check(clicks.resolve(aliasToken).orElseThrow().equals("proxy_must_check"), "non-system paper alias resolved at proxy");
        adapter.release(frame);
        // Server-only registrations cover opaque chat; no client echo metadata is needed.
        var paper = new CommandClicks(() -> 0, 100, 10000, "paper");
        String paperJson = paper.rewrite(JsonParser.parseString(JSON)).toString();
        Object backend = packetType.getConstructor(holder, type).newInstance(holder.getConstructor(protocol, String.class).newInstance(version, paperJson), chatType);
        paper.drainExports().forEach(exported -> check(clicks.importRegistration(exported), "paper alias imported"));
        check(adapter.rewrite(backend, clicks) == backend, "already registered clicks need no second rewrite");
        var raw = Unpooled.buffer();
        raw.writeByte((int) Reflect.get(adapter, "systemChatId")); // 1.21.6 system chat packet ID
        Object direction = Class.forName("com.velocitypowered.proxy.protocol.ProtocolUtils$Direction").getField("CLIENTBOUND").get(null);
        Reflect.method(packetType, "encode", 3).invoke(packet, raw, direction, version);
        int reader = raw.readerIndex();
        check(adapter.rewrite(raw, clicks).getClass() == packetType, "proxy-only raw backend run_command is converted");
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
        var backendRegistry = new CommandClicks(() -> 0, 100, 10000, "paper");
        String backendAlias = backendRegistry.register("proxy_must_check").orElseThrow();
        byte[] registrationData = RegistrationFrame.encode(backendRegistry.drainExports().getFirst());
        Class<?> pluginMessage = Class.forName("net.md_5.bungee.protocol.packet.PluginMessage");
        Object registrationPacket = pluginMessage.getConstructor(String.class, byte[].class, boolean.class).newInstance(RegistrationFrame.CHANNEL, registrationData, false);
        check(adapter.control(registrationPacket, clicks), "native Bungee registration imported");
        check(clicks.resolve(backendAlias).orElseThrow().equals("proxy_must_check"), "non-system alias guarded by proxy");
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
        Class<?> bossClass = Class.forName("net.md_5.bungee.netty.HandlerBoss");
        Object boss = bossClass.getConstructor().newInstance();
        Class<?> limiterClass = Class.forName("net.md_5.bungee.util.PacketLimiter");
        Object limiter = limiterClass.getConstructor(int.class, int.class).newInstance(1, 1048576);
        bossClass.getMethod("setLimiter", limiterClass).invoke(boss, limiter);
        var guarded = new EmbeddedChannel(new ClickHandler(adapter, new CommandClicks(() -> 0, 10, 1000, "proxy"), ex -> { throw new AssertionError(ex); }));
        guarded.pipeline().addLast("inbound-boss", (io.netty.channel.ChannelHandler) boss);
        for (int n = 0; n < 4; n++) {
            Object unknown = custom.getConstructor().newInstance(); custom.getMethod("setId", String.class).invoke(unknown, "ncd:proxy/junk");
            var data = Unpooled.buffer().writeByte(0);
            Object wrapped = wrapperClass.getConstructor(defined, io.netty.buffer.ByteBuf.class, protocol).newInstance(unknown, data, protocol.getField("GAME").get(null));
            guarded.writeInbound(wrapped); check(data.refCnt() == 0, "budgeted request released");
        }
        check(!guarded.isActive(), "strict native Bungee packet limit preserved for consumed junk");
        guarded.finishAndReleaseAll();
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); assertions++; }
}
