package dev.leo.vaultpredictor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VaultCaptureTest {
    @Test
    void recordsEachNewEjectionOnceAndWaitsForTheEnd() {
        var capture = new VaultCapture(10, 20, 30);
        UUID oldDrop = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        assertFalse(capture.observe(oldDrop, 10.5, 21.075, 30.5));
        capture.updateVault(true);
        assertFalse(capture.observe(UUID.randomUUID(), 11, 21.075, 30.5));
        assertTrue(capture.observe(first, 10.5, 21.075, 30.5));
        capture.ejected();
        capture.record(first, "emerald x6");
        capture.record(first, "emerald x12");
        assertFalse(capture.complete());
        UUID second = UUID.randomUUID();
        assertTrue(capture.observe(second, 10.5, 21.075, 30.5));
        capture.ejected();
        capture.updateVault(false);
        assertFalse(capture.complete());
        capture.record(second, "heavy_core");
        assertTrue(capture.complete());
        assertEquals(List.of("emerald x6", "heavy_core"), capture.loot());
    }

    @Test
    void flagsMissingEjectionsAndRejectsAFollowingOpening() {
        var capture = new VaultCapture(0, 0, 0);
        capture.updateVault(true);
        capture.ejected();
        capture.updateVault(false);
        capture.updateVault(true);
        assertFalse(capture.observe(UUID.randomUUID(), 0.5, 1.075, 0.5));
        assertFalse(capture.complete());
    }
}
