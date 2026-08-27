package dev.leo.vaultpredictor;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Standalone Minecraft Java 1.21.11 ominous-vault predictor.
 *
 * Loot model transcribed 1:1 from the vanilla 1.21.11 data-pack tables
 * (primary source: misode/mcmeta tag 1.21.11-data, Mojang data-generator
 * output; single-source verification - NOT independently diffed against the
 * Mojang jar itself):
 *
 *   chests/trial_chambers/reward_ominous
 *     Pool 1: rolls=1               -> rare(w8) / common(w2) nested refs
 *     Pool 2: rolls=uniform(1..3)   -> common
 *     Pool 3: rolls=1, random_chance(0.75) gate -> unique (weights 3,3,2,1,1)
 *   reward_ominous_rare    total weight 29
 *   reward_ominous_common  total weight 15
 *   reward_ominous_unique  total weight 10 (heavy_core weight 1)
 *
 * RNG model correction: EACH loot table declares its OWN random_sequence, so
 * FOUR streams exist (root/_rare/_common/_unique), each derived from the
 * world seed via lo = seed ^ H_LO, hi = lo + GOLDEN, XOR with MD5(path)
 * halves, Stafford13-mixed. One open advances several streams; because the
 * per-open consumption is deterministic, a scalar "offset" (#prior opens)
 * still replays all four streams in lockstep during recovery.
 *
 * IMPORTANT:
 * - Coordinates are labels/order only; they are NOT used to seed the RNG.
 * - Offsets/predictions are only meaningful when NOTHING ELSE consumed these
 *   sequences between your observations (singleplayer / solo on the server).
 * - Engine-level RandomSequences defaults (salt/include flags) are not
 *   observable from data files; live-world testing remains the final check.
 */
public final class OminousVaultPredictor {

    static final String SEQ_ROOT =
            "minecraft:chests/trial_chambers/reward_ominous";
    static final String SEQ_RARE = SEQ_ROOT + "_rare";
    static final String SEQ_COMMON = SEQ_ROOT + "_common";
    static final String SEQ_UNIQUE = SEQ_ROOT + "_unique";

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);
        if (a.help) {
            Args.usage();
            return;
        }
        boolean hasOverride = a.stateLo != null && a.stateHi != null;
        if (a.stateLo != null || a.stateHi != null) {
            if (!hasOverride) {
                throw new IllegalArgumentException("--state-lo and --state-hi must be provided together");
            }
        }
        if (a.seed == null && a.stateFile == null) {
            throw new IllegalArgumentException("Provide --seed, or --state-file");
        }
        if (a.seed != null && a.stateFile != null) {
            throw new IllegalArgumentException("Pass either --seed or --state-file, not both");
        }

        VaultRng rng = (a.stateFile != null) ? VaultRng.load(a.stateFile)
                                             : VaultRng.fromSeed(a.seed);
        if (hasOverride) {
            // Debug hook: forces ONLY the root stream; the other three stay
            // seed-derived. Prefer --state-file for a fully coherent resume.
            rng.root = new Xoroshiro128PlusPlus(a.stateLo, a.stateHi);
        }

        if (a.knownLootFile != null) {
            List<List<String>> known = readKnownLootFile(a.knownLootFile);
            System.out.println("Recovering sequence offsets from " + known.size() + " known vault results...");
            int offset = recoverOffset(rng, known, a.maxSearch > 0 ? (int) a.maxSearch : 100_000);
            if (offset < 0) {
                System.out.println("No consistent offset found within search range " + a.maxSearch +
                        ". Either the opening order is wrong, something else consumed the");
                System.out.println("sequences in between, or the model diverges from your runtime.");
                return;
            }
            System.out.println("Recovered offset: " + offset + " prior vault opens.");
            for (int i = 0; i < offset + known.size(); i++) simulateVault(rng);
            System.out.println("State after known history:");
            System.out.print(rng.dumpState());
            LootResult next = simulateVault(rng);
            System.out.println("Predicted NEXT vault (unopened): " + next.items());
            System.out.println("Heavy Core: " + (next.heavyCore() ? "YES" : "NO"));
            if (a.saveState != null) VaultRng.save(a.saveState, rng);
            return;
        }

        if (a.skipVaults > 0) {
            for (long i = 0; i < a.skipVaults; i++) simulateVault(rng);
        }

        if (a.vaultFile != null) {
            List<Vault> vaults = readVaultFile(a.vaultFile);
            System.out.println("Minecraft 1.21.11 Ominous Vault scan");
            System.out.println("World seed: " + a.seed);
            System.out.println("Starting RNG state:");
            System.out.print(rng.dumpState());
            System.out.println("Assumed opening order: vault-file line order");
            System.out.println();

            int hits = 0;
            for (int i = 0; i < vaults.size(); i++) {
                Vault v = vaults.get(i);
                LootResult r = simulateVault(rng);
                System.out.printf("%d. %d %d %d -> %s | items: %s%n", i + 1, v.x, v.y, v.z,
                        r.heavyCore() ? "HEAVY CORE" : "no Heavy Core", String.join("; ", r.items()));
                if (r.heavyCore()) hits++;
            }
            System.out.println();
            System.out.println("Heavy Core candidates: " + hits);
            System.out.println("Final RNG state:");
            System.out.print(rng.dumpState());
            if (a.saveState != null) VaultRng.save(a.saveState, rng);
            return;
        }

        LootResult result = simulateVault(rng);
        System.out.println("Minecraft 1.21.11 Ominous Vault Predictor");
        System.out.println("World seed: " + a.seed);
        if (a.vault != null) System.out.printf("Vault: %d %d %d%n", a.vault.x, a.vault.y, a.vault.z);
        System.out.println("Target: minecraft:heavy_core");
        System.out.println();
        System.out.println("Predicted loot: Heavy Core: " + (result.heavyCore() ? "YES" : "NO"));
        System.out.println("Full simulated items: " + String.join("; ", result.items()));
        System.out.println("Root sequence state after simulation:");
        System.out.print(rng.dumpState());
        if (a.saveState != null) VaultRng.save(a.saveState, rng);
    }

    // -----------------------------------------------------------------
    // POOL MODEL (verified 1.21.11): three pools ALL fire. Pool 1 is an
    // 80/20 weighted pick between two nested loot-table refs; pool 2 is
    // uniform 1..3 more common-table invocations; pool 3 is a 75%-gated
    // WEIGHTED draw from five uniques. The project notes claiming a
    // weight-2175 mixed pool and a six-entry unique table with "empty"
    // were WRONG - superseded by the fetched data-generator JSON.
    // -----------------------------------------------------------------
    static LootResult simulateVault(VaultRng rng) {
        List<String> items = new ArrayList<>();

        // Pool 1 (rolls=1): rare(w8) vs common(w2).
        if (rng.root.nextInt(10) < 8) {
            simulateRare(rng, items);
        } else {
            simulateCommon(rng, items);
        }

        // Pool 2: uniform rolls 1..3, each one common-table invocation.
        int extraRolls = rng.root.nextInt(3) + 1;
        for (int i = 0; i < extraRolls; i++) {
            simulateCommon(rng, items);
        }

        // Pool 3 (rolls=1): random_chance(0.75) gate, then the WEIGHTED
        // unique table. Branch order matches JSON entry order so cumulative
        // range mapping stays bit-compatible with vanilla iteration.
        if (rng.root.nextFloat() < 0.75f) {
            int roll = rng.unique.nextInt(10);
            if (roll < 3) {
                items.add("enchanted_golden_apple");
            } else if (roll < 6) {
                items.add("flow_armor_trim_smithing_template");
            } else if (roll < 8) {
                items.add("flow_banner_pattern");
            } else if (roll < 9) {
                items.add("music_disc_creator");
            } else {
                items.add("heavy_core");
                return new LootResult(true, items);
            }
        }
        return new LootResult(false, items);
    }

    /** reward_ominous_common: weights 5/4/3/2/1 = 15. Consumes COMMON stream. */
    private static void simulateCommon(VaultRng rng, List<String> items) {
        Xoroshiro128PlusPlus r = rng.common;
        int roll = r.nextInt(15);
        if (roll < 5) {
            int amt = 4 + r.nextInt(7);          // uniform 4..10
            items.add("emerald x" + amt);
        } else if (roll < 9) {
            int amt = 8 + r.nextInt(5);          // uniform 8..12
            items.add("wind_charge x" + amt);
        } else if (roll < 12) {
            int amt = 4 + r.nextInt(9);          // uniform 4..12
            items.add("tipped_arrow_slowness x" + amt);
        } else if (roll < 14) {
            int amt = 2 + r.nextInt(2);          // uniform 2..3
            items.add("diamond x" + amt);
        } else {
            String[] lvl = {"I", "II", "III", "IV", "V"};
            // set_ominous_bottle_amplifier uniform 2..4 -> bottles III..V.
            items.add("ominous_bottle " + lvl[r.nextInt(3) + 2]);
        }
    }

    /** reward_ominous_rare: weights 5,4,4,3,3,3,2,2,2,1 = 29. RARE stream. */
    private static void simulateRare(VaultRng rng, List<String> items) {
        Xoroshiro128PlusPlus r = rng.rare;
        int roll = r.nextInt(29);
        if (roll < 5) { items.add("emerald_block"); return; }
        if (roll < 9) { items.add("iron_block"); return; }
        if (roll < 13) {
            int level = r.nextInt(16) + 5;        // enchant_with_levels 5..20
            items.add("crossbow[" + simulateSelectEnchantment(r, ITEM_CROSSBOW, level) + "]");
            return;
        }
        if (roll < 16) { items.add("golden_apple"); return; }
        if (roll < 19) {
            int level = r.nextInt(11) + 10;       // enchant_with_levels 10..20
            items.add("diamond_axe[" + simulateSelectEnchantment(r, ITEM_DIAMOND_AXE, level) + "]");
            return;
        }
        if (roll < 22) {
            int level = r.nextInt(11) + 10;       // enchant_with_levels 10..20
            items.add("diamond_chestplate[" + simulateSelectEnchantment(r, ITEM_DIAMOND_CHESTPLATE, level) + "]");
            return;
        }
        if (roll < 24) {
            items.add("book[" + simulateRandomBookEnchant(r,
                    new EnchantDef[]{ENCH_KNOCKBACK, ENCH_PUNCH, ENCH_SMITE, ENCH_LOOTING, ENCH_MULTISHOT}) + "]");
            return;
        }
        if (roll < 26) {
            items.add("book[" + simulateRandomBookEnchant(r,
                    new EnchantDef[]{ENCH_BREACH, ENCH_DENSITY}) + "]");
            return;
        }
        if (roll < 28) {
            // This book is set_enchantments(wind_burst=1) - FIXED level,
            // never routed through enchant_randomly.
            items.add("book[wind_burst]");
            return;
        }
        items.add("diamond_block");
    }

    private static String simulateRandomBookEnchant(Xoroshiro128PlusPlus rng, EnchantDef[] options) {
        int idx = rng.nextInt(options.length);
        EnchantDef e = options[idx];
        // EnchantRandomlyFunction calls enchantItem -> one level selection.
        int level = rng.nextInt(e.maxLevel - e.minLevel + 1) + e.minLevel;
        return e.name + " " + level;
    }

    private static String simulateSelectEnchantment(Xoroshiro128PlusPlus rng, int itemType, int initialLevel) {
        EnchantDef[] pool;
        int enchantValue;
        if (itemType == ITEM_CROSSBOW) {
            pool = CROSSBOW_POOL;
            enchantValue = 1;
        } else if (itemType == ITEM_DIAMOND_AXE) {
            pool = DIAMOND_AXE_POOL;
            enchantValue = 10;
        } else {
            pool = DIAMOND_CHESTPLATE_POOL;
            enchantValue = 10;
        }

        int level = initialLevel + 1
                + rng.nextInt(enchantValue / 4 + 1)
                + rng.nextInt(enchantValue / 4 + 1);

        float f = (rng.nextFloat() + rng.nextFloat() - 1.0f) * 0.15f;
        level = clamp(Math.round(level + level * f), 1, Integer.MAX_VALUE);

        List<EnchantmentInstance> available = getAvailable(pool, level);
        List<EnchantmentInstance> selected = new ArrayList<>(2);

        while (!available.isEmpty()) {
            EnchantmentInstance chosen = weightedPick(rng, available);
            selected.add(chosen);

            if (rng.nextInt(50) > level) {
                break;
            }

            if (!selected.isEmpty()) {
                EnchantmentInstance last = selected.get(selected.size() - 1);
                available.removeIf(e -> !areCompatible(e.enchant, last.enchant));
            }
            if (available.isEmpty()) break;

            EnchantmentInstance chosen2 = weightedPick(rng, available);
            selected.add(chosen2);
            level /= 2;
        }

        StringBuilder sb = new StringBuilder();
        for (EnchantmentInstance ei : selected) {
            if (sb.length() > 0) sb.append(",");
            sb.append(ei.enchant.name).append(" ").append(ei.level);
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static List<EnchantmentInstance> getAvailable(EnchantDef[] pool, int level) {
        List<EnchantmentInstance> out = new ArrayList<>();
        for (EnchantDef e : pool) {
            int best = -1;
            for (int l = e.maxLevel; l >= e.minLevel; l--) {
                int min = e.minBase + e.minPer * (l - 1);
                int max = e.maxBase + e.maxPer * (l - 1);
                if (level >= min && level <= max) {
                    best = l;
                    break;
                }
            }
            if (best >= 0) out.add(new EnchantmentInstance(e, best));
        }
        return out;
    }

    private static EnchantmentInstance weightedPick(Xoroshiro128PlusPlus rng, List<EnchantmentInstance> list) {
        int total = 0;
        for (EnchantmentInstance e : list) total += e.enchant.weight;
        int r = rng.nextInt(total);
        for (EnchantmentInstance e : list) {
            r -= e.enchant.weight;
            if (r < 0) return e;
        }
        throw new AssertionError();
    }

    private static boolean areCompatible(EnchantDef a, EnchantDef b) {
        return a.exclusiveGroup == null || b.exclusiveGroup == null ||
                !a.exclusiveGroup.equals(b.exclusiveGroup);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Parses user-typed seeds (decimal or 0x-hex); shared with the GUI. */
    static long parseSeedString(String s) { return Args.parseBits(s); }

    static RandomState initialState(long worldSeed) {
        return initialState(worldSeed, SEQ_ROOT);
    }

    static RandomState initialState(long worldSeed, String sequenceId) {
        // RandomSequences defaults in 1.21.11: salt=0, includeWorldSeed=true.
        // The sequence id contributes via MD5 of the FULL resource location,
        // XORed big-endian into both halves before Stafford13 mixing.
        long base = worldSeed;
        long lo = base ^ 0x6A09E667F3BCC909L;
        long hi = lo + 0x9E3779B97F4A7C15L;

        byte[] digest;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            digest = md.digest(sequenceId.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }

        long hashLo = ByteBuffer.wrap(digest, 0, 8).order(ByteOrder.BIG_ENDIAN).getLong();
        long hashHi = ByteBuffer.wrap(digest, 8, 8).order(ByteOrder.BIG_ENDIAN).getLong();
        lo ^= hashLo;
        hi ^= hashHi;

        lo = mixStafford13(lo);
        hi = mixStafford13(hi);

        if ((lo | hi) == 0L) {
            lo = 0x9E3779B97F4A7C15L;
            hi = 0x6A09E667F3BCC909L;
        }
        return new RandomState(lo, hi);
    }

    private static long mixStafford13(long x) {
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        x = x ^ (x >>> 31);
        return x;
    }

    /**
     * Recover how many whole vault-opens precede the known observations by
     * brute-force replaying from the seed-derived state. All four streams
     * advance deterministically per simulated open, so matching N consecutive
     * real vaults (multiset of full item lists) pins the JOINT state.
     */
    static int recoverOffset(VaultRng rng, List<List<String>> knownLoots, int maxSearch) {
        if (knownLoots.isEmpty()) {
            throw new IllegalArgumentException("Need at least 1 known observed vault result to recover offset");
        }
        for (int offset = 0; offset < maxSearch; offset++) {
            VaultRng trial = rng.snapshot();
            for (int i = 0; i < offset; i++) simulateVault(trial);
            VaultRng probe = trial.snapshot();
            boolean allMatch = true;
            for (List<String> known : knownLoots) {
                LootResult res = simulateVault(probe);
                List<String> s1 = new ArrayList<>(res.items());
                List<String> s2 = new ArrayList<>(known);
                Collections.sort(s1);
                Collections.sort(s2);
                if (!s1.equals(s2)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                return offset;
            }
        }
        return -1;
    }

    /** Parses known-loot file: one line per vault, SEMICOLON-separated items, in opening order. */
    private static List<List<String>> readKnownLootFile(File f) throws IOException {
        List<List<String>> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                List<String> items = new ArrayList<>();
                for (String part : line.split(";")) {
                    String t = part.trim();
                    if (!t.isEmpty()) items.add(t);
                }
                out.add(items);
            }
        }
        return out;
    }

    private static List<Vault> readVaultFile(File f) throws IOException {
        List<Vault> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\s+");
                if (p.length < 3) throw new IllegalArgumentException("vault-file line " + lineNo + ": expected x y z");
                out.add(new Vault(Long.parseLong(p[0]), Long.parseLong(p[1]), Long.parseLong(p[2])));
            }
        }
        return out;
    }

    /** Holds the FOUR per-table random sequences consumed by one vault open. */
    static final class VaultRng {
        Xoroshiro128PlusPlus root;
        Xoroshiro128PlusPlus rare;
        Xoroshiro128PlusPlus common;
        Xoroshiro128PlusPlus unique;

        private VaultRng(Xoroshiro128PlusPlus root, Xoroshiro128PlusPlus rare,
                         Xoroshiro128PlusPlus common, Xoroshiro128PlusPlus unique) {
            this.root = root;
            this.rare = rare;
            this.common = common;
            this.unique = unique;
        }

        static VaultRng fromSeed(long worldSeed) {
            return new VaultRng(
                    mk(initialState(worldSeed, SEQ_ROOT)),
                    mk(initialState(worldSeed, SEQ_RARE)),
                    mk(initialState(worldSeed, SEQ_COMMON)),
                    mk(initialState(worldSeed, SEQ_UNIQUE)));
        }

        private static Xoroshiro128PlusPlus mk(RandomState s) {
            return new Xoroshiro128PlusPlus(s.lo(), s.hi());
        }

        VaultRng snapshot() {
            return new VaultRng(new Xoroshiro128PlusPlus(root.lo, root.hi),
                                new Xoroshiro128PlusPlus(rare.lo, rare.hi),
                                new Xoroshiro128PlusPlus(common.lo, common.hi),
                                new Xoroshiro128PlusPlus(unique.lo, unique.hi));
        }

        String dumpState() {
            return String.format(
                    "  Root   lo=0x%016X hi=0x%016X%n" +
                    "  Rare   lo=0x%016X hi=0x%016X%n" +
                    "  Common lo=0x%016X hi=0x%016X%n" +
                    "  Unique lo=0x%016X hi=0x%016X%n",
                    root.lo, root.hi, rare.lo, rare.hi,
                    common.lo, common.hi, unique.lo, unique.hi);
        }

        static void save(File f, VaultRng rng) throws IOException {
            Properties p = new Properties();
            p.setProperty("root.lo", String.format("%016X", rng.root.lo));
            p.setProperty("root.hi", String.format("%016X", rng.root.hi));
            p.setProperty("rare.lo", String.format("%016X", rng.rare.lo));
            p.setProperty("rare.hi", String.format("%016X", rng.rare.hi));
            p.setProperty("common.lo", String.format("%016X", rng.common.lo));
            p.setProperty("common.hi", String.format("%016X", rng.common.hi));
            p.setProperty("unique.lo", String.format("%016X", rng.unique.lo));
            p.setProperty("unique.hi", String.format("%016X", rng.unique.hi));
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
                p.store(w, "OminousVaultPredictor four-stream state");
            }
        }

        static VaultRng load(File f) throws IOException {
            Properties p = new Properties();
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                p.load(r);
            }
            return new VaultRng(
                    fromHex(p, "root"),
                    fromHex(p, "rare"),
                    fromHex(p, "common"),
                    fromHex(p, "unique"));
        }

        private static Xoroshiro128PlusPlus fromHex(Properties p, String name) {
            String lo = p.getProperty(name + ".lo");
            String hi = p.getProperty(name + ".hi");
            if (lo == null || hi == null) {
                throw new IllegalArgumentException("state file missing " + name + " stream");
            }
            return new Xoroshiro128PlusPlus(Long.parseUnsignedLong(lo.trim(), 16),
                                            Long.parseUnsignedLong(hi.trim(), 16));
        }
    }

    static final class Xoroshiro128PlusPlus {
        long lo;
        long hi;
        Xoroshiro128PlusPlus(long lo, long hi) {
            this.lo = lo;
            this.hi = hi;
            if ((lo | hi) == 0L) {
                this.lo = 0x9E3779B97F4A7C15L;
                this.hi = 0x6A09E667F3BCC909L;
            }
        }
        long nextLong() {
            long s0 = lo;
            long s1 = hi;
            long result = Long.rotateLeft(s0 + s1, 17) + s0;
            s1 ^= s0;
            lo = Long.rotateLeft(s0, 49) ^ s1 ^ (s1 << 21);
            hi = Long.rotateLeft(s1, 28);
            return result;
        }
        int nextInt() {
            return (int)nextLong();
        }
        int nextInt(int bound) {
            if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
            int bits = nextInt();
            long ul = Integer.toUnsignedLong(bits);
            long product = ul * bound;
            long low = product & 0xFFFF_FFFFL;
            if (low < bound) {
                int threshold = Integer.remainderUnsigned(-bound, bound);
                while (low < threshold) {
                    ul = Integer.toUnsignedLong(nextInt());
                    product = ul * bound;
                    low = product & 0xFFFF_FFFFL;
                }
            }
            return (int)(product >>> 32);
        }
        float nextFloat() {
            return (nextLong() >>> 40) * 5.9604645E-8f;
        }
    }

    record RandomState(long lo, long hi) {}
    record LootResult(boolean heavyCore, List<String> items) {}
    private record Vault(long x, long y, long z) {}
    private record EnchantmentInstance(EnchantDef enchant, int level) {}
    private record EnchantDef(String name, int weight, int minLevel, int maxLevel,
                              int minBase, int minPer, int maxBase, int maxPer,
                              String exclusiveGroup) {}

    private static final int ITEM_CROSSBOW = 1;
    private static final int ITEM_DIAMOND_AXE = 2;
    private static final int ITEM_DIAMOND_CHESTPLATE = 3;

    private static final EnchantDef ENCH_KNOCKBACK =
            new EnchantDef("knockback", 2, 1, 2, 5, 20, 55, 20, null);
    private static final EnchantDef ENCH_PUNCH =
            new EnchantDef("punch", 2, 1, 2, 12, 20, 37, 20, null);
    private static final EnchantDef ENCH_SMITE =
            new EnchantDef("smite", 2, 1, 5, 5, 8, 25, 8, "damage");
    private static final EnchantDef ENCH_LOOTING =
            new EnchantDef("looting", 2, 1, 3, 15, 9, 65, 9, null);
    private static final EnchantDef ENCH_MULTISHOT =
            new EnchantDef("multishot", 2, 1, 1, 20, 0, 50, 0, "crossbow");
    private static final EnchantDef ENCH_BREACH =
            new EnchantDef("breach", 2, 1, 4, 15, 9, 65, 9, "damage");
    private static final EnchantDef ENCH_DENSITY =
            new EnchantDef("density", 2, 1, 5, 5, 8, 25, 8, "damage");

    // Tag #minecraft:on_random_loot resolved for the three enchanted rare items.
    private static final EnchantDef[] CROSSBOW_POOL = {
            new EnchantDef("mending", 2, 1, 1, 25, 25, 75, 25, null),
            new EnchantDef("multishot", 2, 1, 1, 20, 0, 50, 0, "crossbow"),
            new EnchantDef("piercing", 10, 1, 4, 1, 10, 50, 0, "crossbow"),
            new EnchantDef("quick_charge", 5, 1, 3, 12, 20, 50, 0, null),
            new EnchantDef("unbreaking", 5, 1, 3, 5, 8, 55, 8, null),
            new EnchantDef("vanishing_curse", 1, 1, 1, 25, 0, 50, 0, null)
    };

    private static final EnchantDef[] DIAMOND_AXE_POOL = {
            new EnchantDef("efficiency", 10, 1, 5, 1, 10, 51, 10, null),
            new EnchantDef("fortune", 2, 1, 3, 15, 9, 65, 9, "mining"),
            new EnchantDef("mending", 2, 1, 1, 25, 25, 75, 25, null),
            new EnchantDef("silk_touch", 1, 1, 1, 15, 0, 65, 0, "mining"),
            new EnchantDef("unbreaking", 5, 1, 3, 5, 8, 55, 8, null),
            new EnchantDef("vanishing_curse", 1, 1, 1, 25, 0, 50, 0, null)
    };

    private static final EnchantDef[] DIAMOND_CHESTPLATE_POOL = {
            new EnchantDef("binding_curse", 1, 1, 1, 25, 0, 50, 0, null),
            new EnchantDef("blast_protection", 2, 1, 4, 5, 8, 13, 8, "armor"),
            new EnchantDef("fire_protection", 5, 1, 4, 10, 8, 18, 8, "armor"),
            new EnchantDef("mending", 2, 1, 1, 25, 25, 75, 25, null),
            new EnchantDef("projectile_protection", 5, 1, 4, 3, 6, 9, 6, "armor"),
            new EnchantDef("protection", 10, 1, 4, 1, 11, 12, 11, "armor"),
            new EnchantDef("thorns", 1, 1, 3, 10, 20, 60, 20, null),
            new EnchantDef("unbreaking", 5, 1, 3, 5, 8, 55, 8, null),
            new EnchantDef("vanishing_curse", 1, 1, 1, 25, 0, 50, 0, null)
    };

    private static final class Args {
        Long seed;
        Long stateLo;
        Long stateHi;
        long skipVaults = 0;
        File vaultFile;
        File knownLootFile;
        File stateFile;
        File saveState;
        long maxSearch = 100_000;
        Vault vault;
        boolean help;

        static Args parse(String[] args) {
            Args a = new Args();
            for (int i = 0; i < args.length; i++) {
                String s = args[i];
                switch (s) {
                    case "--help", "-h" -> a.help = true;
                    case "--seed" -> a.seed = parseBits(next(args, ++i, s));
                    case "--state-lo" -> a.stateLo = parseBits(next(args, ++i, s));
                    case "--state-hi" -> a.stateHi = parseBits(next(args, ++i, s));
                    case "--state-file" -> a.stateFile = new File(next(args, ++i, s));
                    case "--save-state" -> a.saveState = new File(next(args, ++i, s));
                    case "--skip-vaults" -> a.skipVaults = Long.parseLong(next(args, ++i, s));
                    case "--vault-file" -> a.vaultFile = new File(next(args, ++i, s));
                    case "--known-loot-file" -> a.knownLootFile = new File(next(args, ++i, s));
                    case "--max-search" -> a.maxSearch = Long.parseLong(next(args, ++i, s));
                    case "--target" -> {
                        String target = next(args, ++i, s);
                        if (!"minecraft:heavy_core".equals(target)) {
                            throw new IllegalArgumentException("Only --target minecraft:heavy_core is implemented");
                        }
                    }
                    case "--vault-x" -> {
                        long x = Long.parseLong(next(args, ++i, s));
                        if (a.vault == null) a.vault = new Vault(x, 0, 0); else a.vault = new Vault(x, a.vault.y, a.vault.z);
                    }
                    case "--vault-y" -> {
                        long y = Long.parseLong(next(args, ++i, s));
                        if (a.vault == null) a.vault = new Vault(0, y, 0); else a.vault = new Vault(a.vault.x, y, a.vault.z);
                    }
                    case "--vault-z" -> {
                        long z = Long.parseLong(next(args, ++i, s));
                        if (a.vault == null) a.vault = new Vault(0, 0, z); else a.vault = new Vault(a.vault.x, a.vault.y, a.vault.z);
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + s);
                }
            }
            return a;
        }

        static String next(String[] args, int i, String opt) {
            if (i >= args.length) throw new IllegalArgumentException("Missing value for " + opt);
            return args[i];
        }

        static long parseBits(String s) {
            s = s.replace("_", "");
            boolean negative = s.startsWith("-");
            if (negative) return Long.parseLong(s);
            if (s.startsWith("0x") || s.startsWith("0X")) return Long.parseUnsignedLong(s.substring(2), 16);
            return Long.parseLong(s);
        }

        static void usage() {
            System.out.println("Usage:");
            System.out.println("  java OminousVaultPredictor --seed 123456789");
            System.out.println("  java OminousVaultPredictor --seed S --vault-file vaults.txt [--save-state st.props]");
            System.out.println("  java OminousVaultPredictor --state-file st.props [...]   # full four-stream resume");
            System.out.println("  java OminousVaultPredictor --seed S --known-loot-file known.txt [--max-search N] [--save-state st.props]");
            System.out.println("      known.txt: one line per vault YOU opened, exact chronological order,");
            System.out.println("      items SEMICOLON-separated (commas appear inside enchant lists),");
            System.out.println("      in this tool's own item format (see --vault-file output).");
            System.out.println("  optional: --skip-vaults N; --state-lo/--state-hi = ROOT-stream-only debug override");
        }
    }
}
