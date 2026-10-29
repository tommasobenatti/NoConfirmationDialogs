package io.github.tommasobenatti.ncd;

import io.netty.channel.Channel;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class Channels implements AutoCloseable {
    private static final String NAME = "ncd_clicks";
    private final Set<Channel> channels = ConcurrentHashMap.newKeySet();
    private final Consumer<Exception> report;
    private volatile boolean closed;

    Channels(Consumer<Exception> report) { this.report = report; }

    void attach(Channel channel, String before, PacketAdapter adapter, String scope) {
        channel.eventLoop().execute(() -> {
            if (closed || !channel.isActive() || channel.pipeline().get(NAME) != null) return;
            try {
                var clicks = new CommandClicks(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), 4096, 43_200_000L, scope);
                channel.pipeline().addBefore(before, NAME, new ClickHandler(adapter, clicks, report));
                channels.add(channel);
                channel.closeFuture().addListener(ignored -> channels.remove(channel));
            } catch (RuntimeException ex) { report.accept(ex); }
        });
    }

    @Override public void close() {
        closed = true;
        for (Channel channel : channels) channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(NAME) != null) channel.pipeline().remove(NAME);
        });
        channels.clear();
    }
}
