package dev.leo.vaultpredictor;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

class GuiCatalogParityTest {

    /** Every item string the simulator can emit must be buildable by the GUI editor. */
    @Test
    void allSimulatorEmissionsMatchCanonicalEditorPatterns() {
        List<Pattern> patterns = VaultPredictorGui.canonicalPatterns();
        OminousVaultPredictor.VaultRng rng = OminousVaultPredictor.VaultRng.fromSeed(31337L);
        int checked = 0;
        for (int i = 0; i < 20000; i++) {
            OminousVaultPredictor.LootResult r = OminousVaultPredictor.simulateVault(rng);
            for (String item : r.items()) {
                checked++;
                boolean ok = false;
                for (Pattern p : patterns) {
                    if (p.matcher(item).matches()) { ok = true; break; }
                }
                assertTrue(ok, "simulator emitted non-canonical string: " + item);
            }
        }
        assertTrue(checked > 30000, "checked=" + checked);
    }
}
