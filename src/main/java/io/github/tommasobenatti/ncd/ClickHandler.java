package io.github.tommasobenatti.ncd;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.util.function.Consumer;

final class ClickHandler extends ChannelDuplexHandler {
    private final PacketAdapter adapter;
    private final CommandClicks clicks;
    private final Consumer<Exception> report;
    private boolean reported;

    ClickHandler(PacketAdapter adapter, CommandClicks clicks, Consumer<Exception> report) {
        this.adapter = adapter;
        this.clicks = clicks;
        this.report = report;
    }

    @Override public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) throws Exception {
        Object rewritten = packet;
        try {
            rewritten = adapter.rewrite(packet, clicks);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            reportOnce(ex); // Preserve the original message if a third-party packet cannot be handled.
        }
        if (rewritten != packet) adapter.release(packet);
        context.write(rewritten, promise);
    }

    @Override public void channelRead(ChannelHandlerContext context, Object packet) throws Exception {
        String id;
        try {
            id = adapter.clickId(packet);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            reportOnce(ex);
            context.fireChannelRead(packet);
            return;
        }
        // Another NCD layer owns other scopes; forward them unchanged to that layer.
        if (id == null || !clicks.owns(id)) {
            context.fireChannelRead(packet);
            return;
        }
        try {
            var command = clicks.resolve(id);
            if (command.isPresent()) adapter.execute(context, command.get());
        } catch (ReflectiveOperationException | RuntimeException ex) {
            reportOnce(ex);
        } finally {
            adapter.release(packet);
        }
    }

    @Override public void handlerRemoved(ChannelHandlerContext context) { clicks.clear(); }
    @Override public void channelInactive(ChannelHandlerContext context) throws Exception {
        clicks.clear();
        context.fireChannelInactive();
    }
    private void reportOnce(Exception ex) {
        if (!reported) { reported = true; report.accept(ex); }
    }
}
