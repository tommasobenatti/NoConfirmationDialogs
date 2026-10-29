package io.github.tommasobenatti.ncd;

import com.google.gson.JsonParser;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated runtime fixture, packaged separately by qa/paper_matrix.py. Never in the production JAR. */
public final class RuntimeProbe extends JavaPlugin implements Listener {
    private int assertions;
    private static final String JSON = """
        {"text":"NCD probe","color":"gold","extra":[{"text":" accept","click_event":{"action":"run_command","command":"/huskhomes:tpaccept Alice"}}]}
        """;

    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("ncdprobe").setExecutor((sender, command, label, args) -> {
            getLogger().info("NCD_COMMAND_EXECUTED " + sender.getName()); return true;
        });
        getCommand("ncdrestricted").setExecutor((sender, command, label, args) -> {
            getLogger().severe("NCD_PERMISSION_BYPASSED"); return true;
        });
        getCommand("ncdbackendblocked").setExecutor((sender, command, label, args) -> {
            getLogger().info("NCD_STANDALONE_ALLOWED " + sender.getName()); return true;
        });
        getServer().getScheduler().runTask(this, () -> {
            try { probe(); getLogger().info("NCD_RUNTIME_PASS assertions=" + assertions); }
            catch (Throwable ex) { getLogger().log(java.util.logging.Level.SEVERE, "NCD_RUNTIME_FAIL", ex); }
            if (!Boolean.getBoolean("ncd.qa.keep-running")) getServer().shutdown();
        });
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        // This client does not simulate gravity/movement; keep its fixture alive in the flat test world.
        event.getPlayer().setInvulnerable(true);
        event.getPlayer().setAllowFlight(true);
        event.getPlayer().setFlying(true);
        getServer().getScheduler().runTaskLater(this, () -> {
            try {
                Object handle = event.getPlayer().getClass().getMethod("getHandle").invoke(event.getPlayer());
                getLogger().info("NCD_QA_STATE removed=" + handle.getClass().getMethod("isRemoved").invoke(handle)
                        + " reason=" + handle.getClass().getMethod("getRemovalReason").invoke(handle)
                        + " chat=" + handle.getClass().getMethod("getChatVisibility").invoke(handle));
            } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
        }, 30);
        event.getPlayer().sendMessage(Component.text("NCD-NATIVE-CLICK").clickEvent(ClickEvent.runCommand("/ncdprobe")));
        event.getPlayer().sendMessage(Component.text("NCD-NATIVE-DENIED").clickEvent(ClickEvent.runCommand("/ncdrestricted")));
        event.getPlayer().sendMessage(Component.text("NCD-BACKEND-BLOCK").clickEvent(ClickEvent.runCommand("/ncdbackendblocked")),
                net.kyori.adventure.chat.ChatType.CHAT.bind(Component.text("NCD-QA")));
    }

    private void probe() throws Exception {
        var plugin = getServer().getPluginManager().getPlugin("NoConfirmationDialogs");
        check(plugin != null && plugin.isEnabled(), "plugin enabled");
        ClassLoader loader = plugin.getClass().getClassLoader();
        Class<?> registryClass = loader.loadClass("io.github.tommasobenatti.ncd.CommandClicks");
        Class<?> adapterClass = loader.loadClass("io.github.tommasobenatti.ncd.PaperPackets");
        Class<?> interfaceClass = loader.loadClass("io.github.tommasobenatti.ncd.PacketAdapter");
        Object registry = construct(registryClass, new Class<?>[]{LongSupplier.class, int.class, long.class, String.class}, (LongSupplier) () -> 0L, 100, 10000L, "paper");
        Object adapter = construct(adapterClass, new Class<?>[0]);
        Class<?> component = Class.forName("net.minecraft.network.chat.Component");
        Class<?> craft = Class.forName("org.bukkit.craftbukkit.util.CraftChatMessage");
        Object text = craft.getMethod("fromJSON", String.class).invoke(null, JSON);
        String normalized = (String) craft.getMethod("toJSON", component).invoke(null, text);
        String game = "net.minecraft.network.protocol.game.";
        Object packet = Class.forName(game + "ClientboundSystemChatPacket").getConstructor(component, boolean.class).newInstance(text, false);
        Object fixed = invoke(adapter, "rewrite", new Class<?>[]{Object.class, registryClass}, packet, registry);
        check(fixed != packet, "system chat copied");
        String rewritten = (String) craft.getMethod("toJSON", component).invoke(null, fixed.getClass().getMethod("content").invoke(fixed));
        var event = JsonParser.parseString(rewritten).getAsJsonObject().getAsJsonArray("extra").get(0).getAsJsonObject().getAsJsonObject("click_event");
        check(event.get("action").getAsString().equals("custom"), "native parser roundtrip retains custom click");
        String token = event.get("id").getAsString();
        check(((Optional<?>) invoke(registry, "resolve", new Class<?>[]{String.class}, token)).orElseThrow().equals("huskhomes:tpaccept Alice"), "exact backend command");
        check(normalized.equals(craft.getMethod("toJSON", component).invoke(null, text)), "original not mutated");
        Object twice = invoke(adapter, "rewrite", new Class<?>[]{Object.class, registryClass}, fixed, registry);
        check(twice == fixed, "idempotent native packet");
        for (String type : List.of("ClientboundDisguisedChatPacket", "ClientboundPlayerChatPacket")) {
            Class<?> cls = Class.forName(game + type);
            var fields = cls.getRecordComponents();
            Class<?>[] types = new Class<?>[fields.length];
            Object[] values = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) {
                types[i] = fields[i].getType();
                if (types[i] == component) values[i] = text;
                else if (types[i] == int.class) values[i] = 42;
                else if (types[i] == UUID.class) values[i] = UUID.randomUUID();
                else if (types[i] == Optional.class) values[i] = fields[i].getName().equals("unsignedContent") ? Optional.of(text) : Optional.empty();
            }
            Object original = cls.getConstructor(types).newInstance(values);
            Object next = invoke(adapter, "rewrite", new Class<?>[]{Object.class, registryClass}, original, registry);
            check(next != original, type + " rewritten");
            for (int i = 0; i < fields.length; i++) {
                if (types[i] != component && !fields[i].getName().equals("unsignedContent"))
                    check(java.util.Objects.equals(values[i], fields[i].getAccessor().invoke(next)), "preserved " + fields[i].getName());
            }
        }
        Class<?> bundleClass = Class.forName(game + "ClientboundBundlePacket");
        Object bundle = bundleClass.getConstructor(Iterable.class).newInstance(List.of(packet));
        check(invoke(adapter, "rewrite", new Class<?>[]{Object.class, registryClass}, bundle, registry) != bundle, "bundled chat");
        Object adventure = packet.getClass().getConstructor(Component.class, boolean.class).newInstance(Component.text("test").clickEvent(ClickEvent.runCommand("help")), false);
        check(invoke(adapter, "rewrite", new Class<?>[]{Object.class, registryClass}, adventure, registry) != adventure, "native Adventure message");
        var errors = new ArrayList<Exception>();
        Class<?> handlerClass = loader.loadClass("io.github.tommasobenatti.ncd.ClickHandler");
        Object handler = construct(handlerClass, new Class<?>[]{interfaceClass, registryClass, Consumer.class}, adapter, registry, (Consumer<Exception>) errors::add);
        EmbeddedChannel channel = new EmbeddedChannel((ChannelHandler) handler);
        channel.writeOutbound(packet);
        Object output = channel.readOutbound(); int registrations = 0;
        while (output != null && output.getClass().getSimpleName().equals("ClientboundCustomPayloadPacket")) {
            Object payload = output.getClass().getMethod("payload").invoke(output);
            check(payload.getClass().getMethod("id").invoke(payload).toString().equals("ncd:register"), "native registration channel");
            registrations++; output = channel.readOutbound();
        }
        check(registrations > 0, "all backend tokens registered before chat");
        check(output != null && output.getClass() == packet.getClass(), "outbound pipeline");
        Class<?> clickClass = Class.forName("net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket");
        Class<?> identifier = clickClass.getRecordComponents()[0].getType();
        Object id = identifier.getMethod("parse", String.class).invoke(null, token);
        Object click = clickClass.getConstructors()[0].newInstance(id, Optional.empty());
        check((int) invoke(adapter, "requestBytes", new Class<?>[]{Object.class}, click) > 0, "decoded request budget");
        channel.writeInbound(click);
        Object command = channel.readInbound();
        check(command != null && command.getClass().getSimpleName().equals("ServerboundChatCommandPacket"), "normal native command route");
        check(command.getClass().getMethod("command").invoke(command).equals("huskhomes:tpaccept Alice"), "normal command unchanged");
        check(errors.isEmpty(), "no transport errors");
        channel.finishAndReleaseAll();
        // Consumed custom clicks must respect a stricter native per-packet DROP policy.
        Class<?> config = Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
        Object limits = config.getMethod("get").invoke(null).getClass().getField("packetLimiter").get(config.getMethod("get").invoke(null));
        var overrideField = limits.getClass().getField("overrides");
        Object originalOverrides = overrideField.get(limits);
        Class<?> limitType = Class.forName("io.papermc.paper.configuration.GlobalConfiguration$PacketLimiter$PacketLimit");
        Class<?> actionType = Class.forName(limitType.getName() + "$ViolateAction");
        Object drop = actionType.getField("DROP").get(null);
        Object strict = limitType.getConstructor(double.class, double.class, actionType).newInstance(1.0, 1.0, drop);
        java.util.Map<Object, Object> overrides = new java.util.HashMap<>(); overrides.put(clickClass, strict);
        Class<?> connectionType = Class.forName("net.minecraft.network.Connection");
        Class<?> flow = Class.forName("net.minecraft.network.protocol.PacketFlow");
        Object connection = connectionType.getConstructor(flow).newInstance(flow.getField("SERVERBOUND").get(null));
        EmbeddedChannel guarded = new EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
        guarded.pipeline().addLast("packet_handler", (ChannelHandler) connection);
        try {
            overrideField.set(limits, overrides);
            Method account = adapterClass.getDeclaredMethod("account", io.netty.channel.ChannelHandlerContext.class, Object.class, boolean.class); account.setAccessible(true);
            check(!(boolean) account.invoke(adapter, guarded.pipeline().firstContext(), click, true), "native custom-click DROP policy preserved");
            check(guarded.isActive(), "native DROP does not become a disconnect");
            var counts = connectionType.getDeclaredField("allPacketCounts"); counts.setAccessible(true);
            Object globalCount = counts.get(connection);
            check(globalCount != null && ((Number) globalCount.getClass().getMethod("getSum").invoke(globalCount)).longValue() == 1,
                    "dropped valid token still charged to native global counter");
        } finally {
            overrideField.set(limits, originalOverrides);
            guarded.pipeline().remove("packet_handler"); guarded.finishAndReleaseAll();
        }
    }

    private void check(boolean result, String label) { if (!result) throw new AssertionError(label); assertions++; }
    private static Object construct(Class<?> cls, Class<?>[] types, Object... args) throws Exception {
        Constructor<?> constructor = cls.getDeclaredConstructor(types); constructor.setAccessible(true); return constructor.newInstance(args);
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, args);
    }
}
