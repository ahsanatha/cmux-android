package dev.cmux.android;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class MachineRegistryTest {
    @Test public void roundTripsIrohAndTailscaleMachines() {
        MachineRegistry.Machine iroh = MachineRegistry.Machine.iroh("a".repeat(64), "Office Mac");
        MachineRegistry.Machine tcp = MachineRegistry.Machine.tcp("mac-mini.tailnet.ts.net",
            58465, "Mac mini");

        List<MachineRegistry.Machine> decoded = MachineRegistry.decode(
            MachineRegistry.encode(List.of(iroh, tcp)));

        assertEquals(List.of(iroh, tcp), decoded);
    }

    @Test public void rejectsUnsafeRoutesAndMalformedEntries() {
        assertThrows(IllegalArgumentException.class,
            () -> MachineRegistry.Machine.tcp("localhost", 58465, "Mac"));
        assertThrows(IllegalArgumentException.class,
            () -> MachineRegistry.Machine.tcp("127.0.0.1", 58465, "Mac"));
        assertThrows(IllegalArgumentException.class,
            () -> MachineRegistry.Machine.tcp("mac mini", 58465, "Mac"));
        assertEquals(0, MachineRegistry.decode("[{\"kind\":\"tcp\",\"host\":\"localhost\"}]").size());
        assertEquals(0, MachineRegistry.decode("[{\"kind\":\"future\"}]").size());
    }

    @Test public void decodeSkipsBadEntryWithoutDiscardingGoodEntries() {
        MachineRegistry.Machine good = MachineRegistry.Machine.tcp("100.64.0.10", 58465, "Mac");
        String goodJson = MachineRegistry.encode(List.of(good));
        String encoded = "["
            + "{\"id\":\"bad\",\"kind\":\"tcp\",\"name\":\"bad\",\"host\":\"localhost\",\"port\":58465},"
            + goodJson.substring(1, goodJson.length() - 1)
            + "]";

        assertEquals(List.of(good), MachineRegistry.decode(encoded));
    }

    @Test public void encodeLimitsRegistrySize() {
        List<MachineRegistry.Machine> machines = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            machines.add(MachineRegistry.Machine.tcp("100.64.0." + (i + 1), 58000 + i, "Mac " + i));
        }

        assertEquals(32, MachineRegistry.decode(MachineRegistry.encode(machines)).size());
    }
}
