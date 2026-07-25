package ua.nanit.limbo.connection.pipeline;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VarIntFrameDecoderTest {

    @Test
    void waitsUntilDeclaredPayloadLengthIsAvailable() {
        EmbeddedChannel channel = new EmbeddedChannel(new VarIntFrameDecoder(64));

        assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 3, 1, 2 })));
        assertNull(channel.readInbound());

        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 3 })));
        ByteBuf frame = channel.readInbound();
        assertNotNull(frame);
        try {
            assertEquals(4, frame.readableBytes());
            assertEquals(3, frame.readByte());
            assertEquals(1, frame.readByte());
            assertEquals(2, frame.readByte());
            assertEquals(3, frame.readByte());
        } finally {
            frame.release();
        }
    }

    @Test
    void closesConnectionWhenDeclaredPacketLengthExceedsLimit() {
        EmbeddedChannel channel = new EmbeddedChannel(new VarIntFrameDecoder(2));

        assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 3, 1, 2, 3 })));

        assertFalse(channel.isActive());
        assertNull(channel.readInbound());
    }
}
