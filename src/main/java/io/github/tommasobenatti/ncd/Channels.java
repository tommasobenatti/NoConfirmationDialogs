package io.github.tommasobenatti.ncd;

import io.netty.channel.Channel;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

final class Channels implements AutoCloseable {
    private static final String NAME = "ncd_clicks";
    private final Map<Channel, ClickHandler> channels = new HashMap<>();
    private final Consumer<Exception> report;
    private volatile boolean closed;

    Channels(Consumer<Exception> report) { this.report = report; }

    void attach(Channel channel, String before, PacketAdapter adapter, String scope) {
        attach(channel, before, adapter, scope, () -> true);
    }

    void attach(Channel channel, String before, PacketAdapter adapter, String scope, BooleanSupplier playing) {
        try { channel.eventLoop().execute(() -> {
            synchronized (channels) {
            if (closed || !channel.isActive() || channel.pipeline().get(NAME) != null) return;
            try {
                var clicks = new CommandClicks(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), 4096, 43_200_000L, scope);
                var handler = new ClickHandler(adapter, clicks, report, playing, () -> {
                    synchronized (channels) { channels.remove(channel); }
                });
                channel.pipeline().addBefore(before, NAME, handler);
                channels.put(channel, handler);
            } catch (RuntimeException ex) { report.accept(ex); }
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
