package io.github.tommasobenatti.ncd;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Directional server-to-proxy control message, never accepted from an inbound client packet. */
final class RegistrationFrame {
    static final String CHANNEL = "ncd:register";
    static final int MAX_BYTES = 131072;
    private RegistrationFrame() { }

    static byte[] encode(CommandClicks.Registration registration) {
        byte[] token = registration.token().getBytes(StandardCharsets.UTF_8);
        byte[] command = registration.command().getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(7 + token.length + command.length).put((byte) 1)
                .putShort((short) token.length).put(token).putInt(command.length).put(command).array();
    }

    static CommandClicks.Registration decode(byte[] data) {
        if (data.length < 7 || data.length > MAX_BYTES) throw new IllegalArgumentException("Invalid registration size");
        ByteBuffer input = ByteBuffer.wrap(data);
        if (input.get() != 1) throw new IllegalArgumentException("Unknown registration version");
        int length = Short.toUnsignedInt(input.getShort());
        if (length != 46 || input.remaining() < length + 4) throw new IllegalArgumentException("Invalid registration token");
        byte[] token = new byte[length]; input.get(token);
        int commandLength = input.getInt();
        if (commandLength < 1 || commandLength != input.remaining()) throw new IllegalArgumentException("Invalid registration command size");
        byte[] command = new byte[commandLength]; input.get(command);
        String decoded = new String(command, StandardCharsets.UTF_8);
        if (!Arrays.equals(command, decoded.getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException("Malformed registration UTF-8");
        return new CommandClicks.Registration(new String(token, StandardCharsets.UTF_8), decoded);
    }
}
