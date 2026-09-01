package dev.cmux.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class BrowserAddressTest {
    @Test public void resolvesWebAddressesAndSearchesWithoutUnsafeSchemes() {
        assertEquals("https://cmux.com/docs", BrowserAddress.resolve("cmux.com/docs"));
        assertEquals("http://127.0.0.1:3000", BrowserAddress.resolve("http://127.0.0.1:3000"));
        assertEquals("https://www.google.com/search?q=cmux%20android",
            BrowserAddress.resolve("cmux android"));
        assertNull(BrowserAddress.resolve("javascript://alert(1)"));
        assertNull(BrowserAddress.resolve("file:///etc/passwd"));
    }
}
