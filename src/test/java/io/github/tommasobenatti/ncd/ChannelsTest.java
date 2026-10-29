package io.github.tommasobenatti.ncd;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChannelsTest {
    @Test void disablingDoesNotLeaveCloseListenersRetainingTheManager() throws Exception {
        var channel = new EmbeddedChannel(); channel.pipeline().addLast("host", new ChannelInboundHandlerAdapter());
        var listeners = Reflect.field(channel.closeFuture().getClass(), "listeners");
        Object before = listeners.get(channel.closeFuture());
        var manager = new Channels(ex -> fail(ex));
        manager.attach(channel, "host", new ClickHandlerTest.Adapter(), "proxy"); channel.runPendingTasks();
        manager.close(); channel.runPendingTasks();
        assertSame(before, listeners.get(channel.closeFuture()));
        channel.finishAndReleaseAll();
    }

    @Test void concurrentCloseCannotMissAnAttachmentPastTheClosedCheck() throws Exception {
        var channel = new EmbeddedChannel(); channel.pipeline().addLast("host", new ChannelInboundHandlerAdapter());
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        var facade = (io.netty.channel.Channel) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{io.netty.channel.Channel.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("isActive") && once.compareAndSet(false, true)) {
                        entered.countDown();
                        if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timed out");
                    }
                    return method.invoke(channel, arguments);
                });
        var errors = new java.util.concurrent.ConcurrentLinkedQueue<Exception>();
        var manager = new Channels(errors::add);
        manager.attach(facade, "host", new ClickHandlerTest.Adapter(), "proxy");
        Thread attaching = new Thread(channel::runPendingTasks); attaching.start();
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        var closed = new java.util.concurrent.CountDownLatch(1);
        Thread closing = new Thread(() -> { manager.close(); closed.countDown(); }); closing.start();
        // On the vulnerable implementation close finishes while the attachment is still unpublished.
        closed.await(100, java.util.concurrent.TimeUnit.MILLISECONDS);
        release.countDown(); attaching.join(5000); closing.join(5000); channel.runPendingTasks();
        assertFalse(attaching.isAlive()); assertFalse(closing.isAlive());
        assertTrue(errors.isEmpty()); assertNull(channel.pipeline().get("ncd_clicks"));
        channel.finishAndReleaseAll();
    }
    @Test void closeBeforeQueuedAttachmentCannotLeaveAHandler() {
        var channel = new EmbeddedChannel(); channel.pipeline().addLast("host", new ChannelInboundHandlerAdapter());
        var manager = new Channels(ex -> fail(ex));
        manager.attach(channel, "host", new ClickHandlerTest.Adapter(), "proxy");
        manager.close(); channel.runPendingTasks();
        assertNull(channel.pipeline().get("ncd_clicks"));
        channel.finishAndReleaseAll();
    }

    @Test void attachedConnectionsAreCleanedWithoutClosingThePlayer() {
        for (int i=0;i<200;i++) {
            var channel = new EmbeddedChannel(); channel.pipeline().addLast("host", new ChannelInboundHandlerAdapter());
            var manager = new Channels(ex -> fail(ex));
            manager.attach(channel, "host", new ClickHandlerTest.Adapter(), "proxy"); channel.runPendingTasks();
            assertNotNull(channel.pipeline().get("ncd_clicks"));
            manager.close(); channel.runPendingTasks();
            assertNull(channel.pipeline().get("ncd_clicks")); assertTrue(channel.isActive());
            manager.close(); channel.finishAndReleaseAll();
        }
    }
}
