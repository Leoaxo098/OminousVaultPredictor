package dev.leo.vaultpredictor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VaultSimulationTest {

    @Test
    void initialStateDeterministicPerSeed() {
        assertEquals(OminousVaultPredictor.initialState(987654321L), OminousVaultPredictor.initialState(987654321L));
    }

    @Test
    void distinctSeedsGiveDistinctStates() {
        assertNotEquals(OminousVaultPredictor.initialState(1L), OminousVaultPredictor.initialState(2L));
    }

    @Test
    void heavyCoreRateNearSevenPointFivePercent() {
        // Verified math: 0.75 gate * 1/10 weighted unique share = 7.5%.
        OminousVaultPredictor.VaultRng rng = OminousVaultPredictor.VaultRng.fromSeed(20260827L);
        int vaults = 40000;
        int hits = 0;
        for (int i = 0; i < vaults; i++) {
            if (OminousVaultPredictor.simulateVault(rng).heavyCore()) hits++;
        }
        double rate = (double) hits / vaults;
        assertTrue(rate > 0.065 && rate < 0.085, "rate=" + rate);
    }
}
