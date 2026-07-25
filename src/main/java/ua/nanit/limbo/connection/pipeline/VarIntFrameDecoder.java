/*
 * Copyright (C) 2020 Nan1t
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ua.nanit.limbo.connection.pipeline;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import ua.nanit.limbo.server.Log;

import java.util.List;

public class VarIntFrameDecoder extends ByteToMessageDecoder {

    private final int maxPacketSize;

    public VarIntFrameDecoder() {
        this(-1);
    }

    public VarIntFrameDecoder(int maxPacketSize) {
        this.maxPacketSize = maxPacketSize;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!ctx.channel().isActive()) {
            in.clear();
            return;
        }

        VarIntByteDecoder reader = new VarIntByteDecoder();
        int varIntEnd = in.forEachByte(reader);

        // Handle TOO_BIG regardless of whether forEachByte stopped by exhaustion or by early return
        if (reader.getResult() == VarIntByteDecoder.DecodeResult.TOO_BIG) {
            Log.warning("VarInt too large from %s, closing connection", ctx.channel().remoteAddress());
            ctx.close();
            in.clear();
            return;
        }

        if (varIntEnd == -1) {
            // Not enough data yet (process() ran through all bytes without returning false)
            return;
        }

        if (reader.getResult() == VarIntByteDecoder.DecodeResult.SUCCESS) {
            int readVarInt = reader.getReadVarInt();
            int bytesRead = reader.getBytesRead();
            if (readVarInt < 0) {
                Log.error("[VarIntFrameDecoder] Read VarInt is negative, skipping...");
                in.skipBytes(bytesRead);
                return;
            }

            int packetLength = readVarInt;
            int packetLengthBytes = bytesRead;
            int available = in.readableBytes();

            if (maxPacketSize > 0 && packetLength > maxPacketSize) {
                Log.warning("Packet length %d exceeds limit %d from %s, closing connection",
                        packetLength, maxPacketSize, ctx.channel().remoteAddress());
                ctx.close();
                in.clear();
                return;
            }

            if (available < packetLengthBytes + packetLength) {
                return;
            }

            out.add(in.retainedSlice(in.readerIndex(), packetLengthBytes + packetLength));
            in.skipBytes(packetLengthBytes + packetLength);
        }
    }
}
