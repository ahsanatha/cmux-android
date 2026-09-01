package dev.cmux.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.Test;

public final class StackAuthClientTest {
    @Test public void fingerprintsJwtSubjectAndRejectsMissingIdentity() throws Exception {
        String claims = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"sub\":\"user-123\"}".getBytes(StandardCharsets.UTF_8));
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest("user-123".getBytes(StandardCharsets.UTF_8));
        StringBuilder expected = new StringBuilder();
        for (byte value : digest) expected.append(String.format("%02x", value & 0xff));
        assertEquals(expected.toString(), StackAuthClient.accountFingerprint("x." + claims + ".y"));

        String missing = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,
            () -> StackAuthClient.accountFingerprint("x." + missing + ".y"));
    }
}
