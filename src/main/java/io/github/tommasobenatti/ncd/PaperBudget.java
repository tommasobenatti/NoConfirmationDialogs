package io.github.tommasobenatti.ncd;

import io.netty.channel.ChannelHandlerContext;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/** Charges consumed custom clicks to the host's existing counters, including DROP overrides. */
final class PaperBudget {
    private final Class<?> connection = Class.forName("net.minecraft.network.Connection");
    private final Method configuration = Class.forName("io.papermc.paper.configuration.GlobalConfiguration").getMethod("get");
    private final Field lock = Reflect.field(connection, "PACKET_LIMIT_LOCK");
    private final Field all = Reflect.field(connection, "allPacketCounts");
    private final Field specific = Reflect.field(connection, "packetSpecificLimits");
    private final Field stopped = Reflect.field(connection, "stopReadingPackets");
    private final Method kick = connection.getDeclaredMethod("killForPacketSpam");
    private final Constructor<?> counter = Class.forName("io.papermc.paper.util.IntervalledCounter").getConstructor(long.class);
    private final Method add = counter.getDeclaringClass().getMethod("updateAndAdd", long.class, long.class);
    private final Method rate = counter.getDeclaringClass().getMethod("getRate");

    PaperBudget() throws ReflectiveOperationException { kick.setAccessible(true); }

    @SuppressWarnings("unchecked")
    boolean account(ChannelHandlerContext context, Object packet, boolean commandWillRun) throws ReflectiveOperationException {
        Object host = context.pipeline().get("packet_handler");
        if (!connection.isInstance(host)) return true;
        Object limits = Reflect.get(configuration.invoke(null), "packetLimiter");
        Map<Class<?>, Object> overrides = (Map<Class<?>, Object>) Reflect.get(limits, "overrides");
        synchronized (lock.get(host)) {
            if (stopped.getBoolean(host)) return false;
            long now = System.nanoTime();
            Object global = all.get(host);
            // A converted command passes through Connection itself and is globally counted there.
            if (!commandWillRun && global != null) {
                if (!chargeGlobal(host, global, limits, now)) return false;
            }
            Map<Class<?>, Object> counters = (Map<Class<?>, Object>) specific.get(host);
            for (Class<?> type = packet.getClass(); type != Object.class; type = type.getSuperclass()) {
                Object limit = overrides.get(type);
                if (limit == null || !(boolean) Reflect.call(limit, "isEnabled")) continue;
                Object count = counters.get(type);
                if (count == null) {
                    count = counter.newInstance((long) (((Number) Reflect.call(limit, "interval")).doubleValue() * 1.0e9));
                    counters.put(type, count);
                }
                add.invoke(count, 1L, now);
                if (exceeded(count, limit)) {
                    // A DROP means the anticipated command will not reach Connection after all.
                    if (commandWillRun && global != null && !chargeGlobal(host, global, limits, now)) return false;
                    if (Reflect.call(limit, "action").toString().equals("KICK")) kick.invoke(host);
                    return false;
                }
            }
            return true;
        }
    }

    private boolean chargeGlobal(Object host, Object count, Object limits, long now) throws ReflectiveOperationException {
        add.invoke(count, 1L, now);
        if (exceeded(count, Reflect.get(limits, "allPackets"))) { kick.invoke(host); return false; }
        return true;
    }

    private boolean exceeded(Object count, Object limit) throws ReflectiveOperationException {
        return ((Number) rate.invoke(count)).doubleValue() >= ((Number) Reflect.call(limit, "maxPacketRate")).doubleValue();
    }
}
