package io.github.tommasobenatti.ncd;

import com.google.gson.JsonParser;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandClicksTest {
    @Test void emptyCompoundIsExplicitForBothPlatformScopesWithoutCommandMetadata() {
        for (String scope : new String[]{"paper", "proxy"}) {
            var registry = new CommandClicks(() -> 0, 10, 1000, scope);
            var source = JsonParser.parseString("{\"text\":\"Accept\",\"click_event\":{\"action\":\"run_command\",\"command\":\"/tpaccept Alice\"}}");
            var fixed = registry.rewrite(source).getAsJsonObject().getAsJsonObject("click_event");
            assertNotNull(fixed.get("payload"));
            assertEquals("{}", fixed.get("payload").getAsString());
            assertEquals(3, fixed.size());
            assertFalse(fixed.toString().contains("tpaccept"));
            assertEquals("tpaccept Alice", registry.resolve(fixed.get("id").getAsString()).orElseThrow());
        }
    }
    @Test void convertsNestedChatAndPreservesFormattingAndOtherActions() {
        var clicks = new CommandClicks(() -> 0L, 10, 1000);
        var source = JsonParser.parseString("""
            {"text":"","color":"gold","extra":[
             {"text":"Accept","click_event":{"action":"run_command","command":"/huskhomes:tpaccept Alice"}},
             {"text":"Web","click_event":{"action":"open_url","url":"https://example.org"}},
             {"translate":"test","with":[{"text":"X","click_event":{"action":"run_command","command":"tpdecline"}}]}]}
            """);
        var fixed = clicks.rewrite(source);
        assertNotEquals(source, fixed);
        assertEquals("run_command", source.getAsJsonObject().getAsJsonArray("extra").get(0).getAsJsonObject().getAsJsonObject("click_event").get("action").getAsString());
        var extra = fixed.getAsJsonObject().getAsJsonArray("extra");
        var event = extra.get(0).getAsJsonObject().getAsJsonObject("click_event");
        assertEquals("custom", event.get("action").getAsString());
        assertEquals("huskhomes:tpaccept Alice", clicks.resolve(event.get("id").getAsString()).orElseThrow());
        assertEquals(source.getAsJsonObject().getAsJsonArray("extra").get(1), extra.get(1));
        assertEquals("gold", fixed.getAsJsonObject().get("color").getAsString());
        assertEquals(fixed, clicks.rewrite(fixed));
    }

    @Test void noClicksReturnsOriginalAndMalformedCommandsArePreserved() {
        var clicks = new CommandClicks(() -> 0L, 10, 1000);
        for (String json : new String[]{"\"plain\"", "{\"text\":\"plain\"}", "[\"a\",\"b\"]",
                "{\"click_event\":{\"action\":\"run_command\",\"command\":\"/bad\\ncommand\"}}",
                "{\"click_event\":{\"action\":\"run_command\",\"command\":7}}"}) {
            var source = JsonParser.parseString(json);
            assertSame(source, clicks.rewrite(source));
        }
    }

    @Test void tokensAreSessionBoundExpireAndDoNotTrustPayloadCommands() {
        var clock = new AtomicLong();
        var a = new CommandClicks(clock::get, 2, 1000);
        var b = new CommandClicks(clock::get, 2, 1000);
        String token = a.register("/tpaccept Alice").orElseThrow();
        assertTrue(b.resolve(token).isEmpty());
        assertTrue(a.resolve("ncd:run/forged").isEmpty());
        assertEquals(token, a.register("tpaccept Alice").orElseThrow());
        clock.set(1000);
        assertTrue(a.resolve(token).isEmpty());
        a.clear();
        assertTrue(a.resolve(token).isEmpty());
    }

    @Test void cacheIsBoundedAndEvictsOldestWithoutChangingCommand() {
        var a = new CommandClicks(() -> 0L, 2, 1000);
        String oldest = a.register("one").orElseThrow();
        a.register("two"); a.register("three");
        assertTrue(a.resolve(oldest).isEmpty());
        assertEquals(2, a.size());
        assertTrue(a.register("/").isEmpty());
        assertTrue(a.register("say\u0000evil").isEmpty());
        assertTrue(a.register("x".repeat(32768)).isEmpty());
    }

    @Test void rateLimitsOnlyValidClicksAndResetsWindow() {
        var clock = new AtomicLong();
        var clicks = new CommandClicks(clock::get, 10, 10000);
        var token = clicks.register("help").orElseThrow();
        for (int i = 0; i < 100; i++) assertTrue(clicks.resolve("ncd:run/forged").isEmpty());
        for (int i = 0; i < 20; i++) assertTrue(clicks.resolve(token).isPresent());
        assertTrue(clicks.resolve(token).isEmpty());
        clock.set(1000);
        assertTrue(clicks.resolve(token).isPresent());
    }
}
