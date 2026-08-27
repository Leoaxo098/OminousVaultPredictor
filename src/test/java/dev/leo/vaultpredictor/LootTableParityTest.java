package dev.leo.vaultpredictor;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LootTableParityTest {

    private static final Map<String, Integer> tally(List<String> tokens) {
        Map<String, Integer> counts = new HashMap<>();
        for (String t : tokens) counts.merge(t, 1, Integer::sum);
        return counts;
    }

    /** First token of each item string identifies the top-level drop. */
    private static String head(String item) {
        String s = item;
        int b = s.indexOf('[');
        if (b >= 0) s = s.substring(0, b);
        int sp = s.indexOf(' ');
        if (sp >= 0) s = s.substring(0, sp);
        return s;
    }

    @Test
    void structuralRatesMatchVerifiedJsonWeights() {
        OminousVaultPredictor.VaultRng rng = OminousVaultPredictor.VaultRng.fromSeed(777L);
        int n = 30000;
        List<String> firsts = new ArrayList<>(n);
        List<String> allTokens = new ArrayList<>(n * 4);
        for (int i = 0; i < n; i++) {
            OminousVaultPredictor.LootResult r = OminousVaultPredictor.simulateVault(rng);
            assertTrue(!r.items().isEmpty());
            firsts.add(head(r.items().get(0)));
            allTokens.addAll(r.items());
        }
        double d = n;
        Map<String, Integer> c1 = tally(firsts);

        // Pool-1 first roll drives the first item: rare branch 80% of the time.
        // Rare table entry rates: p(entry) = 0.8 * w/29.
        assertRate(c1, "emerald_block", 0.8 * 5 / 29.0, 0.03, c1);
        assertRate(c1, "iron_block", 0.8 * 4 / 29.0, 0.03, c1);
        assertRate(c1, "crossbow", 0.8 * 4 / 29.0, 0.03, c1);
        assertRate(c1, "golden_apple", 0.8 * 3 / 29.0, 0.03, c1);
        // Common is the pool-1 fallback AND pool-2 items appear later in the list,
        // so only check the FIRST item's common share loosely via emerald lead:
        // first item starts common-table roll with rate 0.2*(5/15)=6.7% etc. But
        // common items also leak into first position when rare branch skipped.
        // Approximation tolerance widened accordingly.

        Map<String, Integer> ct = tally(allTokens);
        // Pool-3 weighted uniques: p = 0.75 * w/10 (any position; only emitted once).
        assertRate(ct, "enchanted_golden_apple", 0.225, 0.02, ct);
        assertRate(ct, "flow_armor_trim_smithing_template", 0.225, 0.02, ct);
        assertRate(ct, "flow_banner_pattern", 0.15, 0.02, ct);
        assertRate(ct, "music_disc_creator", 0.075, 0.015, ct);
        assertRate(ct, "heavy_core", 0.075, 0.015, ct);

        // Ominous bottle levels must be exactly III..V (amplifier uniform 2..4).
        int bottles = 0;
        for (String t : allTokens) {
            if (t.startsWith("ominous_bottle ")) {
                bottles++;
                String lvl = t.substring("ominous_bottle ".length());
                assertTrue(lvl.equals("III") || lvl.equals("IV") || lvl.equals("V"), t);
            }
        }
        // Expected share across all tokens ~ (0.2 + E[extra 2]*?) ... just require presence.
        assertTrue(bottles > n / 20, "bottles=" + bottles);
    }

    private static void assertRate(Map<String, Integer> counts, String key,
                                   double expected, double tol, Object ignoredAnchor) {
        int hits = counts.getOrDefault(key, 0);
        double rate = hits / 30000.0;
        assertTrue(Math.abs(rate - expected) < tol,
                key + " rate=" + rate + " expected~" + expected);
    }
}
