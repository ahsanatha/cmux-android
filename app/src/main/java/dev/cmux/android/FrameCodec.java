package dev.cmux.android;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

final class FrameCodec {
    static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;

    private FrameCodec() {}

    static byte[] encode(byte[] payload) throws IOException {
        if (payload.length > MAX_FRAME_BYTES) throw new IOException("Frame too large");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + 4);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(payload.length);
            output.write(payload);
        }
        return bytes.toByteArray();
    }

    static void validateLength(int length) throws IOException {
        if (length < 0 || length > MAX_FRAME_BYTES) throw new IOException("Invalid frame length");
    }
}
