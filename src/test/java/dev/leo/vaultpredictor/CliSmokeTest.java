package dev.leo.vaultpredictor;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliSmokeTest {

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

    @Test
    void helpPrintsUsage() throws Exception {
        String out = runMain(new String[]{"-h"});
        assertTrue(out.contains("Usage:"));
    }

    @Test
    void singleVaultPredictionPrintsLootAndState() throws Exception {
        String out = runMain(new String[]{"--seed", "12345"});
        assertTrue(out.contains("Minecraft 1.21.11 Ominous Vault Predictor"));
        assertTrue(out.contains("Heavy Core: YES") || out.contains("Heavy Core: NO"));
        assertTrue(out.contains("lo=0x"));
    }

    @Test
    void vaultFileScanListsEveryLine() throws Exception {
        Path file = Files.createTempFile("vaults", ".txt");
        Files.writeString(file, "# comment\r\n100 20 -50\r\n-12 33 271\r\n0 0 0");
        String out = runMain(new String[]{"--seed", "987654321", "--vault-file", file.toString()});
        assertTrue(out.contains("1. 100 20 -50"), out);
        assertTrue(out.contains("3. 0 0 0"), out);
        assertTrue(out.contains("Heavy Core candidates:"), out);
        Files.deleteIfExists(file);
    }

    @Test
    void missingSeedAndStateThrows() {
        assertThrows(IllegalArgumentException.class, () -> OminousVaultPredictor.main(new String[0]));
    }

    @Test
    void unsupportedTargetThrows() {
        assertThrows(IllegalArgumentException.class, () -> OminousVaultPredictor.main(new String[]{"--seed", "1", "--target", "minecraft:diamond"}));
    }
}
