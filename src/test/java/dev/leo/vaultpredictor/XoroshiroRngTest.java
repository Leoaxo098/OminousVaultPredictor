package dev.leo.vaultpredictor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class XoroshiroRngTest {

    @Test
    void sameStateProducesIdenticalSequence() {
        OminousVaultPredictor.Xoroshiro128PlusPlus a = new OminousVaultPredictor.Xoroshiro128PlusPlus(12345L, 67890L);
        OminousVaultPredictor.Xoroshiro128PlusPlus b = new OminousVaultPredictor.Xoroshiro128PlusPlus(12345L, 67890L);
        for (int i = 0; i < 1000; i++) {
            assertEquals(a.nextLong(), b.nextLong());
        }
    }

    @Test
    void nextIntStaysInBoundsForSmallBounds() {
        OminousVaultPredictor.Xoroshiro128PlusPlus rng = new OminousVaultPredictor.Xoroshiro128PlusPlus(42L, 1337L);
        for (int bound = 1; bound <= 64; bound++) {
            for (int i = 0; i < 2000; i++) {
                int v = rng.nextInt(bound);
                assertTrue(v >= 0 && v < bound, "bound=" + bound + " value=" + v);
            }
        }
    }

    @Test
    void nonPositiveBoundRejected() {
        assertThrows(IllegalArgumentException.class, () -> new OminousVaultPredictor.Xoroshiro128PlusPlus(1L, 2L).nextInt(0));
    }

    @Test
    void zeroStateFallsBackToGoldenConstants() {
        OminousVaultPredictor.Xoroshiro128PlusPlus rng = new OminousVaultPredictor.Xoroshiro128PlusPlus(0L, 0L);
        assertEquals(0x9E3779B97F4A7C15L, rng.lo);
        assertEquals(0x6A09E667F3BCC909L, rng.hi);
    }

    @Test
    void uniformCoverageOverTenBuckets() {
        OminousVaultPredictor.Xoroshiro128PlusPlus rng = new OminousVaultPredictor.Xoroshiro128PlusPlus(0xDEADBEEFL, 0xCAFEBABEL);
        int[] buckets = new int[10];
        for (int i = 0; i < 50000; i++) buckets[rng.nextInt(10)]++;
        for (int count : buckets) assertTrue(count > 4000 && count < 6000, "bucket=" + count);
    }

    @Test
    void nextFloatInRangeZeroToOne() {
        OminousVaultPredictor.Xoroshiro128PlusPlus rng = new OminousVaultPredictor.Xoroshiro128PlusPlus(7L, 11L);
        for (int i = 0; i < 10000; i++) {
            float f = rng.nextFloat();
            assertTrue(f >= 0.0f && f < 1.0f);
        }
    }
}
