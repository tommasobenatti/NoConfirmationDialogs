package io.github.tommasobenatti.ncd;

import io.netty.channel.ChannelHandlerContext;

interface PacketAdapter {
    Object rewrite(Object packet, CommandClicks clicks) throws ReflectiveOperationException;
    String clickId(Object packet) throws ReflectiveOperationException;
    void execute(ChannelHandlerContext context, String command) throws ReflectiveOperationException;
    default void release(Object packet) { io.netty.util.ReferenceCountUtil.release(packet); }
}
