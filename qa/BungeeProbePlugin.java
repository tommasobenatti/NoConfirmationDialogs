package io.github.tommasobenatti.ncd.qa;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

public final class BungeeProbePlugin extends Plugin implements Listener {
    @Override public void onEnable() {
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerCommand(this, new Command("ncdproxy") {
            @Override public void execute(CommandSender sender, String[] args) { getLogger().info("NCD_PROXY_COMMAND_EXECUTED"); }
        });
    }
    @EventHandler public void connect(ServerSwitchEvent event) {
        var message = new TextComponent("NCD-PROXY-CLICK");
        message.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ncdproxy"));
        event.getPlayer().sendMessage(message);
        var blocked = new TextComponent("NCD-PROXY-BLOCK");
        blocked.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ncdblocked"));
        event.getPlayer().sendMessage(blocked);
    }
    @EventHandler public void command(ChatEvent event) {
        getLogger().info("NCD_PROXY_EVENT " + event.getMessage());
        if (event.getMessage().equals("/ncdblocked")) {
            event.setCancelled(true);
            getLogger().info("NCD_PROXY_EVENT_CANCELLED");
        }
    }
}
