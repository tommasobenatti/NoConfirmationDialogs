package io.github.tommasobenatti.ncd.qa;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;
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
    }
    @Subscribe public void command(CommandExecuteEvent event) {
        logger.info("NCD_PROXY_EVENT " + event.getCommand());
        if (event.getCommand().equals("ncdblocked")) {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
            logger.info("NCD_PROXY_EVENT_CANCELLED");
        }
    }
}
