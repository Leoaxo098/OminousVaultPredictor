package dev.leo.vaultpredictor;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.regex.*;

/**
 * Swing front-end for OminousVaultPredictor.
 *
 * Left column: seed + observed-vault editor (chronological order!) and the
 * offset-recovery button. Right column: forecast against a chosen target plus
 * an always-visible stats tab. Profiles persist via File menu (.properties).
 */
public final class VaultPredictorGui extends JFrame {

    // Canonical item templates shown in the row editor. The STRING formats are
    // mirrored 1:1 from OminousVaultPredictor.simulateVault emissions; any
    // divergence breaks offset matching. LootTableParityTest guards this via
    // canonicalPatterns() below.
    enum ItemKind {
        STACK("Stackable", true),
        PLAIN("Single item", false),
        GEAR("Enchanted gear", false),
        BOOK_GROUP("Enchanted book (options list)", false),
        FIXED_BOOK("Fixed enchant book", false);

        final String label;
        final boolean hasCount;
        ItemKind(String label, boolean hasCount) { this.label = label; this.hasCount = hasCount; }
    }

    private record Template(String token, String display, ItemKind kind,
                            int minQty, int maxQty, int weightForStats) {}

    private static final List<Template> TEMPLATES = List.of(
            new Template("emerald", "Emerald", ItemKind.STACK, 4, 10, 5),
            new Template("wind_charge", "Wind charge", ItemKind.STACK, 8, 12, 4),
            new Template("tipped_arrow_slowness", "Tipped arrow (slowness)", ItemKind.STACK, 4, 12, 3),
            new Template("diamond", "Diamond", ItemKind.STACK, 2, 3, 2),
            new Template("ominous_bottle", "Ominous bottle", ItemKind.PLAIN, 0, 0, 1),
            new Template("emerald_block", "Block of emerald", ItemKind.PLAIN, 0, 0, 0),
            new Template("iron_block", "Block of iron", ItemKind.PLAIN, 0, 0, 0),
            new Template("golden_apple", "Golden apple", ItemKind.PLAIN, 0, 0, 0),
            new Template("diamond_block", "Block of diamond", ItemKind.PLAIN, 0, 0, 0),
            new Template("crossbow", "Crossbow (enchanted)", ItemKind.GEAR, 0, 0, 0),
            new Template("diamond_axe", "Diamond axe (enchanted)", ItemKind.GEAR, 0, 0, 0),
            new Template("diamond_chestplate", "Diamond chestplate (enchanted)", ItemKind.GEAR, 0, 0, 0),
            new Template("book", "Book (knockback/punch/smite/looting/multishot)", ItemKind.BOOK_GROUP, 0, 0, 0),
            new Template("book", "Book (breach/density)", ItemKind.BOOK_GROUP, 0, 0, 0),
            new Template("book", "Book (wind burst)", ItemKind.FIXED_BOOK, 0, 0, 0),
            new Template("enchanted_golden_apple", "Enchanted golden apple", ItemKind.PLAIN, 0, 0, 0),
            new Template("flow_armor_trim_smithing_template", "Flow armor trim", ItemKind.PLAIN, 0, 0, 0),
            new Template("flow_banner_pattern", "Flow banner pattern", ItemKind.PLAIN, 0, 0, 0),
            new Template("music_disc_creator", "Music disc creator", ItemKind.PLAIN, 0, 0, 0),
            new Template("heavy_core", "Heavy core", ItemKind.PLAIN, 0, 0, 0));

    /** Regexes every simulator emission must satisfy (GUI <-> sim parity). */
    static List<Pattern> canonicalPatterns() {
        List<Pattern> out = new ArrayList<>();
        out.add(Pattern.compile("(emerald|wind_charge|tipped_arrow_slowness|diamond) x\\d+"));
        out.add(Pattern.compile("ominous_bottle (III|IV|V)"));
        for (String g : new String[]{"crossbow", "diamond_axe", "diamond_chestplate", "book"}) {
            out.add(Pattern.compile(g + "\\[[a-z_]+ \\d+(,[a-z_]+ \\d+)*\\]"));
        }
        out.add(Pattern.compile("book\\[wind_burst\\]"));
        for (String s : new String[]{"emerald_block", "iron_block", "golden_apple",
                "diamond_block", "enchanted_golden_apple", "flow_armor_trim_smithing_template",
                "flow_banner_pattern", "music_disc_creator", "heavy_core"}) {
            out.add(Pattern.compile(s));
        }
        return out;
    }

