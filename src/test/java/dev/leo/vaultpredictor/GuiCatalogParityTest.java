package dev.leo.vaultpredictor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuiCatalogParityTest {

    /** Every item string the simulator can emit must be buildable by the GUI editor. */
    @Test
    void allSimulatorEmissionsRoundTripThroughTheEditor() {
        OminousVaultPredictor.VaultRng rng = OminousVaultPredictor.VaultRng.fromSeed(31337L);
        int checked = 0;
        for (int i = 0; i < 20000; i++) {
            OminousVaultPredictor.LootResult r = OminousVaultPredictor.simulateVault(rng);
            for (String item : r.items()) {
                checked++;
                var entry = VaultLoot.parse(item).orElseThrow(() -> new AssertionError("Cannot edit " + item));
                assertEquals(item, entry.token());
            }
        }
        assertTrue(checked > 30000, "checked=" + checked);
    }

    @Test
    void recognizesSearchTermsAndFormatsNewBottleStacks() {
        assertTrue(VaultLoot.Item.MACE_BOOK.matches("book density"));
        assertFalse(VaultLoot.Item.COMBAT_BOOK.matches("book density"));
        assertTrue(VaultLoot.Item.DIAMOND_CHESTPLATE.matches("CHEST Mending"));
        assertEquals("density", VaultLoot.Item.MACE_BOOK.searchedEnchantment("book dens").orElseThrow().name());
        assertTrue(VaultLoot.Item.MACE_BOOK.searchedEnchantment("mace book").isEmpty());
        var bottles = VaultLoot.parse("ominous_bottle V x2").orElseThrow();
        assertEquals("Ominous bottle V ×2", bottles.label());
        assertEquals("ominous_bottle V x2", bottles.token());
    }
}
