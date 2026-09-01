package dev.cmux.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.time.Instant
import org.json.JSONObject

class IrohWireConnectionTest {
    @Test fun `relay credentials reject expired or malformed lifetimes`() {
        val now = Instant.ofEpochSecond(1_000)
        val valid = JSONObject().put("relay_url", "https://relay.example.com")
            .put("token", "abc.def.ghi").put("expires_at", 1_300)
            .put("refresh_after", 1_240).put("ttl_seconds", 300)
        assertEquals("https://relay.example.com", parseRelayCredential(valid, now).first)
        assertThrows(IllegalArgumentException::class.java) {
            parseRelayCredential(JSONObject(valid.toString()).put("refresh_after", 999), now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseRelayCredential(JSONObject(valid.toString()).put("ttl_seconds", 10), now)
        }
    }

    @Test fun `registration relay credentials accept official legacy wire shape`() {
        val response = JSONObject().put("token", "abc234")
            .put("expires_at", "2026-07-11T00:00:00.000Z")
            .put("refresh_after", "2026-07-10T12:00:00.000Z")
            .put("relay_fleet", org.json.JSONArray()
                .put("https://one.relay.example.com/")
                .put("https://two.relay.example.com/"))
        assertEquals(2, legacyRelayCredentials(response,
            Instant.parse("2026-07-10T00:00:00Z")).size)
        assertThrows(IllegalArgumentException::class.java) {
            legacyRelayCredentials(response, Instant.parse("2026-07-10T13:00:00Z"))
        }
    }

    @Test fun relayUrlsRejectCredentialAndRedirectShapes() {
        assertTrue(canonicalRelayUrl("https://relay.example.com"))
        assertFalse(canonicalRelayUrl("http://relay.example.com"))
        assertFalse(canonicalRelayUrl("https://user@relay.example.com"))
        assertFalse(canonicalRelayUrl("https://relay.example.com?next=evil"))
    }

    @Test fun `control header matches cmux mobile v1 wire format`() {
        val frame = IrohWireConnection.controlHeader("grant")
        assertEquals("CMUXIRH1", String(frame.copyOfRange(0, 8)))
        assertEquals(1, frame[8].toInt())
        assertEquals(1, frame[9].toInt())
        assertEquals(1, frame[11].toInt())
        assertEquals(7, ByteBuffer.wrap(frame, 12, 4).int)
        assertEquals(5, ByteBuffer.wrap(frame, 16, 2).short.toInt())
        assertEquals("grant", String(frame.copyOfRange(18, 23)))
    }

    @Test fun `admission validates status and denial`() {
        assertEquals(0, IrohWireConnection.admission("CMXA\u0001\u0000\u0000\u0000".toByteArray()))
        assertEquals(3, IrohWireConnection.admission("CMXA\u0001\u0003\u0000\u0000".toByteArray()))
        assertThrows(IllegalStateException::class.java) {
            IrohWireConnection.admission("CMXA\u0001\u0001\u0000\u0007".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            IrohWireConnection.admission("BAD!\u0001\u0000\u0000\u0000".toByteArray())
        }
    }
}
