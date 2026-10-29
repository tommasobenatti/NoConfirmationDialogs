package io.github.tommasobenatti.ncd;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClickHandlerTest {
    static final class Adapter implements PacketAdapter {
        boolean fail;
        final List<Object> released = new ArrayList<>();
        @Override public Object rewrite(Object packet, CommandClicks clicks) {
            if (fail) throw new IllegalArgumentException("fixture");
            return packet.equals("chat") ? "fixed" : packet;
        }
        @Override public String clickId(Object packet) { return packet.toString(); }
        @Override public void execute(ChannelHandlerContext ctx, String command) { ctx.fireChannelRead("executed:" + command); }
        @Override public void release(Object packet) { released.add(packet); }
    }

    @Test void rewritesMessagesAndRoutesOnlySessionAuthorizedClicks() {
        var adapter = new Adapter();
        var clicks = new CommandClicks(() -> 0, 10, 1000, "paper");
        var channel = new EmbeddedChannel(new ClickHandler(adapter, clicks, ex -> fail(ex)));
        channel.writeOutbound("chat");
        assertEquals("fixed", channel.readOutbound());
        String token = clicks.register("help").orElseThrow();
        channel.writeInbound(token);
        assertEquals("executed:help", channel.readInbound());
        channel.writeInbound("ncd:proxy/foreign");
        assertEquals("ncd:proxy/foreign", channel.readInbound());
        channel.writeInbound("ncd:paper/forged");
        assertNull(channel.readInbound());
        assertTrue(adapter.released.contains(token));
        channel.finishAndReleaseAll();
        assertEquals(0, clicks.size());
    }

    @Test void conversionFailurePreservesMessagesAndReportsOnce() {
        var adapter = new Adapter(); adapter.fail = true;
        var errors = new ArrayList<Exception>();
        var channel = new EmbeddedChannel(new ClickHandler(adapter, new CommandClicks(() -> 0, 2, 1000), errors::add));
        channel.writeOutbound("one"); channel.writeOutbound("two");
        assertEquals("one", channel.readOutbound());
        assertEquals("two", channel.readOutbound());
        assertEquals(1, errors.size());
        assertTrue(adapter.released.isEmpty());
        channel.finishAndReleaseAll();
    }

    @Test void proxyAdoptsBackendClicksSoItsCommandEventsAndRoutingStillRun() {
        var paper = new CommandClicks(() -> 0, 10, 1000, "paper");
        var proxy = new CommandClicks(() -> 0, 10, 1000, "proxy");
        var original = com.google.gson.JsonParser.parseString("{\"text\":\"click\",\"click_event\":{\"action\":\"run_command\",\"command\":\"help\"}}");
        var fixed = paper.rewrite(original);
        paper.drainExports().forEach(registration -> assertTrue(proxy.importRegistration(registration)));
        var adopted = proxy.rewrite(fixed);
        String token = adopted.getAsJsonObject().getAsJsonObject("click_event").get("id").getAsString();
        assertTrue(proxy.owns(token));
        assertTrue(paper.owns(token));
        assertFalse(adopted.getAsJsonObject().getAsJsonObject("click_event").has("payload"));
        assertEquals("help", proxy.resolve(token).orElseThrow());
        assertSame(adopted, proxy.rewrite(adopted));
    }
}
