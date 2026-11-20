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
import java.util.function.BooleanSupplier;
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
            Channel channel = (Channel) Reflect.call(connection, "getChannel");
            BooleanSupplier playing = () -> {
                try { return Reflect.call(connection, "getState").toString().equals("PLAY"); }
                catch (ReflectiveOperationException ex) { return false; }
            };
            var pipeline = channel.pipeline();
            var names = pipeline.names();
            String framing = pipeline.get("compression-encoder") != null ? "compression-encoder" : "frame-encoder";
            int index = names.indexOf(framing);
            if (index < 0 || index + 1 == names.size()) throw new IllegalStateException("Missing Velocity packet framing");
            // Inspect encoded packets before framing, including writes started at a packet-library encoder.
            channels.attach(channel, "handler", names.get(index + 1),
                    new VelocityPackets(player.getProtocolVersion(), playing), "proxy", playing);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            logger.error("Could not install command click handler", ex);
        }
    }

    @Subscribe public void shutdown(ProxyShutdownEvent event) { if (channels != null) channels.close(); }
}
