package ua.nanit.limbo.connection.pipeline;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketDecoderTest {

    @Test
    void closesConnectionAndDropsPacketWhenDecodeFails() {
        PacketDecoder decoder = new PacketDecoder();
        EmbeddedChannel channel = new EmbeddedChannel(decoder);

        assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 0 })));

        assertFalse(channel.isActive());
        assertNull(channel.readInbound());
    }
}
