package ua.nanit.limbo.connection.pipeline;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChannelTrafficHandlerTest {

    @Test
    void packetBucketSinglePacket() {
        ChannelTrafficHandler handler = new ChannelTrafficHandler(8192, 7.0, 500.0);
        assertNotNull(handler);
    }

    @Test
    void packetBucketDisabled() {
        ChannelTrafficHandler handler = new ChannelTrafficHandler(8192, -1.0, -1.0);
        assertNotNull(handler);
    }

    @Test
    void bucketRateWithinBounds() throws Exception {
        ChannelTrafficHandler.PacketBucket bucket = new ChannelTrafficHandler.PacketBucket(7000.0, 150);
        bucket.incrementPackets(1);
        assertTrue(bucket.getCurrentPacketRate() >= 0);
    }

    @Test
    void releasesOversizedPacketWhenClosingConnection() {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelTrafficHandler(2, -1.0, -1.0));
        ByteBuf oversized = Unpooled.buffer(3).writeZero(3);

        assertFalse(channel.writeInbound(oversized));

        assertEquals(0, oversized.refCnt());
        assertFalse(channel.isActive());
        channel.finishAndReleaseAll();
    }
}
