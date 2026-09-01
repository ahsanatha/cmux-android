package dev.cmux.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class FrameCodecTest {
    @Test public void encodesBigEndianLengthPrefix() throws Exception {
        byte[] payload = "cmux".getBytes(StandardCharsets.UTF_8);
        byte[] frame = FrameCodec.encode(payload);
        assertEquals(payload.length, ByteBuffer.wrap(frame, 0, 4).getInt());
        assertArrayEquals(payload, java.util.Arrays.copyOfRange(frame, 4, frame.length));
    }
}
