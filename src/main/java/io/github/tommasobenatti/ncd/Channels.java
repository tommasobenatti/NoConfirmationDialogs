package io.github.tommasobenatti.ncd;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

final class Channels implements AutoCloseable {
    private static final String NAME = "ncd_clicks";
    private static final String OUTBOUND = "ncd_clicks_outbound";
    private final Map<Channel, ClickHandler> channels = new HashMap<>();
    private final Consumer<Exception> report;
    private volatile boolean closed;

    Channels(Consumer<Exception> report) { this.report = report; }

    void attach(Channel channel, String before, PacketAdapter adapter, String scope) {
        attach(channel, before, adapter, scope, () -> true);
    }

    void attach(Channel channel, String before, PacketAdapter adapter, String scope, BooleanSupplier playing) {
        attach(channel, before, null, adapter, scope, playing);
    }

    void attach(Channel channel, String before, String outboundBefore, PacketAdapter adapter, String scope, BooleanSupplier playing) {
        try { channel.eventLoop().execute(() -> {
            synchronized (channels) {
            if (closed || !channel.isActive() || channel.pipeline().get(NAME) != null) return;
            try {
                var clicks = new CommandClicks(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), 4096, 43_200_000L, scope);
                var handler = new ClickHandler(adapter, clicks, report, playing, () -> {
                    synchronized (channels) { channels.remove(channel); }
                    if (channel.pipeline().get(OUTBOUND) != null) channel.pipeline().remove(OUTBOUND);
                }, outboundBefore == null);
                if (outboundBefore != null) {
                    channel.pipeline().addBefore(outboundBefore, OUTBOUND, new ChannelOutboundHandlerAdapter() {
                        @Override public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) {
                            handler.rewriteWrite(context, packet, promise);
                        }
                    });
                }
                channel.pipeline().addBefore(before, NAME, handler);
                channels.put(channel, handler);
            } catch (RuntimeException ex) {
                if (channel.pipeline().get(OUTBOUND) != null) channel.pipeline().remove(OUTBOUND);
                report.accept(ex);
            }
            }
        }); } catch (java.util.concurrent.RejectedExecutionException ex) {
            if (!closed && channel.isActive()) report.accept(ex);
        }
    }

    @Override public void close() {
        java.util.List<Channel> snapshot;
        synchronized (channels) {
            closed = true;
            channels.values().forEach(ClickHandler::deactivate);
            snapshot = new ArrayList<>(channels.keySet());
            channels.clear();
        }
        for (Channel channel : snapshot) {
            try { channel.eventLoop().execute(() -> {
                if (channel.pipeline().get(NAME) != null) channel.pipeline().remove(NAME);
            }); } catch (java.util.concurrent.RejectedExecutionException ex) {
                if (channel.isActive()) report.accept(ex);
            }
        }
    }
}
