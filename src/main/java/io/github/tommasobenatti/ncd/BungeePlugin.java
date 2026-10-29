package io.github.tommasobenatti.ncd;

import io.netty.channel.Channel;
import java.util.logging.Level;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

public final class BungeePlugin extends Plugin implements Listener {
    private Channels channels;

    @Override public void onEnable() {
        channels = new Channels(ex -> getLogger().log(Level.SEVERE, "Could not process command clicks", ex));
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPlayers().forEach(this::attach);
        getLogger().info("NCD enabled: automatic proxy and backend command clicks");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PostLoginEvent event) { attach(event.getPlayer()); }

    private void attach(ProxiedPlayer player) {
        if (player.getPendingConnection().getVersion() < 771) return;
        try {
            Object wrapper = Reflect.call(player, "getCh");
            channels.attach((Channel) Reflect.call(wrapper, "getHandle"), "inbound-boss", new BungeePackets(player), "proxy");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            getLogger().log(Level.SEVERE, "Could not install command click handler", ex);
        }
    }

    @Override public void onDisable() { if (channels != null) channels.close(); }
}
