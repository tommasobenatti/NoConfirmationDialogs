package io.github.tommasobenatti.ncd;

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationTest {
    @Test void longCommandsDoNotCreateOversizedClientEchoPayloads() {
        var registry = new CommandClicks(() -> 0, 10, 1000, "paper");
        var original = new com.google.gson.JsonObject(); original.addProperty("text", "long action");
        var event = new com.google.gson.JsonObject(); event.addProperty("action", "run_command"); event.addProperty("command", "x".repeat(32767)); original.add("click_event", event);
        var fixed = registry.rewrite(original).getAsJsonObject().getAsJsonObject("click_event");
        assertFalse(fixed.has("payload"));
        assertEquals(32767, registry.resolve(fixed.get("id").getAsString()).orElseThrow().length());
        assertEquals(32767, RegistrationFrame.decode(RegistrationFrame.encode(registry.drainExports().getFirst())).command().length());
    }
    @Test void freshMessagesRefreshAliasesEvictedIndependentlyAtTheProxy() {
        var paper = new CommandClicks(() -> 0, 10, 1000, "paper");
        var proxy = new CommandClicks(() -> 0, 1, 1000, "proxy");
        String token = paper.register("help").orElseThrow();
        proxy.importRegistration(paper.drainExports().getFirst());
        proxy.register("different"); assertTrue(proxy.resolve(token).isEmpty());
        assertEquals(token, paper.register("help").orElseThrow());
        assertTrue(proxy.importRegistration(paper.drainExports().getFirst()));
        assertEquals("help", proxy.resolve(token).orElseThrow());
    }
    @Test void backendRegistrationGivesProxyAnAliasWithoutTrustingInboundMetadata() {
        var paper = new CommandClicks(() -> 0, 10, 1000, "paper");
        var proxy = new CommandClicks(() -> 0, 10, 1000, "proxy");
        String token = paper.register("/blocked_on_proxy").orElseThrow();
        assertTrue(proxy.resolve(token).isEmpty());
        var exported = paper.drainExports();
        assertEquals(1, exported.size());
        assertTrue(paper.drainExports().isEmpty());
        assertTrue(proxy.importRegistration(RegistrationFrame.decode(RegistrationFrame.encode(exported.getFirst()))));
        assertEquals("blocked_on_proxy", proxy.resolve(token).orElseThrow());
        assertFalse(proxy.importRegistration(new CommandClicks.Registration(token, "different_command")));
        assertFalse(paper.importRegistration(exported.getFirst()));
        proxy.clear(); assertTrue(proxy.resolve(token).isEmpty());
    }

    @Test void malformedFramesAndForeignIdentifiersCannotRegister() {
        var proxy = new CommandClicks(() -> 0, 10, 1000, "proxy");
        assertFalse(proxy.importRegistration(new CommandClicks.Registration("ncd:proxy/" + UUID.randomUUID(), "help")));
        assertFalse(proxy.importRegistration(new CommandClicks.Registration("ncd:paper/not-a-uuid", "help")));
        for (byte[] data : new byte[][]{new byte[0], new byte[RegistrationFrame.MAX_BYTES + 1], new byte[]{2,0,0,0,0,0,0}})
            assertThrows(IllegalArgumentException.class, () -> RegistrationFrame.decode(data));
        String token = "ncd:paper/" + UUID.randomUUID();
        var frame = RegistrationFrame.encode(new CommandClicks.Registration(token, "help"));
        byte[] extra = java.util.Arrays.copyOf(frame, frame.length + 1);
        assertThrows(IllegalArgumentException.class, () -> RegistrationFrame.decode(extra));
        assertFalse(proxy.importRegistration(new CommandClicks.Registration(token, "a\nb")));
    }

    @Test void phaseChangesAndDeactivationNeverExecuteKnownTokens() {
        var adapter = new ClickHandlerTest.Adapter();
        var registry = new CommandClicks(() -> 0, 10, 1000, "proxy");
        String token = registry.register("help").orElseThrow();
        var play = new java.util.concurrent.atomic.AtomicBoolean(false);
        var handler = new ClickHandler(adapter, registry, ex -> fail(ex), play::get, () -> { });
        var channel = new EmbeddedChannel(handler);
        channel.writeInbound(token); assertNull(channel.readInbound());
        play.set(true); channel.writeInbound(token); assertEquals("executed:help", channel.readInbound());
        handler.deactivate(); channel.writeInbound(token); assertNull(channel.readInbound());
        assertTrue(channel.isActive());
        channel.finishAndReleaseAll();
    }

    @Test void requestByteAndCountBudgetsResetAndCoverUnknownTokens() {
        var time = new AtomicLong(); var registry = new CommandClicks(time::get, 10, 1000);
        assertTrue(registry.acceptRequest(1048576)); assertFalse(registry.acceptRequest(1));
        time.set(1000);
        for (int i=0;i<100;i++) assertTrue(registry.acceptRequest(0));
        assertFalse(registry.acceptRequest(0));
        time.set(2000); assertTrue(registry.acceptRequest(1));
    }

    @Test void importedAliasesAndExportsRemainInsideTheSharedMemoryBudget() {
        var paper = new CommandClicks(() -> 0, 4096, 1000, "paper");
        var proxy = new CommandClicks(() -> 0, 4096, 1000, "proxy");
        for (int i=0;i<500;i++) paper.register("help "+i+"x".repeat(8192));
        var exports = paper.drainExports();
        assertEquals(paper.size(), exports.size());
        assertTrue(exports.size() <= 32);
        exports.forEach(registration -> assertTrue(proxy.importRegistration(registration)));
        assertEquals(paper.size(), proxy.size());
    }
}
