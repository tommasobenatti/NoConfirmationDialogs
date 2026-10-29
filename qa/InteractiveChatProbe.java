package io.github.tommasobenatti.ncd.qa;

import com.google.gson.JsonParser;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.java.JavaPlugin;

/** Separate test plugin: actual NCD -> native NBT -> PacketEvents -> InteractiveChat conversion. */
public final class InteractiveChatProbe extends JavaPlugin {
    private int assertions;

    @Override public void onEnable() {
        getServer().getScheduler().runTaskLater(this, () -> {
            try {
                for (String name : new String[]{"NoConfirmationDialogs", "InteractiveChat", "InteractiveChat-PacketEvents", "packetevents"}) {
                    var plugin = getServer().getPluginManager().getPlugin(name);
                    require(plugin != null && plugin.isEnabled(), "Enabled " + name);
                    getLogger().info("NCD_COMPAT_VERSION " + name + "=" + plugin.getDescription().getVersion());
                }
                var ic = getServer().getPluginManager().getPlugin("InteractiveChat");
                Object platform = ic.getClass().getField("protocolPlatform").get(null);
                require(platform != null && platform.getClass().getName().toLowerCase().contains("packetevents"), "Real PacketEvents bridge active");
                // This normally initializes on the first player's message; do it while IC is enabled.
                Class.forName("com.loohp.interactivechat.listeners.packet.MessagePacketHandler", true, ic.getClass().getClassLoader());
                for (String command : new String[]{"huskhomes:tpaccept Alice", "huskhomes:tpdecline Alice", "help " + "x".repeat(32762)}) roundtrip(command);
                if (Boolean.getBoolean("ncd.compat.expect-red")) throw new AssertionError("Expected original failure was not observed");
                getLogger().info("NCD_ICPE_PASS assertions=" + assertions);
            } catch (Throwable failure) {
                Throwable root = failure;
                while (root.getCause() != null) root = root.getCause();
                if (Boolean.getBoolean("ncd.compat.expect-red") && root.toString().contains("EndBinaryTag")) {
                    getLogger().info("NCD_ICPE_REPRODUCED " + root);
                } else getLogger().log(java.util.logging.Level.SEVERE, "NCD_ICPE_FAIL", failure);
            }
            if (!Boolean.getBoolean("ncd.compat.keep-running")) getServer().shutdown();
        }, 100L);
    }