    // Enchantment tables mirrored from OminousVaultPredictor pools (names only).
    private static final Map<String, String[][]> ENCH_OPTIONS = Map.of(
            "crossbow", new String[][]{
                    {"mending", "1", "1"}, {"multishot", "1", "1"}, {"piercing", "1", "4"},
                    {"quick_charge", "1", "3"}, {"unbreaking", "1", "3"}, {"vanishing_curse", "1", "1"}},
            "diamond_axe", new String[][]{
                    {"efficiency", "1", "5"}, {"fortune", "1", "3"}, {"mending", "1", "1"},
                    {"silk_touch", "1", "1"}, {"unbreaking", "1", "3"}, {"vanishing_curse", "1", "1"}},
            "diamond_chestplate", new String[][]{
                    {"binding_curse", "1", "1"}, {"blast_protection", "1", "4"}, {"fire_protection", "1", "4"},
                    {"mending", "1", "1"}, {"projectile_protection", "1", "4"}, {"protection", "1", "4"},
                    {"thorns", "1", "3"}, {"unbreaking", "1", "3"}, {"vanishing_curse", "1", "1"}});
    private static final String[] BOOK_A = {"knockback", "punch", "smite", "looting", "multishot"};
    private static final String[] BOOK_B = {"breach", "density"};

    private JTextField seedField = new JTextField(12);
    private DefaultListModel<String> vaultModel = new DefaultListModel<>();
    private JList<String> vaultList = new JList<>(vaultModel);
    private JComboBox<String> targetCombo = new JComboBox<>();
    private JTextArea outputArea = new JTextArea();
    private JLabel statusLabel = new JLabel("Ready.");
    private JCheckBox useOffsetCheck = new JCheckBox("Predict from recovered state (uncheck: fresh seed / offset 0)");
    private Integer recoveredOffset;
    private JSpinner forecastCapSpinner = new JSpinner(new SpinnerNumberModel(500, 10, 100000, 50));

    public VaultPredictorGui() {
        super("Ominous Vault Predictor - Minecraft 1.21.11");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        add(buildTopPanel(), BorderLayout.NORTH);
        add(buildLeftPanel(), BorderLayout.WEST);
        add(buildRightTabs(), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
        setJMenuBar(buildMenu());
        setSize(1080, 720);
        setLocationRelativeTo(null);
    }

    private JMenuBar buildMenu() {
        JMenuBar mb = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem open = new JMenuItem("Open profile...");
        open.addActionListener(e -> openProfile());
        JMenuItem save = new JMenuItem("Save profile...");
        save.addActionListener(e -> saveProfile());
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> System.exit(0));
        file.add(open); file.add(save); file.addSeparator(); file.add(exit);
        mb.add(file);
        return mb;
    }

