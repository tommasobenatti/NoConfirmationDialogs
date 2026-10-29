package io.github.tommasobenatti.ncd;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HardeningTest {
    @Test void proxyDoesNotForwardUnknownPaperCapabilitiesAroundCommandPolicy() {
        var adapter = new ClickHandlerTest.Adapter();
        var registry = new CommandClicks(() -> 0, 20, 1000, "proxy");
        var channel = new EmbeddedChannel(new ClickHandler(adapter, registry, ex -> fail(ex)));
        channel.writeInbound("ncd:paper/unknown");
        assertNull(channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test void unknownTokenFloodIsDisconnectedWithoutNeedingAValidToken() {
        var adapter = new ClickHandlerTest.Adapter();
        var channel = new EmbeddedChannel(new ClickHandler(adapter, new CommandClicks(() -> 0, 20, 1000, "proxy"), ex -> fail(ex)));
        for (int i = 0; i < 101; i++) channel.writeInbound("ncd:proxy/junk");
        assertFalse(channel.isActive());
        channel.finishAndReleaseAll();
    }

    @Test void retainedTextHasAnAggregateBudgetEvenBelowEntryCapacity() throws Exception {
        var registry = new CommandClicks(() -> 0, 4096, 1000);
        for (int i = 0; i < 100; i++) registry.register("help " + i + "x".repeat(8192));
        var field = CommandClicks.class.getDeclaredField("entries"); field.setAccessible(true);
        long chars = 0;
        for (Object entry : ((java.util.Map<?, ?>) field.get(registry)).values()) {
            var command = entry.getClass().getDeclaredMethod("command"); command.setAccessible(true);
            chars += ((String) command.invoke(entry)).length();
        }
        assertTrue(chars <= 262144, "Unbounded retained command text: " + chars);
    }

    @Test void excessiveTreeDepthIsRejectedBeforeDeepCopy() {
        var root = JsonParser.parseString("{\"click_event\":{\"action\":\"run_command\",\"command\":\"help\"}}").getAsJsonObject();
        JsonArray nested = new JsonArray(); root.add("extra", nested);
        for (int i = 0; i < 10000; i++) { var next = new JsonArray(); nested.add(next); nested = next; }
        assertSame(root, new CommandClicks(() -> 0, 10, 1000).rewrite(root));
    }

    @Test void oversizedTreesAreBoundedWithoutChangingOriginal() {
        var root = JsonParser.parseString("{\"click_event\":{\"action\":\"run_command\",\"command\":\"help\"}}").getAsJsonObject();
        var children = new JsonArray(); for (int i = 0; i < 10000; i++) children.add("x"); root.add("extra", children);
        assertSame(root, new CommandClicks(() -> 0, 10, 1000).rewrite(root));
    }
}
