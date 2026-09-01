package dev.cmux.android;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PairingLinkTest {
    @Test public void parsesCurrentTailscaleGrammar() throws Exception {
        var routes = PairingLink.parse("cmux-ios://attach?v=2&r=mac.tail.ts.net:58465&r=100.64.0.5:58466");
        assertEquals(new PairingLink.Route("mac.tail.ts.net", 58465), routes.get(0));
        assertEquals(new PairingLink.Route("100.64.0.5", 58466), routes.get(1));
    }

    @Test public void parsesCompactV1Ticket() throws Exception {
        String json = "{\"v\":1,\"r\":[{\"e\":{\"h\":\"100.64.0.7\",\"p\":58465}}]}";
        String payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(new PairingLink.Route("100.64.0.7", 58465),
            PairingLink.parse("cmux-ios://attach?v=1&payload=" + payload).get(0));
    }

    @Test public void rejectsLoopbackAndIroh() {
        assertThrows(IllegalArgumentException.class,
            () -> PairingLink.parse("cmux-ios://attach?v=2&r=127.0.0.1:58465"));
        assertThrows(IllegalArgumentException.class,
            () -> PairingLink.parse("cmux-ios://attach?v=3&i=cccc"));
    }

    @Test public void parsesExactIrohGrammar() {
        String id = "c".repeat(64);
        assertEquals(id, PairingLink.parseIrohEndpointId("cmux-ios://attach?v=3&i=" + id));
        assertThrows(IllegalArgumentException.class,
            () -> PairingLink.parseIrohEndpointId("cmux-ios://attach?v=3&i=" + id + "&x=1"));
        assertThrows(IllegalArgumentException.class,
            () -> PairingLink.parseIrohEndpointId("cmux-ios-dev://attach?v=3&i=" + id));
    }
}
