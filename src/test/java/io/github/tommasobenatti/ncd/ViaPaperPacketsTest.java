package io.github.tommasobenatti.ncd;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.ClientboundPacketType;
import com.viaversion.viaversion.api.protocol.packet.Direction;
import com.viaversion.viaversion.api.protocol.packet.ServerboundPacketType;
import com.viaversion.viaversion.api.protocol.packet.provider.PacketTypesProvider;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ViaPaperPacketsTest {
    @Test void chargesTheOriginalClickEvenWhenItResolvesACommand() throws Exception {
        var charged = new AtomicInteger();
        var user = (UserConnection) Proxy.newProxyInstance(UserConnection.class.getClassLoader(),
                new Class<?>[]{UserConnection.class}, (proxy, method, args) -> {
                    assertEquals("checkServerboundPacket", method.getName());
                    charged.addAndGet((int) args[0]);
                    return false;
                });
        var constructor = ViaPaperPackets.class.getDeclaredConstructor(UserConnection.class, PacketTypesProvider.class);
        constructor.setAccessible(true);
        var adapter = constructor.newInstance(user, packetTypes());
        var raw = Unpooled.buffer().writeZero(321);
        try {
            assertFalse(adapter.account(null, raw, true));
            assertEquals(321, charged.get());
        } finally {
            raw.release();
        }
    }

    static PacketTypesProvider<?, ?, ?, ?> packetTypes() {
        return (PacketTypesProvider<?, ?, ?, ?>) Proxy.newProxyInstance(PacketTypesProvider.class.getClassLoader(),
                new Class<?>[]{PacketTypesProvider.class}, (proxy, method, args) -> Type.valueOf((String) args[1]));
    }

    enum Type implements ClientboundPacketType, ServerboundPacketType {
        SYSTEM_CHAT, DISGUISED_CHAT, PLAYER_CHAT, CUSTOM_PAYLOAD, CUSTOM_CLICK_ACTION, CHAT_COMMAND;

        @Override public int getId() { return ordinal(); }
        @Override public String getName() { return name(); }
        @Override public Direction direction() {
            return ordinal() < CUSTOM_CLICK_ACTION.ordinal() ? Direction.CLIENTBOUND : Direction.SERVERBOUND;
        }
    }
}
