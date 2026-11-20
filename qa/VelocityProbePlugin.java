package io.github.tommasobenatti.ncd.qa;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.chat.ChatType;
import com.velocitypowered.proxy.protocol.packet.chat.ComponentHolder;
import com.velocitypowered.proxy.protocol.packet.chat.SystemChatPacket;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.slf4j.Logger;

public final class VelocityProbePlugin {
    @Inject private ProxyServer server;
    @Inject private Logger logger;
    @Subscribe public void init(ProxyInitializeEvent event) {
        server.getCommandManager().register("ncdproxy", (SimpleCommand) invocation -> logger.info("NCD_PROXY_COMMAND_EXECUTED"));
    }
    @Subscribe public void connect(ServerPostConnectEvent event) {
        event.getPlayer().sendMessage(Component.text("NCD-PROXY-CLICK").clickEvent(ClickEvent.runCommand("/ncdproxy")));
        event.getPlayer().sendMessage(Component.text("NCD-PROXY-BLOCK").clickEvent(ClickEvent.runCommand("/ncdblocked")));
        var player = (ConnectedPlayer) event.getPlayer();
        var channel = player.getConnection().getChannel();
        channel.eventLoop().execute(() -> {
            var version = player.getProtocolVersion();
            var component = Component.text("NCD-PROXY-PREENCODED").clickEvent(ClickEvent.runCommand("/ncdproxy"));
            var packet = new SystemChatPacket(new ComponentHolder(version, component), ChatType.SYSTEM);
            var encoded = channel.alloc().buffer();
            try {
                var registry = StateRegistry.PLAY.getProtocolRegistry(ProtocolUtils.Direction.CLIENTBOUND, version);
                ProtocolUtils.writeVarInt(encoded, registry.getPacketId(packet));
                packet.encode(encoded, ProtocolUtils.Direction.CLIENTBOUND, version);
            } catch (RuntimeException ex) {
                encoded.release();
                throw ex;
            }
            channel.pipeline().context("minecraft-encoder").writeAndFlush(encoded);
        });
    }
    @Subscribe public void command(CommandExecuteEvent event) {
        logger.info("NCD_PROXY_EVENT " + event.getCommand());
        if (event.getCommand().equals("ncdblocked") || event.getCommand().equals("ncdbackendblocked")) {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
            logger.info("NCD_PROXY_EVENT_CANCELLED");
            if (event.getCommand().equals("ncdbackendblocked")) logger.info("NCD_PROXY_BACKEND_EVENT_CANCELLED");
        }
    }
}
