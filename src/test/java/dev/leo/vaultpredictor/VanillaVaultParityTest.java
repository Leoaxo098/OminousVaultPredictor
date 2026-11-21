package dev.leo.vaultpredictor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VanillaVaultParityTest {
    @Test
    void matchesKeyOpenedVaultsFromVanilla12111() {
        // Captured from successive ominous trial key insertions on a vanilla
        // 1.21.11 server with world seed 123456789.
        List<List<String>> expected = List.of(
                List.of("emerald_block", "wind_charge x11", "flow_armor_trim_smithing_template"),
                List.of("book[wind_burst]", "diamond x2", "heavy_core"),
                List.of("book[knockback 2]", "tipped_arrow_slowness x6", "emerald x6"),
                List.of("diamond_chestplate[protection 1]", "emerald x4", "diamond x3", "emerald x4", "flow_banner_pattern"),
                List.of("crossbow[piercing 2]", "tipped_arrow_slowness x11", "wind_charge x12", "emerald x6", "enchanted_golden_apple"),
                List.of("diamond_chestplate[blast_protection 3]", "emerald x10", "flow_armor_trim_smithing_template"),
                List.of("emerald x6", "tipped_arrow_slowness x4", "emerald x9"),
                List.of("wind_charge x10", "wind_charge x11", "diamond x2", "enchanted_golden_apple"),
                List.of("emerald x9", "wind_charge x8", "wind_charge x10", "tipped_arrow_slowness x7"),
                List.of("iron_block", "wind_charge x12", "wind_charge x9", "enchanted_golden_apple"),
                List.of("crossbow[unbreaking 2,quick_charge 1]", "emerald x4", "emerald x7", "wind_charge x11"),
                List.of("golden_apple", "emerald x8", "emerald x4", "wind_charge x10", "enchanted_golden_apple"));
        var rng = OminousVaultPredictor.VaultRng.fromSeed(123456789L);
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), OminousVaultPredictor.simulateVault(rng).items(), "Opening " + (i + 1));
        }
    }

    @Test
    void recoversVanillaHistoryWithMergedStacksAndReorderedEnchantments() {
        var rng = OminousVaultPredictor.VaultRng.fromSeed(123456789L);
        var history = List.of(
                List.of("iron_block", "wind_charge x21", "enchanted_golden_apple"),
                List.of("crossbow[quick_charge 1, unbreaking 2]", "emerald x11", "wind_charge x11"),
                List.of("golden_apple", "emerald x12", "wind_charge x10", "enchanted_golden_apple"));
        assertEquals(9, OminousVaultPredictor.recoverOffset(rng, history, 100_000));
    }

    @Test
    void refusesToGuessWhenSeveralOffsetsFitTheObservedLoot() {
        var rng = OminousVaultPredictor.VaultRng.fromSeed(123456789L);
        var history = List.of(List.of("wind_charge x20"));
        assertEquals(OminousVaultPredictor.AMBIGUOUS_OFFSET,
                OminousVaultPredictor.recoverOffset(rng, history, 100_000));
    }

    @Test
    void recoversFromStackedBottlesOfTheSameLevel() {
        var rng = OminousVaultPredictor.VaultRng.fromSeed(966L);
        assertEquals(0, OminousVaultPredictor.recoverOffset(rng,
                List.of(List.of("emerald x5", "ominous_bottle V x2")), 1));
        assertEquals(OminousVaultPredictor.NO_OFFSET, OminousVaultPredictor.recoverOffset(rng,
                List.of(List.of("emerald x5", "ominous_bottle IV x2")), 1));
    }
}
