package io.github.tommasobenatti.ncd;

import com.google.gson.JsonParser;
import io.netty.channel.ChannelHandlerContext;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class PaperPackets implements PacketAdapter {
    private static final String GAME = "net.minecraft.network.protocol.game.";
    private final Class<?> component = Class.forName("net.minecraft.network.chat.Component");
    private final Method toJson, fromJson, clickId, subPackets;
    private final Constructor<?> command, bundle;
    private final Class<?> customClick;

    PaperPackets() throws ReflectiveOperationException {
        Class<?> serializer = Class.forName("org.bukkit.craftbukkit.util.CraftChatMessage");
        toJson = serializer.getMethod("toJSON", component);
        fromJson = serializer.getMethod("fromJSON", String.class);
        customClick = Class.forName("net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket");
        clickId = customClick.getMethod("id");
        command = Class.forName(GAME + "ServerboundChatCommandPacket").getConstructor(String.class);
        Class<?> bundleClass = Class.forName(GAME + "ClientboundBundlePacket");
        bundle = bundleClass.getConstructor(Iterable.class);
        subPackets = bundleClass.getMethod("subPackets");
        // Fail at startup rather than silently announcing support for an incompatible packet layout.
        for (String type : List.of("ClientboundSystemChatPacket", "ClientboundDisguisedChatPacket", "ClientboundPlayerChatPacket")) {
            Class<?> packet = Class.forName(GAME + type);
            if (!packet.isRecord()) throw new IllegalStateException("Unsupported packet layout: " + type);
        }
    }

    @Override public Object rewrite(Object packet, CommandClicks clicks) throws ReflectiveOperationException {
        String name = packet.getClass().getName();
        if (name.equals(GAME + "ClientboundBundlePacket")) {
            List<Object> children = new ArrayList<>();
            boolean changed = false;
            for (Object child : (Iterable<?>) subPackets.invoke(packet)) {
                Object next = rewrite(child, clicks);
                children.add(next); changed |= next != child;
            }
            return changed ? bundle.newInstance(children) : packet;
        }
        if (!name.equals(GAME + "ClientboundSystemChatPacket")
                && !name.equals(GAME + "ClientboundDisguisedChatPacket")
                && !name.equals(GAME + "ClientboundPlayerChatPacket")) return packet;
        RecordComponent[] fields = packet.getClass().getRecordComponents();
        Object[] values = new Object[fields.length];
        Class<?>[] types = new Class<?>[fields.length];
        boolean changed = false;
        for (int i = 0; i < fields.length; i++) {
            types[i] = fields[i].getType();
            Object value = fields[i].getAccessor().invoke(packet);
            Object next = value;
            if (component.isInstance(value)) next = rewriteComponent(value, clicks);
            else if (value instanceof Optional<?> optional && optional.isPresent() && component.isInstance(optional.get())) {
                Object result = rewriteComponent(optional.get(), clicks);
                if (result != optional.get()) next = Optional.of(result);
            }
            values[i] = next; changed |= next != value;
        }
        return changed ? packet.getClass().getConstructor(types).newInstance(values) : packet;
    }

    private Object rewriteComponent(Object original, CommandClicks clicks) throws ReflectiveOperationException {
        String json = (String) toJson.invoke(null, original);
        if (!json.contains("run_command")) return original;
        var tree = JsonParser.parseString(json);
        var rewritten = clicks.rewrite(tree);
        return rewritten == tree ? original : fromJson.invoke(null, rewritten.toString());
    }

    @Override public String clickId(Object packet) throws ReflectiveOperationException {
        return customClick.isInstance(packet) ? clickId.invoke(packet).toString() : null;
    }
    @Override public void execute(ChannelHandlerContext context, String text) throws ReflectiveOperationException {
        context.fireChannelRead(command.newInstance(text));
    }
}
