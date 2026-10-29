package io.github.tommasobenatti.ncd;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

final class ClickHandler extends ChannelDuplexHandler {
    private final PacketAdapter adapter;
    private final CommandClicks clicks;
    private final Consumer<Exception> report;
    private boolean reported;
    private volatile boolean active = true;
    private final BooleanSupplier playing;
    private final Runnable detached;

    ClickHandler(PacketAdapter adapter, CommandClicks clicks, Consumer<Exception> report) {
        this(adapter, clicks, report, () -> true, () -> { });
    }
    ClickHandler(PacketAdapter adapter, CommandClicks clicks, Consumer<Exception> report, BooleanSupplier playing, Runnable detached) {
        this.adapter = adapter;
        this.clicks = clicks;
        this.report = report;
        this.playing = playing;
        this.detached = detached;
    }

    @Override public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) throws Exception {
        Object rewritten = packet;
        if (!active) { context.write(packet, promise); return; }
        try {
            if (adapter.control(packet, clicks)) {
                adapter.release(packet); promise.setSuccess(); return;
            }
            rewritten = adapter.rewrite(packet, clicks);
            adapter.publish(context, clicks);
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
            if (ex instanceof MalformedClickException) {
                adapter.release(packet); context.close(); return;
            }
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
            if (!active) return;
            if (!clicks.acceptRequest(adapter.requestBytes(packet))) { context.close(); return; }
            var command = playing.getAsBoolean() ? clicks.resolve(id) : java.util.Optional.<String>empty();
            if (!adapter.account(context, packet, command.isPresent())) return;
            if (command.isPresent()) adapter.execute(context, command.get());
        } catch (ReflectiveOperationException | RuntimeException ex) {
            reportOnce(ex);
        } finally {
            adapter.release(packet);
        }
    }

    void deactivate() { active = false; clicks.clear(); }
    @Override public void handlerRemoved(ChannelHandlerContext context) { deactivate(); detached.run(); }
    @Override public void channelInactive(ChannelHandlerContext context) throws Exception {
        deactivate(); detached.run();
        context.fireChannelInactive();
    }
    private void reportOnce(Exception ex) {
        if (!reported) { reported = true; report.accept(ex); }
    }
}