    private void roundtrip(String command) throws Exception {
        var ncd = getServer().getPluginManager().getPlugin("NoConfirmationDialogs");
        ClassLoader loader = ncd.getClass().getClassLoader();
        Class<?> registryType = loader.loadClass("io.github.tommasobenatti.ncd.CommandClicks");
        Constructor<?> constructor = registryType.getDeclaredConstructor(LongSupplier.class, int.class, long.class, String.class);
        constructor.setAccessible(true);
        Object registry = constructor.newInstance((LongSupplier) () -> 0L, 10, 1000L, "paper");
        var source = JsonParser.parseString("{\"text\":\"\",\"color\":\"gold\",\"extra\":[{\"text\":\"Accept\",\"click_event\":{\"action\":\"run_command\",\"command\":\"placeholder\"}},{\"text\":\" Web\",\"click_event\":{\"action\":\"open_url\",\"url\":\"https://example.org\"}}]}");
        source.getAsJsonObject().getAsJsonArray("extra").get(0).getAsJsonObject().getAsJsonObject("click_event").addProperty("command", command);
        Method rewrite = registryType.getDeclaredMethod("rewrite", com.google.gson.JsonElement.class); rewrite.setAccessible(true);
        Object rewritten = rewrite.invoke(registry, source);

        // Use the real native encoder so missing payload becomes precisely the END form from the log.
        Class<?> component = Class.forName("net.minecraft.network.chat.Component");
        Class<?> craft = Class.forName("org.bukkit.craftbukkit.util.CraftChatMessage");
        Object nativeComponent = craft.getMethod("fromJSON", String.class).invoke(null, rewritten.toString());
        Class<?> packet = Class.forName("net.minecraft.network.protocol.game.ClientboundSystemChatPacket");
        Object systemChat = packet.getConstructor(component, boolean.class).newInstance(nativeComponent, false);
        Object server = getServer().getClass().getMethod("getServer").invoke(getServer());
        Object access = server.getClass().getMethod("registryAccess").invoke(server);
        Class<?> registryAccess = Class.forName("net.minecraft.core.RegistryAccess");
        ByteBuf bytes = Unpooled.buffer();
        try {
            Object friendly = Class.forName("net.minecraft.network.RegistryFriendlyByteBuf")
                    .getConstructor(ByteBuf.class, registryAccess).newInstance(bytes, access);
            Object codec = packet.getField("STREAM_CODEC").get(null);
            Method encode = codec.getClass().getMethod("encode", Object.class, Object.class); encode.setAccessible(true);
            encode.invoke(codec, friendly, systemChat);
            WrapperPlayServerSystemChatMessage.HANDLE_JSON = false;
            var wrapper = new WrapperPlayServerSystemChatMessage(false, Component.empty());
            wrapper.setBuffer(bytes);
            wrapper.read();
            require(!bytes.isReadable(), "PacketEvents consumed actual native packet body");
            Object decoded = wrapper.getMessage();
            var ic = getServer().getPluginManager().getPlugin("InteractiveChat");
            ClassLoader icLoader = ic.getClass().getClassLoader();
            Class<?> converter = icLoader.loadClass("com.loohp.interactivechat.utils.NativeAdventureConverter");
            Object shadow = converter.getMethod("componentFromNative", Object.class).invoke(null, decoded);
            Class<?> shadowComponent = icLoader.loadClass("com.loohp.interactivechat.libs.net.kyori.adventure.text.Component");
            Object nativeAgain = converter.getMethod("componentToNative", shadowComponent, boolean.class).invoke(null, shadow, false);
            String json = (String) converter.getMethod("jsonStringFromNative", Object.class).invoke(null, nativeAgain);
            var tree = JsonParser.parseString(json).getAsJsonObject();
            var action = tree.getAsJsonArray("extra").get(0).getAsJsonObject().getAsJsonObject("click_event");
            require(action.get("action").getAsString().equals("custom"), "Custom action survives IC/PE");
            require(action.has("payload") && action.get("payload").getAsString().length() <= 16, "Explicit inert payload survives IC/PE");
            Method resolve = registryType.getDeclaredMethod("resolve", String.class); resolve.setAccessible(true);
            require(((Optional<?>) resolve.invoke(registry, action.get("id").getAsString())).orElseThrow().equals(command), "Exact registered command survives");
            require(tree.getAsJsonArray("extra").get(1).getAsJsonObject().getAsJsonObject("click_event").get("action").getAsString().equals("open_url"), "Unrelated action preserved");
            require(!json.contains("ncd_command") && !json.contains(command), "No command echo metadata");
            // Exercise the actual IC -> native encode -> PE -> IC path repeatedly, not just JSON.
            for (int pass = 0; pass < 3; pass++) {
                bytes.clear();
                Object again = packet.getConstructor(Component.class, boolean.class).newInstance(nativeAgain, false);
                encode.invoke(codec, friendly, again);
                var next = new WrapperPlayServerSystemChatMessage(false, Component.empty()); next.setBuffer(bytes); next.read();
                shadow = converter.getMethod("componentFromNative", Object.class).invoke(null, next.getMessage());
                nativeAgain = converter.getMethod("componentToNative", shadowComponent, boolean.class).invoke(null, shadow, false);
                json = (String) converter.getMethod("jsonStringFromNative", Object.class).invoke(null, nativeAgain);
                var nextAction = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("extra").get(0).getAsJsonObject().getAsJsonObject("click_event");
                require(nextAction.get("id").equals(action.get("id")), "Repeated conversion preserves token");
                require(nextAction.has("payload") && nextAction.get("payload").getAsString().length() <= 16, "Repeated conversion keeps payload bounded");
            }
        } finally { bytes.release(); }
    }

    private void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); assertions++; }
}
