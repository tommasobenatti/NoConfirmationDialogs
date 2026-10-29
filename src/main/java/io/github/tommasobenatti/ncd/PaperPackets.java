package io.github.tommasobenatti.ncd;

import com.google.gson.JsonParser;
import io.netty.channel.ChannelHandlerContext;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;

final class PaperPackets implements PacketAdapter {
    private static final String GAME = "net.minecraft.network.protocol.game.";
    private final Class<?> component = Class.forName("net.minecraft.network.chat.Component");
    private final Method toJson, fromJson, clickId, subPackets;
    private final Constructor<?> command, bundle;
    private final Class<?> customClick;
    private final Method clickPayload, tagSize;
    private final Constructor<?> discardedPayload, customPayload;
    private final Object registrationId;
    private record Layout(Constructor<?> constructor, Method[] accessors, boolean[] components) { }
    private final Map<Class<?>, Layout> layouts;
    private final PaperBudget budget = new PaperBudget();

    PaperPackets() throws ReflectiveOperationException {
        Class<?> serializer = Class.forName("org.bukkit.craftbukkit.util.CraftChatMessage");
        toJson = serializer.getMethod("toJSON", component);
        fromJson = serializer.getMethod("fromJSON", String.class);
        customClick = Class.forName("net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket");
        clickId = customClick.getMethod("id");
        clickPayload = customClick.getMethod("payload");
        tagSize = Class.forName("net.minecraft.nbt.Tag").getMethod("sizeInBytes");
        command = Class.forName(GAME + "ServerboundChatCommandPacket").getConstructor(String.class);
        Class<?> bundleClass = Class.forName(GAME + "ClientboundBundlePacket");
        bundle = bundleClass.getConstructor(Iterable.class);
        subPackets = bundleClass.getMethod("subPackets");
        Class<?> discarded = Class.forName("net.minecraft.network.protocol.common.custom.DiscardedPayload");
        Class<?> identifier = discarded.getRecordComponents()[0].getType();
        registrationId = identifier.getMethod("parse", String.class).invoke(null, RegistrationFrame.CHANNEL);
        discardedPayload = discarded.getConstructor(identifier, byte[].class);
        customPayload = Class.forName("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket")
                .getConstructor(Class.forName("net.minecraft.network.protocol.common.custom.CustomPacketPayload"));
        // Fail at startup rather than silently announcing support for an incompatible packet layout.
        Map<Class<?>, Layout> cached = new HashMap<>();
        for (String type : List.of("ClientboundSystemChatPacket", "ClientboundDisguisedChatPacket", "ClientboundPlayerChatPacket")) {
            Class<?> packet = Class.forName(GAME + type);
            if (!packet.isRecord()) throw new IllegalStateException("Unsupported packet layout: " + type);
            RecordComponent[] fields = packet.getRecordComponents();
            Class<?>[] types = new Class<?>[fields.length];
            Method[] accessors = new Method[fields.length];
            boolean[] components = new boolean[fields.length];
            for (int i = 0; i < fields.length; i++) {
                types[i] = fields[i].getType(); accessors[i] = fields[i].getAccessor();
                components[i] = types[i] == component || fields[i].getName().equals("unsignedContent");
            }
            cached.put(packet, new Layout(packet.getConstructor(types), accessors, components));
        }
        layouts = Map.copyOf(cached);
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
        Layout layout = layouts.get(packet.getClass());
        if (layout == null) return packet;
        Object[] values = null;
        for (int i = 0; i < layout.accessors.length; i++) {
            if (!layout.components[i]) continue;
            Object value = layout.accessors[i].invoke(packet);
            Object next = value;
            if (component.isInstance(value)) next = rewriteComponent(value, clicks);
            else if (value instanceof Optional<?> optional && optional.isPresent() && component.isInstance(optional.get())) {
                Object result = rewriteComponent(optional.get(), clicks);
                if (result != optional.get()) next = Optional.of(result);
            }
            if (next != value) {
                if (values == null) {
                    values = new Object[layout.accessors.length];
                    for (int j = 0; j < values.length; j++) values[j] = layout.accessors[j].invoke(packet);
                }
                values[i] = next;
            }
        }
        return values == null ? packet : layout.constructor.newInstance(values);
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

    @Override public void publish(ChannelHandlerContext context, CommandClicks clicks) throws ReflectiveOperationException {
        for (var registration : clicks.drainExports()) {
            Object payload = discardedPayload.newInstance(registrationId, RegistrationFrame.encode(registration));
            context.write(customPayload.newInstance(payload));
        }
    }

    @Override public int requestBytes(Object packet) throws ReflectiveOperationException {
        if (!customClick.isInstance(packet)) return 0;
        Optional<?> payload = (Optional<?>) clickPayload.invoke(packet);
        // Native decoded packets no longer carry their wire buffer. Charge a conservative NBT size.
        long bytes = 16L + clickId.invoke(packet).toString().length() * 2L;
        if (payload.isPresent()) bytes += ((Number) tagSize.invoke(payload.get())).intValue();
        return (int) Math.min(Integer.MAX_VALUE, bytes);
    }

    @Override public boolean account(ChannelHandlerContext context, Object packet, boolean commandWillRun) throws ReflectiveOperationException {
        return budget.account(context, packet, commandWillRun);
    }
}
