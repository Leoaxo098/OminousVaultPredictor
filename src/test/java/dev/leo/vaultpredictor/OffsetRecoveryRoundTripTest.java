package dev.leo.vaultpredictor;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OffsetRecoveryRoundTripTest {

    private static String runMain(String[] args) throws Exception {
        PrintStream old = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true));
        try {
            OminousVaultPredictor.main(args);
        } finally {
            System.setOut(old);
        }
        return buffer.toString();
    }

    /**
     * Synthetic ground truth: hidden offset of 3 opens on seed 42, three
     * observed vaults, then the tool must recover offset=3 and predict the
     * exact next vault. Also proves all four sequences stay in lockstep.
     */
    @Test
    void recoversTrueOffsetAndPredictsNextExactContents() throws Exception {
        OminousVaultPredictor.VaultRng truth = OminousVaultPredictor.VaultRng.fromSeed(42L);
        for (int i = 0; i < 3; i++) OminousVaultPredictor.simulateVault(truth);   // hidden history
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            OminousVaultPredictor.LootResult r = OminousVaultPredictor.simulateVault(truth);
            List<String> sorted = new ArrayList<>(r.items());
            Collections.sort(sorted);
            lines.add(String.join("; ", sorted));
        }
        OminousVaultPredictor.LootResult expectedNext = OminousVaultPredictor.simulateVault(truth);

        Path known = Files.createTempFile("known", ".txt");
        Files.write(known, lines);
        Path stFile = Files.createTempFile("state", ".props");

        String out;
        try {
            out = runMain(new String[]{"--seed", "42",
                    "--known-loot-file", known.toString(), "--save-state", stFile.toString()});
        } finally {
            Files.deleteIfExists(known);
        }

        assertTrue(out.contains("Recovered offset: 3"), out);
        String expectedLine = "Predicted NEXT vault (unopened): " + expectedNext.items();
        assertTrue(out.contains(expectedLine), out);
        assertTrue(out.contains("Heavy Core: " + (expectedNext.heavyCore() ? "YES" : "NO")), out);

        // Resume-from-saved-state continues the exact same stream: the vault
        // AFTER the predicted one must match a fresh truth simulation too.
        OminousVaultPredictor.LootResult expectedResume = OminousVaultPredictor.simulateVault(truth);
        String out2;
        try {
            out2 = runMain(new String[]{"--state-file", stFile.toString()});
        } finally {
            Files.deleteIfExists(stFile);
        }
        assertTrue(out2.contains("Full simulated items: " + String.join("; ", expectedResume.items())), out2);
    }
}
