package io.github.tommasobenatti.ncd;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

final class CommandClicks {
    static final int MAX_CHARACTERS = 262144;
    record Registration(String token, String command) { }
    private record Entry(String command, long created) { }
    private final LongSupplier clock;
    private final int capacity;
    private final long lifetime;
    private final String prefix;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, String> commands = new HashMap<>();
    private final Map<String, String> exports = new LinkedHashMap<>();
    private int retainedCharacters;
    private long window;
    private int clicks;
    private long requestWindow;
    private int requests;
    private int requestBytes;

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
        this.requestWindow = window;
    }

    // All registry access is synchronized: packet writes can originate outside the event loop.
    synchronized Optional<String> register(String command) {
        if (command == null) return Optional.empty();
        if (command.startsWith("/")) command = command.substring(1);
        if (!validCommand(command)) return Optional.empty();
        long now = clock.getAsLong();
        String existing = commands.get(command);
        Entry retained = existing == null ? null : entries.get(existing);
        if (retained != null && now - retained.created < lifetime) {
            if (prefix.equals("ncd:paper/")) exports.put(existing, retained.command);
            return Optional.of(existing);
        }
        if (existing != null) remove(existing);
        makeRoom(command.length(), now);
        String token = prefix + UUID.randomUUID();
        entries.put(token, new Entry(command, now));
        commands.put(command, token);
        retainedCharacters += command.length();
        if (prefix.equals("ncd:paper/")) exports.put(token, command);
        return Optional.of(token);
    }

    synchronized boolean importRegistration(Registration registration) {
        if (!prefix.equals("ncd:proxy/") || !paperToken(registration.token()) || !validCommand(registration.command())) return false;
        Entry old = entries.get(registration.token());
        long now = clock.getAsLong();
        if (old != null && now - old.created < lifetime) return old.command.equals(registration.command());
        if (old != null) remove(registration.token());
        makeRoom(registration.command().length(), now);
        entries.put(registration.token(), new Entry(registration.command(), now));
        retainedCharacters += registration.command().length();
        return true;
    }

    synchronized List<Registration> drainExports() {
        if (exports.isEmpty()) return List.of();
        List<Registration> result = new ArrayList<>(exports.size());
        exports.forEach((token, command) -> result.add(new Registration(token, command)));
        exports.clear();
        return result;
    }

    private void makeRoom(int characters, long now) {
        while (!entries.isEmpty() && (entries.size() >= capacity || retainedCharacters + characters > MAX_CHARACTERS
                || now - entries.values().iterator().next().created >= lifetime)) remove(entries.keySet().iterator().next());
    }

    private static boolean validCommand(String command) {
        return command != null && !command.isBlank() && command.length() <= 32767
                && command.codePoints().noneMatch(c -> c < 32 || c == 127 || c == 167 || (c >= 0xd800 && c <= 0xdfff));
    }

    static boolean paperToken(String token) {
        if (token == null || !token.startsWith("ncd:paper/") || token.length() != 46) return false;
        try { return UUID.fromString(token.substring(10)).toString().equals(token.substring(10)); }
        catch (IllegalArgumentException ex) { return false; }
    }

    synchronized boolean acceptRequest(int bytes) {
        long now = clock.getAsLong();
        if (now - requestWindow >= 1000) { requestWindow = now; requests = 0; requestBytes = 0; }
        if (bytes < 0 || bytes > 1048576 - requestBytes) return false;
        requestBytes += bytes;
        return ++requests <= 100;
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

    boolean owns(String token) { return token.startsWith(prefix) || (prefix.equals("ncd:proxy/") && token.startsWith("ncd:paper/")); }
    synchronized void clear() { entries.clear(); commands.clear(); exports.clear(); retainedCharacters = 0; clicks = 0; }
    synchronized int size() { return entries.size(); }
    private void remove(String token) {
        Entry entry = entries.remove(token);
        retainedCharacters -= entry.command.length();
        commands.remove(entry.command, token);
        exports.remove(token);
    }

    JsonElement rewrite(JsonElement source) {
        if (!hasCommand(source, 0)) return source;
        if (!withinBudget(source)) return source;
        JsonElement copy = source.deepCopy();
        return rewriteInPlace(copy, 0) ? copy : source;
    }

    private record Node(JsonElement value, int depth) { }
    private boolean withinBudget(JsonElement source) {
        ArrayDeque<Node> work = new ArrayDeque<>(); work.push(new Node(source, 0));
        int nodes = 0, characters = 0;
        while (!work.isEmpty()) {
            Node node = work.pop();
            if (++nodes > 8192 || node.depth > 64) return false;
            JsonElement value = node.value;
            if (value.isJsonObject()) {
                for (var field : value.getAsJsonObject().entrySet()) {
                    characters += field.getKey().length();
                    work.push(new Node(field.getValue(), node.depth + 1));
                    if (work.size() > 8192) return false;
                }
            } else if (value.isJsonArray()) {
                for (var child : value.getAsJsonArray()) {
                    work.push(new Node(child, node.depth + 1));
                    if (work.size() > 8192) return false;
                }
            } else if (isString(value)) characters += value.getAsString().length();
            if (characters > MAX_CHARACTERS) return false;
        }
        return true;
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
                    // An absent payload is represented as END by PacketEvents 2.14.0;
                    // IC's Adventure conversion cannot stringify that tag. Use a tiny explicit value.
                    replacement.addProperty("payload", "{}");
                    // Commands travel only over the directional server registration channel.
                    // The client receives an identifier with no command-sized echo payload.
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
        return Optional.empty();
    }

    private boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }
}
