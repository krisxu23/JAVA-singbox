package ua.nanit.limbo.connection.pipeline;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.jetbrains.annotations.NotNull;
import ua.nanit.limbo.server.Log;

import java.util.Arrays;

public class ChannelTrafficHandler extends ChannelInboundHandlerAdapter {

    private static final int DEFAULT_BUCKET_COUNT = 150;

    private final int maxPacketSize;
    private final double maxPacketRate;
    private final PacketBucket packetBucket;

    public ChannelTrafficHandler(int maxPacketSize, double interval, double maxPacketRate) {
        this.maxPacketSize = maxPacketSize;
        this.maxPacketRate = maxPacketRate;
        this.packetBucket = (interval > 0.0 && maxPacketRate > 0.0)
                ? new PacketBucket(interval * 1000.0, DEFAULT_BUCKET_COUNT)
                : null;
    }

    @Override
    public void channelRead(@NotNull ChannelHandlerContext ctx, @NotNull Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf in = (ByteBuf) msg;
            int bytes = in.readableBytes();

            if (maxPacketSize > 0 && bytes > maxPacketSize) {
                closeConnection(ctx, msg, "Closed %s due to large packet size (%d bytes)", ctx.channel().remoteAddress(), bytes);
                return;
            }

            if (packetBucket != null) {
                packetBucket.incrementPackets(1);
                if (packetBucket.getCurrentPacketRate() > maxPacketRate) {
                    closeConnection(ctx, msg, "Closed %s due to many packets sent (%d in the last %.1f seconds)",
                            ctx.channel().remoteAddress(), packetBucket.sum, (packetBucket.intervalTime / 1000.0));
                    return;
                }
            }
        }

        super.channelRead(ctx, msg);
    }

    private void closeConnection(ChannelHandlerContext ctx, Object msg, String reason, Object... args) {
        ReferenceCountUtil.release(msg);
        ctx.close();
        Log.info(reason, args);
    }

    static class PacketBucket {
        private final double intervalTime;
        private final double intervalResolution;
        private final int[] data;
        private int newestData;
        private double lastBucketTime;
        private int sum;

        PacketBucket(final double intervalTime, final int totalBuckets) {
            this.intervalTime = intervalTime;
            this.intervalResolution = intervalTime / totalBuckets;
            this.data = new int[totalBuckets];
            this.lastBucketTime = System.nanoTime() / 1_000_000.0;
        }

        void incrementPackets(final int packets) {
            double timeMs = System.nanoTime() / 1_000_000.0;
            double timeDelta = timeMs - this.lastBucketTime;

            if (timeDelta < 0.0) timeDelta = 0.0;

            if (timeDelta < this.intervalResolution) {
                this.data[this.newestData] += packets;
                this.sum += packets;
                return;
            }

            int bucketsToAdvance = (int) (timeDelta / this.intervalResolution);

            if (bucketsToAdvance >= this.data.length) {
                Arrays.fill(this.data, 0);
                this.data[0] = packets;
                this.sum = packets;
                this.newestData = 0;
                this.lastBucketTime = timeMs;
                return;
            }

            // Clear all buckets from current position up to the new position
            for (int i = 0; i < bucketsToAdvance; i++) {
                int index = (this.newestData + i) % this.data.length;
                this.sum -= this.data[index];
                this.data[index] = 0;
            }

            int newestDataIndex = (this.newestData + bucketsToAdvance) % this.data.length;
            this.sum += packets - this.data[newestDataIndex];
            this.data[newestDataIndex] = packets;
            this.newestData = newestDataIndex;
            this.lastBucketTime += bucketsToAdvance * this.intervalResolution;
        }

        double getCurrentPacketRate() {
            return this.sum / (this.intervalTime / 1000.0);
        }
    }
}
