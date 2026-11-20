package io.github.tommasobenatti.ncd;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;

final class ClickWire {
    private static final ByteBuf RUN_COMMAND = Unpooled.wrappedBuffer("run_command".getBytes(StandardCharsets.US_ASCII)).asReadOnly();

    private ClickWire() { }

    static boolean hasCommand(ByteBuf buffer) {
        // The action name is ASCII in both NBT and JSON.
        return ByteBufUtil.indexOf(RUN_COMMAND, buffer) >= 0;
    }

    static int readVarInt(ByteBuf buffer) {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (!buffer.isReadable()) throw new MalformedClickException("Truncated VarInt");
            int part = buffer.readUnsignedByte();
            if (shift == 28 && (part & 0xf0) != 0) throw new MalformedClickException("Overflowing VarInt");
            result |= (part & 127) << shift;
            if ((part & 128) == 0) {
                if (shift > 0 && part == 0) throw new MalformedClickException("Noncanonical VarInt");
                return result;
            }
        }
        throw new MalformedClickException("Invalid VarInt");
    }

    static String readClickId(ByteBuf buffer) {
        int size = readVarInt(buffer);
        if (size < 1 || size > 32767 || size > buffer.readableBytes()) {
            throw new MalformedClickException("Invalid click identifier length");
        }
        int start = buffer.readerIndex();
        if (size < 4 || buffer.getByte(start) != 'n' || buffer.getByte(start + 1) != 'c'
                || buffer.getByte(start + 2) != 'd' || buffer.getByte(start + 3) != ':') return null;
        if (size > 128) throw new MalformedClickException("Oversized NCD identifier");
        for (int i = start; i < start + size; i++) {
            if (buffer.getByte(i) < 0) throw new MalformedClickException("Invalid NCD identifier");
        }
        buffer.skipBytes(size);
        int payload = readVarInt(buffer);
        if (payload < 1 || payload > 65536 || payload != buffer.readableBytes()) {
            throw new MalformedClickException("Invalid click payload size");
        }
        return buffer.toString(start, size, StandardCharsets.US_ASCII);
    }
}
