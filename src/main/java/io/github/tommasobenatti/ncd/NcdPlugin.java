package io.github.tommasobenatti.ncd;

import io.netty.channel.Channel;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class NcdPlugin extends JavaPlugin implements Listener {
    private Channels channels;
    private PaperPackets packets;

    @Override public void onEnable() {
        try {
            packets = new PaperPackets();
            channels = new Channels(ex -> getLogger().log(Level.SEVERE, "Could not process command clicks", ex));
            getServer().getPluginManager().registerEvents(this, this);
            getServer().getOnlinePlayers().forEach(this::attach);
            getLogger().info("NCD enabled: automatic command clicks on " + getServer().getMinecraftVersion());
        } catch (ReflectiveOperationException | RuntimeException ex) {
            getLogger().log(Level.SEVERE, "Unsupported server internals; NCD cannot start", ex);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) { attach(event.getPlayer()); }

    private void attach(Player player) {
        try {
            Object handle = Reflect.call(player, "getHandle");
            Object listener = Reflect.get(handle, "connection");
            Object connection = Reflect.get(listener, "connection");
            Channel channel = (Channel) Reflect.get(connection, "channel");
            // ViaVersion exposes the original client protocol when installed on this backend.
            if (getServer().getPluginManager().isPluginEnabled("ViaVersion")) {
                Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
                Object api = via.getMethod("getAPI").invoke(null);
                int protocol = (int) api.getClass().getMethod("getPlayerVersion", java.util.UUID.class).invoke(api, player.getUniqueId());
                if (protocol < 771) return;
            }
            Class<?> playListener = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl");
            channels.attach(channel, "packet_handler", packets, "paper", () -> {
                try { return playListener.isInstance(Reflect.call(connection, "getPacketListener")); }
                catch (ReflectiveOperationException ex) { return false; }
            });
        } catch (ReflectiveOperationException | RuntimeException ex) {
            getLogger().log(Level.SEVERE, "Could not install command click handler", ex);
        }
    }

    @Override public void onDisable() { if (channels != null) channels.close(); }
}
