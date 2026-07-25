package ua.nanit.limbo.connection.pipeline;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VarIntByteDecoderTest {

    @Test
    void decodeSingleByte() {
        VarIntByteDecoder reader = new VarIntByteDecoder();
        assertFalse(reader.process((byte) 0x01)); // continuation bit not set -> SUCCESS
        assertEquals(VarIntByteDecoder.DecodeResult.SUCCESS, reader.getResult());
        assertEquals(1, reader.getReadVarInt());
    }

    @Test
    void decodeMultiByte() {
        // VarInt 300 = 0xAC 0x02
        VarIntByteDecoder reader = new VarIntByteDecoder();
        assertTrue(reader.process((byte) 0xAC));
        assertFalse(reader.process((byte) 0x02));
        assertEquals(VarIntByteDecoder.DecodeResult.SUCCESS, reader.getResult());
        assertEquals(300, reader.getReadVarInt());
    }

    @Test
    void decodeMaxValue() {
        // Max 5-byte VarInt: 0xFF 0xFF 0xFF 0xFF 0x07 = Integer.MAX_VALUE
        VarIntByteDecoder reader = new VarIntByteDecoder();
        assertTrue(reader.process((byte) 0xFF));
        assertTrue(reader.process((byte) 0xFF));
        assertTrue(reader.process((byte) 0xFF));
        assertTrue(reader.process((byte) 0xFF));
        assertFalse(reader.process((byte) 0x07));
        assertEquals(VarIntByteDecoder.DecodeResult.SUCCESS, reader.getResult());
        assertEquals(Integer.MAX_VALUE, reader.getReadVarInt());
    }

    @Test
    void rejectTooBig() {
        // 6 bytes with continuation bit should be rejected
        VarIntByteDecoder reader = new VarIntByteDecoder();
        assertTrue(reader.process((byte) 0xFF));
        assertTrue(reader.process((byte) 0xFF));
        assertTrue(reader.process((byte) 0xFF));
        assertTrue(reader.process((byte) 0xFF));
        assertTrue(reader.process((byte) 0xFF)); // 5th byte with continuation bit
        assertFalse(reader.process((byte) 0x7F)); // 6th byte -> TOO_BIG
        assertEquals(VarIntByteDecoder.DecodeResult.TOO_BIG, reader.getResult());
    }

    @Test
    void decodeZero() {
        VarIntByteDecoder reader = new VarIntByteDecoder();
        assertFalse(reader.process((byte) 0x00));
        assertEquals(0, reader.getReadVarInt());
        assertEquals(VarIntByteDecoder.DecodeResult.SUCCESS, reader.getResult());
    }

    @Test
    void decodeNegativeOne() {
        // VarInt -1 = 0xFF 0xFF 0xFF 0xFF 0x0F
        VarIntByteDecoder reader = new VarIntByteDecoder();
        assertTrue(reader.process((byte) 0xFF));
        assertTrue(reader.process((byte) 0xFF));
        assertTrue(reader.process((byte) 0xFF));
        assertTrue(reader.process((byte) 0xFF));
        assertFalse(reader.process((byte) 0x0F));
        assertEquals(-1, reader.getReadVarInt());
    }
}
