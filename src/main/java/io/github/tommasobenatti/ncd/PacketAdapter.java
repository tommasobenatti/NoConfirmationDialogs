package io.github.tommasobenatti.ncd;

import io.netty.channel.ChannelHandlerContext;

interface PacketAdapter {
    Object rewrite(Object packet, CommandClicks clicks) throws ReflectiveOperationException;
    String clickId(Object packet) throws ReflectiveOperationException;
    void execute(ChannelHandlerContext context, String command) throws ReflectiveOperationException;
    default boolean control(Object packet, CommandClicks clicks) throws ReflectiveOperationException { return false; }
    default void publish(ChannelHandlerContext context, CommandClicks clicks) throws ReflectiveOperationException { }
    default int requestBytes(Object packet) throws ReflectiveOperationException {
        return packet instanceof io.netty.buffer.ByteBuf buffer ? buffer.readableBytes() : 0;
    }
    default boolean account(ChannelHandlerContext context, Object packet) throws ReflectiveOperationException { return true; }
    default boolean account(ChannelHandlerContext context, Object packet, boolean commandWillRun) throws ReflectiveOperationException {
        return account(context, packet);
    }
    default void release(Object packet) { io.netty.util.ReferenceCountUtil.release(packet); }
}
