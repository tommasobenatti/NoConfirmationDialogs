package io.github.tommasobenatti.ncd;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

final class CommandClicks {
    private record Entry(String command, long created) { }
    private final LongSupplier clock;
    private final int capacity;
    private final long lifetime;
    private final String prefix;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, String> commands = new HashMap<>();
    private long window;
    private int clicks;

    CommandClicks(LongSupplier clock, int capacity, long lifetime) {
        this(clock, capacity, lifetime, "run");
    }

    CommandClicks(LongSupplier clock, int capacity, long lifetime, String scope) {
        if (capacity < 1 || lifetime < 1) throw new IllegalArgumentException("Invalid cache bounds");
        this.clock = clock;
        this.capacity = capacity;
        this.lifetime = lifetime;
        this.prefix = "ncd:" + scope + "/";
        this.window = clock.getAsLong();
    }

    // All registry access is synchronized: packet writes can originate outside the event loop.
    synchronized Optional<String> register(String command) {
        if (command.startsWith("/")) command = command.substring(1);
        if (command.isBlank() || command.length() > 32767
                || command.chars().anyMatch(c -> c < 32 || c == 127 || c == 167)) return Optional.empty();
        long now = clock.getAsLong();
        String existing = commands.get(command);
        if (existing != null && now - entries.get(existing).created < lifetime) return Optional.of(existing);
        if (existing != null) remove(existing);
        while (!entries.isEmpty() && (entries.size() >= capacity
                || now - entries.values().iterator().next().created >= lifetime)) {
            remove(entries.keySet().iterator().next());
        }
        String token = prefix + UUID.randomUUID();
        entries.put(token, new Entry(command, now));
        commands.put(command, token);
        return Optional.of(token);
    }

    synchronized Optional<String> resolve(String token) {
        Entry entry = entries.get(token);
        long now = clock.getAsLong();
        if (entry == null) return Optional.empty();
        if (now - entry.created >= lifetime) {
            remove(token);
            return Optional.empty();
        }
        if (now - window >= 1000) { window = now; clicks = 0; }
        if (clicks >= 20) return Optional.empty();
        clicks++;
        return Optional.of(entry.command);
    }

    boolean owns(String token) { return token.startsWith(prefix); }
    synchronized void clear() { entries.clear(); commands.clear(); clicks = 0; }
    synchronized int size() { return entries.size(); }
    private void remove(String token) { commands.remove(entries.remove(token).command); }

    JsonElement rewrite(JsonElement source) {
        if (!hasCommand(source, 0)) return source;
        JsonElement copy = source.deepCopy();
        return rewriteInPlace(copy, 0) ? copy : source;
    }

    private boolean hasCommand(JsonElement value, int depth) {
        if (depth > 64) return false;
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (commandFor(object.get("click_event")).isPresent()) return true;
            for (var field : object.entrySet()) if (hasCommand(field.getValue(), depth + 1)) return true;
        } else if (value.isJsonArray()) {
            for (var child : value.getAsJsonArray()) if (hasCommand(child, depth + 1)) return true;
        }
        return false;
    }

    private boolean rewriteInPlace(JsonElement value, int depth) {
        if (depth > 64) return false;
        boolean changed = false;
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            JsonElement event = object.get("click_event");
            var command = commandFor(event);
            if (command.isPresent()) {
                var token = register(command.get());
                if (token.isPresent()) {
                    JsonObject replacement = new JsonObject();
                    replacement.addProperty("action", "custom");
                    replacement.addProperty("id", token.get());
                    // The proxy learns the command from the server-authored outbound message only.
                    // Inbound payloads are never consulted when executing a click.
                    JsonObject metadata = new JsonObject();
                    metadata.addProperty("ncd_command", command.get());
                    replacement.addProperty("payload", metadata.toString());
                    object.add("click_event", replacement);
                    changed = true;
                }
            }
            for (var field : object.entrySet()) changed |= rewriteInPlace(field.getValue(), depth + 1);
        } else if (value.isJsonArray()) {
            for (var child : value.getAsJsonArray()) changed |= rewriteInPlace(child, depth + 1);
        }
        return changed;
    }

    private Optional<String> commandFor(JsonElement event) {
        if (event == null || !event.isJsonObject()) return Optional.empty();
        JsonObject object = event.getAsJsonObject();
        JsonElement action = object.get("action"), command = object.get("command");
        if (action == null || !action.isJsonPrimitive() || !action.getAsJsonPrimitive().isString()) return Optional.empty();
        if (action.getAsString().equals("run_command") && isString(command)) return Optional.of(command.getAsString());
        if (prefix.equals("ncd:proxy/") && action.getAsString().equals("custom")
                && isString(object.get("id")) && object.get("id").getAsString().startsWith("ncd:paper/")
                && isString(object.get("payload"))) {
            try {
                // JSON is a subset of SNBT. Native serializers can remove quotes from SNBT keys;
                // Gson's lenient tree parser accepts both representations used here.
                JsonElement payload = com.google.gson.JsonParser.parseString(object.get("payload").getAsString());
                if (payload.isJsonObject()) {
                    JsonElement metadata = payload.getAsJsonObject().get("ncd_command");
                    if (isString(metadata)) return Optional.of(metadata.getAsString());
                }
            } catch (com.google.gson.JsonParseException ignored) { /* Leave foreign payloads unchanged. */ }
        }
        return Optional.empty();
    }

    private boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }
}
