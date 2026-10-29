package io.github.tommasobenatti.ncd;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.channel.Channel;
import org.slf4j.Logger;

public final class VelocityPlugin {
    private final ProxyServer server;
    private final Logger logger;
    private Channels channels;
    private VelocityPackets packets;

    @Inject public VelocityPlugin(ProxyServer server, Logger logger) { this.server = server; this.logger = logger; }

    @Subscribe public void initialize(ProxyInitializeEvent event) throws ReflectiveOperationException {
        packets = new VelocityPackets();
        channels = new Channels(ex -> logger.error("Could not process command clicks", ex));
        server.getAllPlayers().forEach(this::attach);
        logger.info("NCD enabled: automatic proxy and backend command clicks");
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onLogin(PostLoginEvent event) { attach(event.getPlayer()); }

    private void attach(Player player) {
        if (player.getProtocolVersion().getProtocol() < 771 || channels == null) return;
        try {
            Object connection = Reflect.call(player, "getConnection");
            channels.attach((Channel) Reflect.call(connection, "getChannel"), "handler", new VelocityPackets(player.getProtocolVersion()), "proxy");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            logger.error("Could not install command click handler", ex);
        }
    }

    @Subscribe public void shutdown(ProxyShutdownEvent event) { if (channels != null) channels.close(); }
}