    private void saveProfile() {
        JFileChooser fc = new JFileChooser();
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        Properties p = new Properties();
        p.setProperty("seed", seedField.getText().trim());
        p.setProperty("target", String.valueOf(targetCombo.getSelectedItem()));
        Enumeration<String> en = vaultModel.elements();
        int i = 0;
        while (en.hasMoreElements()) p.setProperty("vault." + i++, en.nextElement());
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            p.store(w, "OminousVaultPredictor profile");
            status("Saved profile to " + f.getName());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
        }
    }

    private void openProfile() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(new FileInputStream(fc.getSelectedFile()), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Load failed: " + ex.getMessage());
            return;
        }
        seedField.setText(p.getProperty("seed", ""));
        targetCombo.setSelectedItem(p.getProperty("target", "minecraft:heavy_core"));
        vaultModel.clear();
        p.stringPropertyNames().stream()
                .filter(k -> k.startsWith("vault."))
                .sorted(Comparator.comparingInt(k -> Integer.parseInt(k.substring(6))))
                .forEach(k -> vaultModel.addElement(p.getProperty(k)));
        recoveredOffset = null;
        status("Loaded profile from " + fc.getSelectedFile().getName());
    }

    private JPanel buildTopPanel() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("World seed:"));
        top.add(seedField);
        top.add(useOffsetCheck);
        useOffsetCheck.setEnabled(false);
        top.add(new JLabel("Target item:"));
        targetCombo.setEditable(false);
        TEMPLATES.forEach(t -> targetCombo.addItem(displayToTarget(t)));
        targetCombo.setSelectedItem("minecraft:heavy_core");
        top.add(targetCombo);
        return top;
    }

    private static String displayToTarget(Template t) {
        // dropdown shows human names; mapping kept simple via display string
        return t.display;
    }

    private String selectedToken() {
        String sel = (String) targetCombo.getSelectedItem();
        for (Template t : TEMPLATES) if (t.display.equals(sel)) return t.token;
        return "heavy_core";
    }

    private JPanel buildLeftPanel() {
        JPanel left = new JPanel(new BorderLayout(4, 4));
        left.setBorder(new TitledBorder("Observed vaults (YOUR opens, in exact order)"));
        vaultList.setVisibleRowCount(14);
        left.add(new JScrollPane(vaultList), BorderLayout.CENTER);

        JPanel btns = new JPanel(new GridLayout(0, 1, 4, 4));
        JButton add = new JButton("Add observed vault...");
        add.addActionListener(e -> new AddObservedVaultDialog(this).setVisible(true));
        JButton del = new JButton("Remove selected");
        del.addActionListener(e -> {
            int i = vaultList.getSelectedIndex();
            if (i >= 0) { vaultModel.remove(i); recoveredOffset = null; useOffsetCheck.setEnabled(false); }
        });
        JButton up = new JButton("Move up");
        up.addActionListener(e -> swap(-1));
        JButton down = new JButton("Move down");
        down.addActionListener(e -> swap(1));
        btns.add(add); btns.add(del); btns.add(up); btns.add(down);

        JButton recover = new JButton("Recover offset from these vaults");
        recover.addActionListener(e -> runRecovery());
        btns.add(recover);
        left.add(btns, BorderLayout.SOUTH);
        return left;
    }

    private void swap(int delta) {
        int i = vaultList.getSelectedIndex();
        int j = i + delta;
        if (i < 0 || j < 0 || j >= vaultModel.size()) return;
        String a = vaultModel.get(i);
        vaultModel.set(i, vaultModel.get(j));
        vaultModel.set(j, a);
        vaultList.setSelectedIndex(j);
        recoveredOffset = null;
        useOffsetCheck.setEnabled(false);
    }

    private void runRecovery() {
        Long seed = parseSeed();
        if (seed == null || vaultModel.isEmpty()) return;
        List<List<String>> known = new ArrayList<>();
        Enumeration<String> en = vaultModel.elements();
        while (en.hasMoreElements()) {
            List<String> items = new ArrayList<>();
            for (String part : en.nextElement().split(";")) {
                String t = part.trim();
                if (!t.isEmpty()) items.add(t);
            }
            known.add(items);
        }
        status("Recovering offset...");
        SwingWorker<Integer, Void> w = new SwingWorker<>() {
            @Override protected Integer doInBackground() {
                return OminousVaultPredictor.recoverOffset(
                        OminousVaultPredictor.VaultRng.fromSeed(seed), known, 100_000);
            }
            @Override protected void done() {
                try {
                    int off = get();
                    if (off < 0) {
                        status("No offset matched - check order / solo-consumption assumption.");
                        JOptionPane.showMessageDialog(VaultPredictorGui.this,
                                "No consistent offset found within 100,000.\n\n" +
                                "Common causes:\n" +
                                "- vault entries not in the exact order you opened them\n" +
                                "- something else (other player?) opened an ominous vault in between\n" +
                                "- typo in an item entry (must match simulator format exactly)\n\n" +
                                "If these were truly your world's FIRST ominous vaults (offset 0), " +
                                "even the very first open failed to match - the seeded-init model " +
                                "likely diverges from your runtime (see README caveats).");
                    } else {
                        recoveredOffset = off;
                        useOffsetCheck.setEnabled(true);
                        useOffsetCheck.setSelected(true);
                        status("Recovered: " + off + " vaults were already opened before your first observation.");
                    }
                } catch (Exception ex) {
                    status("Recovery failed: " + ex.getMessage());
                }
            }
        };
        w.execute();
    }

    private JTabbedPane buildRightTabs() {
        JTabbedPane tabs = new JTabbedPane();
        outputArea.setEditable(false);
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));

        JPanel forecast = new JPanel(new BorderLayout(4, 4));
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(new JLabel("Simulate up to"));
        bar.add(forecastCapSpinner);
        bar.add(new JLabel("future vaults; report first vault containing target."));
        JButton go = new JButton("Find my loot!");
        go.addActionListener(e -> runForecast());
        bar.add(go);
        forecast.add(bar, BorderLayout.NORTH);
        forecast.add(new JScrollPane(outputArea), BorderLayout.CENTER);
        tabs.addTab("Forecast", forecast);

        JTextArea stats = new JTextArea(buildStatsText());
        stats.setEditable(false);
        stats.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        tabs.addTab("Odds & statistics", new JScrollPane(stats));
        return tabs;
    }

    private String buildStatsText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Per-vault probabilities from the verified 1.21.11 tables\\n");
        sb.append("======================================================\\n\\n");
        sb.append("UNIQUE pool items (single slot per vault):\\n");
        row(sb, "heavy_core", 0.75 * 1 / 10.0);
        row(sb, "enchanted_golden_apple", 0.75 * 3 / 10.0);
        row(sb, "flow_armor_trim_smithing_template", 0.75 * 3 / 10.0);
        row(sb, "flow_banner_pattern", 0.75 * 2 / 10.0);
        row(sb, "music_disc_creator", 0.75 * 1 / 10.0);
        sb.append("\\nRARE-table items (exact; vault fires rare branch with p=0.8):\\n");
        int[][] rareW = {{5, 29}, {4, 29}, {4, 29}, {3, 29}, {3, 29}, {3, 29}, {2, 29}, {2, 29}, {2, 29}, {1, 29}};
        String[] rareNames = {"emerald_block", "iron_block", "crossbow(any enchant)", "golden_apple",
                "diamond_axe(any enchant)", "diamond_chestplate(any enchant)",
                "book(one of 5 enchants)", "book(breach/density)", "book[wind_burst]", "diamond_block"};
        for (int i = 0; i < rareNames.length; i++) row(sb, rareNames[i], 0.8 * rareW[i][0] / (double) rareW[i][1]);
        sb.append("\\nCOMMON stackables (approx: ignores multi-roll overlap;\\n");
        sb.append("p >= value shown since pool 2 rolls 1..3 extra times):\\n");
        row(sb, "emerald (any amount)", 0.2 * 5 / 15.0);
        row(sb, "wind_charge (any amount)", 0.2 * 4 / 15.0);
        row(sb, "tipped_arrow_slowness (any amount)", 0.2 * 3 / 15.0);
        row(sb, "diamond (any amount)", 0.2 * 2 / 15.0);
        row(sb, "ominous_bottle", 0.2 * 1 / 15.0);
        return sb.toString();
    }

    private void row(StringBuilder sb, String name, double p) {
        double expectedOpens = p > 0 ? 1.0 / p : Double.POSITIVE_INFINITY;
        sb.append(String.format("%-36s %6.2f%%   ~%7.1f opens to see one%n", name, p * 100, expectedOpens));
    }

    private Long parseSeed() {
        try {
            return OminousVaultPredictor.parseSeedString(seedField.getText().trim());
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "World seed must be a whole number.");
            return null;
        }
    }

    private void runForecast() {
        Long seed = parseSeed();
        if (seed == null) return;
        String token = selectedToken();
        int cap = (Integer) forecastCapSpinner.getValue();
        Integer off = recoveredOffset;
        boolean useRec = useOffsetCheck.isSelected() && off != null;
        status("Simulating...");
        SwingWorker<String, Void> w = new SwingWorker<>() {
            @Override protected String doInBackground() {
                OminousVaultPredictor.VaultRng rng = OminousVaultPredictor.VaultRng.fromSeed(seed);
                long skip = (useRec ? off : 0L);
                for (long i = 0; i < skip; i++) OminousVaultPredictor.simulateVault(rng);
                StringBuilder sb = new StringBuilder();
                sb.append("Using seed ").append(seed).append(useRec ? (" + recovered offset " + off) : " (offset 0)").append("\n");
                if (!useRec && off == null && !vaultModel.isEmpty()) {
                    sb.append("NOTE: observations exist but were never recovered - results are for offset 0!\n");
                }
                sb.append("Simulating the next ").append(cap).append(" ominous vaults you open, looking for \"").append(token).append("\"...\n\n");
                OminousVaultPredictor.LootResult hit = null;
                int hitIdx = -1;
                List<String> preview = new ArrayList<>();
                for (int i = 1; i <= cap; i++) {
                    OminousVaultPredictor.LootResult r = OminousVaultPredictor.simulateVault(rng);
                    boolean found = false;
                    for (String it : r.items()) {
                        if (it.equals(token) || it.startsWith(token + " ") || it.startsWith(token + " x") || it.startsWith(token + "[")) { found = true; break; }
                    }
                    if (found && hit == null) { hit = r; hitIdx = i; }
                    if (preview.size() < 10 || (hit != null && i <= hitIdx + 2)) {
                        preview.add(String.format("vault #%d:%s%s", i,
                                found ? " >>> TARGET <<<" : "", r.items().isEmpty() ? " (nothing)" : " " + String.join(" | ", r.items())));
                    }
                    if (hit != null && preview.size() >= 12) break;
                }
                // Verdict FIRST so the answer is visible without scrolling through the loot list.
                if (hit == null) {
                    double pApprox = token.startsWith("ominous_bottle") ? 0 : approxChanceForToken(token);
                    sb.append("NO ").append(token).append(" appeared within the next ").append(cap).append(" vaults.");
                    if (pApprox > 0) sb.append(String.format(" (chance per vault ~%.2f%% => raise the cap and retry)", pApprox * 100));
                    sb.append("\n");
                } else {
                    String bar = "=".repeat(60);
                    sb.append(bar).append("\n");
                    sb.append(String.format(">>> YOUR %s IS IN UPCOMING VAULT #%d <<<\n", token.toUpperCase(), hitIdx));
                    if (hitIdx == 1) {
                        sb.append("It is the VERY NEXT ominous vault you open.\n");
                    } else {
                        sb.append(String.format("Open %d more ominous vaults first - vault #%d (right after those) holds it.\n", hitIdx - 1, hitIdx));
                    }
                    sb.append("Full contents: ").append(String.join(" | ", hit.items())).append("\n");
                    sb.append(bar).append("\n");
                }
                sb.append("\nLoot of the upcoming vaults in order:\n");
                for (String line : preview) sb.append(line).append("\n");
                return sb.toString();
            }
            @Override protected void done() {
                try { outputArea.setText(get()); status("Forecast complete."); }
                catch (Exception ex) { status("Forecast failed: " + ex.getMessage()); }
            }
        };
        w.execute();
    }

    private double approxChanceForToken(String token) {
        switch (token) {
            case "heavy_core": case "music_disc_creator": return 0.075;
            case "enchanted_golden_apple": case "flow_armor_trim_smithing_template": return 0.225;
            case "flow_banner_pattern": return 0.15;
            default: return 0;
        }
    }

    private void status(String s) { statusLabel.setText(s); }

    /** Builds one observed-vault line from dropdowns/spinners; appends to vaultModel. */
    private final class AddObservedVaultDialog extends JDialog {
        private final DefaultListModel<String> itemModel = new DefaultListModel<>();
        private final List<String> pendingEnchants = new ArrayList<>();
        private final JLabel pendingLabel = new JLabel(" ");
        private final JComboBox<Template> kindCombo = new JComboBox<>();
        private final JComboBox<String> bottleLvl = new JComboBox<>(new String[]{"III", "IV", "V"});
        private final JComboBox<String> enchCombo = new JComboBox<>();
        private final SpinnerNumberModel levelModel = new SpinnerNumberModel(1, 1, 5, 1);
        private final SpinnerNumberModel qtyModel = new SpinnerNumberModel(4, 1, 64, 1);
        private final CardLayout cards = new CardLayout();
        private final JPanel cardPanel = new JPanel(cards);

        AddObservedVaultDialog(Frame owner) {
            super(owner, "Add observed vault", true);
            setLayout(new BorderLayout(6, 6));
            TEMPLATES.forEach(kindCombo::addItem);
            kindCombo.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object v,
                        int i, boolean sel, boolean foc) {
                    return super.getListCellRendererComponent(l, ((Template) v).display, i, sel, foc);
                }
            });
            kindCombo.addActionListener(e -> syncFields());

            JPanel common = new JPanel(new FlowLayout(FlowLayout.LEFT));
            common.add(new JLabel("Item:"));
            common.add(kindCombo);

            JPanel qtyCard = new JPanel(new FlowLayout(FlowLayout.LEFT));
            qtyCard.add(new JLabel("Amount:"));
            qtyCard.add(new JSpinner(qtyModel));
            JPanel bottleCard = new JPanel(new FlowLayout(FlowLayout.LEFT));
            bottleCard.add(new JLabel("Level:"));
            bottleCard.add(bottleLvl);
            JPanel enchCard = new JPanel(new FlowLayout(FlowLayout.LEFT));
            enchCard.add(new JLabel("Enchantment:"));
            enchCard.add(enchCombo);
            enchCard.add(new JLabel("Level:"));
            enchCard.add(new JSpinner(levelModel));
            enchCard.add(new JSpinner(levelModel));
            JButton moreEnch = new JButton("+ Add enchant");
            moreEnch.addActionListener(e -> {
                String rawSel = String.valueOf(enchCombo.getSelectedItem());
                if (rawSel.contains("(lv ")) {
                    pendingEnchants.add(rawSel.split(" ")[0] + " " + levelModel.getValue());
                    pendingLabel.setText(String.join(" | ", pendingEnchants));
                }
            });
            enchCard.add(moreEnch);
            enchCard.add(pendingLabel);

            cardPanel.add(qtyCard, "QTY");
            cardPanel.add(bottleCard, "BOTTLE");
            cardPanel.add(enchCard, "ENCH");
            cardPanel.add(new JLabel(" "), "NONE");

            JList<String> itemsList = new JList<>(itemModel);
            itemsList.setVisibleRowCount(6);
            JButton addItem = new JButton("Add this item to vault");
            addItem.addActionListener(e -> commitItem());
            JButton rmItem = new JButton("Remove selected item");
            rmItem.addActionListener(e -> {
                int i = itemsList.getSelectedIndex();
                if (i >= 0) itemModel.remove(i);
            });
            JButton ok = new JButton("OK - save vault");
            ok.addActionListener(e -> {
                if (!itemModel.isEmpty()) {
                    List<String> its = Collections.list(itemModel.elements());
                    vaultModel.addElement(String.join("; ", its));
                    recoveredOffset = null;
                    useOffsetCheck.setEnabled(false);
                }
                dispose();
            });
            JButton cancel = new JButton("Cancel");
            cancel.addActionListener(e -> dispose());

            JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            south.add(ok); south.add(cancel);

            JPanel center = new JPanel(new BorderLayout(6, 6));
            JPanel north = new JPanel(new BorderLayout(6, 6));
            north.add(common, BorderLayout.NORTH);
            north.add(cardPanel, BorderLayout.CENTER);
            JPanel midBtns = new JPanel(new FlowLayout(FlowLayout.LEFT));
            midBtns.add(addItem); midBtns.add(rmItem);
            center.add(north, BorderLayout.NORTH);
            center.add(midBtns, BorderLayout.CENTER);
            center.add(new JScrollPane(itemsList), BorderLayout.SOUTH);

            add(center, BorderLayout.CENTER);
            add(south, BorderLayout.SOUTH);
            setSize(560, 420);
            setLocationRelativeTo(owner);
            syncFields();
        }

        private void syncFields() {
            pendingEnchants.clear(); pendingLabel.setText(" ");
            Template t = (Template) kindCombo.getSelectedItem();
            switch (t.kind) {
                case STACK -> { cards.show(cardPanel, "QTY");
                    // ponytail: bounds 1..64 on purpose - observed loot may fall outside predicted table ranges,
                    // and recovery MUST see that divergence rather than have it clamped away.
                    qtyModel.setMinimum(1); qtyModel.setMaximum(64);
                    int q = (Integer) qtyModel.getValue();
                    if (q < 1 || q > 64) qtyModel.setValue(Math.max(1, Math.min(64, q))); }
                case PLAIN -> cards.show(cardPanel, t.token.equals("ominous_bottle") ? "BOTTLE" : "NONE");
                case GEAR -> { fillEnch(t.token); cards.show(cardPanel, "ENCH"); }
                case BOOK_GROUP -> { fillEnch(t.display.contains("breach") ? "BOOK_B" : "BOOK_A"); cards.show(cardPanel, "ENCH"); }
                case FIXED_BOOK -> cards.show(cardPanel, "NONE");
            }
        }

        private void fillEnch(String key) {
            enchCombo.removeAllItems();
            String[][] opts;
            if (key.equals("BOOK_A")) opts = bookA();
            else if (key.equals("BOOK_B")) opts = bookB();
            else opts = ENCH_OPTIONS.get(key);
            for (String[] o : opts) enchCombo.addItem(o[0] + " (lv " + o[1] + "-" + o[2] + ")");
            enchCombo.setActionCommand(key);
            enchCombo.putClientProperty("opts", opts);
            adjustLevelBounds();
        }

        private String[][] bookA() {
            return new String[][]{{"knockback", "1", "2"}, {"punch", "1", "2"}, {"smite", "1", "5"},
                    {"looting", "1", "3"}, {"multishot", "1", "1"}};
        }

        private String[][] bookB() {
            return new String[][]{{"breach", "1", "4"}, {"density", "1", "5"}};
        }

        private void adjustLevelBounds() {
            String sel = String.valueOf(enchCombo.getSelectedItem());
            if (sel == null || !sel.contains("lv ")) return;
            try {
                String[] parts = sel.split("lv ")[1].replace(")", "").split("-");
                int lo = Integer.parseInt(parts[0]);
                int hi = Integer.parseInt(parts[1]);
                levelModel.setMinimum(lo);
                levelModel.setMaximum(hi);
                if ((Integer) levelModel.getValue() > hi || (Integer) levelModel.getValue() < lo)
                    levelModel.setValue(lo);
            } catch (Exception ignored) { }
        }

        private void commitItem() {
            Template t = (Template) kindCombo.getSelectedItem();
            String s;
            switch (t.kind) {
                case STACK -> s = t.token + " x" + qtyModel.getValue();
                case PLAIN -> s = t.token.equals("ominous_bottle")
                        ? "ominous_bottle " + bottleLvl.getSelectedItem() : t.token;
                case FIXED_BOOK -> s = "book[wind_burst]";
                default -> {
                    List<String> parts = new ArrayList<>(pendingEnchants);
                    String raw = String.valueOf(enchCombo.getSelectedItem());
                    parts.add(raw.split(" ")[0] + " " + levelModel.getValue());
                    s = t.token + "[" + String.join(",", parts) + "]";
                    pendingEnchants.clear();
                    pendingLabel.setText(" ");
                }
            }
            itemModel.addElement(s);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new VaultPredictorGui().setVisible(true));
    }
}
