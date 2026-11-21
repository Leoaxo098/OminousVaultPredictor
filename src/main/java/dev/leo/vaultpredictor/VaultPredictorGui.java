package dev.leo.vaultpredictor;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

/**
 * Swing front-end for OminousVaultPredictor.
 *
 * Left column: seed + observed-vault editor (chronological order!) and the
 * offset-recovery button. Right column: forecast against a chosen target plus
 * an always-visible stats tab. Profiles persist via File menu (.properties).
 */
public final class VaultPredictorGui extends JFrame {

    private static final Color WORKSPACE = new Color(0xF4F6F4);
    private static final Color TEXT = new Color(0x18332C);
    private static final Color SECONDARY = new Color(0x50685F);

    private JTextField seedField = new JTextField(21);
    private JLabel seedIndicator = new JLabel("Enter a world seed");
    private JLabel forecastIndicator = new JLabel("Enter a seed and choose Find my loot.");
    private DefaultListModel<String> vaultModel = new DefaultListModel<>();
    private JList<String> vaultList = new JList<>(vaultModel) {
        @Override public boolean getScrollableTracksViewportWidth() { return true; }

        @Override public String getToolTipText(MouseEvent e) {
            int index = locationToIndex(e.getPoint());
            if (index < 0 || !getCellBounds(index, index).contains(e.getPoint())) return null;
            return "Vault " + (index + 1) + " · " + String.join(" · ", lootLabels(vaultModel.get(index)));
        }
    };
    private JComboBox<String> targetCombo = new JComboBox<>();
    private JTextArea outputArea = new JTextArea();
    private JLabel statusLabel = new JLabel("Ready.");
    private JCheckBox useOffsetCheck = new JCheckBox("Use recovered position");
    private Integer recoveredOffset;
    private long recoveryRevision;
    private long forecastRevision;
    private JSpinner forecastCapSpinner = new JSpinner(new SpinnerNumberModel(500, 10, 100000, 50));

    public VaultPredictorGui() {
        super("Ominous Vault Predictor - Minecraft 1.21.11");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        JPanel shell = new JPanel(new BorderLayout(12, 10));
        shell.setBorder(new EmptyBorder(12, 16, 10, 16));
        shell.setBackground(WORKSPACE);
        setContentPane(shell);
        InputStyle.apply(seedField);
        InputStyle.apply(((JSpinner.DefaultEditor) forecastCapSpinner.getEditor()).getTextField());
        add(buildTopPanel(), BorderLayout.NORTH);
        add(buildLeftPanel(), BorderLayout.WEST);
        add(buildRightTabs(), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
        setJMenuBar(buildMenu());
        seedField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { seedChanged(); }
            @Override public void removeUpdate(DocumentEvent e) { seedChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { seedChanged(); }
        });
        targetCombo.addActionListener(e -> invalidateForecast());
        useOffsetCheck.addActionListener(e -> invalidateForecast());
        forecastCapSpinner.addChangeListener(e -> invalidateForecast());
        setMinimumSize(new Dimension(920, 570));
        setSize(1100, 720);
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
        targetCombo.setSelectedItem(p.getProperty("target", "Heavy core"));
        vaultModel.clear();
        p.stringPropertyNames().stream()
                .filter(k -> k.startsWith("vault."))
                .sorted(Comparator.comparingInt(k -> Integer.parseInt(k.substring(6))))
                .forEach(k -> vaultModel.addElement(p.getProperty(k)));
        invalidateRecovery();
        status("Loaded profile from " + fc.getSelectedFile().getName());
    }

    private JPanel buildTopPanel() {
        JPanel top = new JPanel(new BorderLayout(0, 10));
        top.setOpaque(false);
        JLabel title = new JLabel("Ominous Vault Predictor");
        title.setForeground(TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 23f));
        JLabel version = new JLabel("Java Edition 1.21.11", SwingConstants.RIGHT);
        version.setForeground(SECONDARY);
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.add(title, BorderLayout.WEST);
        heading.add(version, BorderLayout.EAST);
        top.add(heading, BorderLayout.NORTH);

        JPanel settings = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 7));
        settings.setBackground(Color.WHITE);
        settings.setBorder(BorderFactory.createLineBorder(new Color(0xD6E0DA)));
        JLabel seedLabel = new JLabel("World seed");
        seedLabel.setLabelFor(seedField);
        settings.add(seedLabel);
        JPanel seedInput = new JPanel(new BorderLayout(0, 3));
        seedInput.setOpaque(false);
        seedIndicator.setForeground(SECONDARY);
        seedInput.add(seedField, BorderLayout.CENTER);
        seedInput.add(seedIndicator, BorderLayout.SOUTH);
        settings.add(seedInput);
        JLabel targetLabel = new JLabel("Target item");
        targetLabel.setLabelFor(targetCombo);
        settings.add(targetLabel);
        useOffsetCheck.setEnabled(false);
        useOffsetCheck.setOpaque(false);
        useOffsetCheck.setToolTipText("When unchecked, forecasts start at the first vault for this seed.");
        targetCombo.setEditable(false);
        for (VaultLoot.Item item : VaultLoot.Item.values()) targetCombo.addItem(item.profileLabel);
        targetCombo.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                VaultLoot.Item item = targetItem((String) value);
                return super.getListCellRendererComponent(list, item == null ? value : item.label, index, selected, focus);
            }
        });
        targetCombo.setSelectedItem("Heavy core");
        settings.add(targetCombo);
        settings.add(useOffsetCheck);
        top.add(settings, BorderLayout.CENTER);
        return top;
    }

    private String selectedToken() {
        VaultLoot.Item item = targetItem((String) targetCombo.getSelectedItem());
        return item == null ? "heavy_core" : item.token;
    }

    private static VaultLoot.Item targetItem(String selection) {
        for (VaultLoot.Item item : VaultLoot.Item.values()) {
            if (item.profileLabel.equals(selection)) return item;
        }
        return null;
    }

    private JPanel buildLeftPanel() {
        JPanel left = new JPanel(new BorderLayout(0, 9));
        left.setPreferredSize(new Dimension(345, 0));
        left.setOpaque(false);
        JLabel title = new JLabel("Vault history");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        title.setForeground(TEXT);
        JLabel count = new JLabel();
        count.setForeground(SECONDARY);
        JPanel heading = new JPanel(new BorderLayout(0, 3));
        heading.setOpaque(false);
        heading.add(title, BorderLayout.NORTH);
        heading.add(count, BorderLayout.CENTER);
        JButton add = VaultLootEditor.primary("Log vault…");
        add.addActionListener(e -> editVault(-1));
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setOpaque(false);
        top.add(heading, BorderLayout.CENTER);
        top.add(add, BorderLayout.EAST);
        left.add(top, BorderLayout.NORTH);

        vaultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        vaultList.setToolTipText("");
        vaultList.setCellRenderer(new HistoryRenderer());
        vaultList.setFixedCellHeight(53);
        vaultList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int index = vaultList.locationToIndex(e.getPoint());
                if (e.getClickCount() == 2 && index >= 0 && vaultList.getCellBounds(index, index).contains(e.getPoint())) {
                    editVault(index);
                }
            }
        });
        JScrollPane scroll = new JScrollPane(vaultList);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        left.add(scroll, BorderLayout.CENTER);

        JButton edit = new JButton("Edit…");
        edit.addActionListener(e -> editSelectedVault());
        JButton remove = new JButton("Remove");
        remove.addActionListener(e -> removeSelectedVault());
        JButton up = new JButton("Up");
        up.addActionListener(e -> swap(-1));
        JButton down = new JButton("Down");
        down.addActionListener(e -> swap(1));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        actions.setOpaque(false);
        actions.add(edit);
        actions.add(up);
        actions.add(down);
        actions.add(remove);

        JButton recover = new JButton("Recover offset");
        recover.addActionListener(e -> runRecovery());
        JPanel footer = new JPanel(new BorderLayout(0, 8));
        footer.setOpaque(false);
        footer.add(actions, BorderLayout.NORTH);
        footer.add(recover, BorderLayout.SOUTH);
        left.add(footer, BorderLayout.SOUTH);

        Runnable refresh = () -> {
            int total = vaultModel.size();
            int selected = vaultList.getSelectedIndex();
            count.setText(total == 0 ? "Log your drops in opening order" : total + (total == 1 ? " vault · oldest first" : " vaults · oldest first"));
            edit.setEnabled(selected >= 0);
            remove.setEnabled(selected >= 0);
            up.setEnabled(selected > 0);
            down.setEnabled(selected >= 0 && selected < total - 1);
            recover.setEnabled(total > 0);
        };
        vaultList.addListSelectionListener(e -> refresh.run());
        vaultModel.addListDataListener(new ListDataListener() {
            @Override public void intervalAdded(ListDataEvent e) { refresh.run(); }
            @Override public void intervalRemoved(ListDataEvent e) { refresh.run(); }
            @Override public void contentsChanged(ListDataEvent e) { refresh.run(); }
        });
        vaultList.getInputMap().put(KeyStroke.getKeyStroke("F2"), "editVault");
        vaultList.getActionMap().put("editVault", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { editSelectedVault(); }
        });
        refresh.run();
        return left;
    }

    private void editSelectedVault() {
        int index = vaultList.getSelectedIndex();
        if (index >= 0) editVault(index);
    }

    private void editVault(int index) {
        String initial = index < 0 ? null : vaultModel.get(index);
        int number = index < 0 ? vaultModel.size() + 1 : index + 1;
        String result = new VaultLootEditor(this, number, initial).showEditor();
        if (result == null || result.equals(initial)) return;
        if (index < 0) {
            index = vaultModel.size();
            vaultModel.addElement(result);
        } else {
            vaultModel.set(index, result);
        }
        vaultList.setSelectedIndex(index);
        vaultList.ensureIndexIsVisible(index);
        invalidateRecovery();
    }

    private void removeSelectedVault() {
        int selected = vaultList.getSelectedIndex();
        if (selected < 0) return;
        vaultModel.remove(selected);
        if (!vaultModel.isEmpty()) vaultList.setSelectedIndex(Math.min(selected, vaultModel.size() - 1));
        invalidateRecovery();
    }

    private static List<String> lootLabels(String line) {
        return VaultLoot.split(line).stream().map(VaultLoot::display).toList();
    }

    private static final class HistoryRenderer extends JPanel implements ListCellRenderer<String> {
        private final JLabel title = new JLabel();
        private final JLabel loot = new JLabel();

        HistoryRenderer() {
            super(new GridLayout(2, 1));
            title.setFont(title.getFont().deriveFont(Font.BOLD));
            setBorder(new EmptyBorder(5, 8, 5, 8));
            title.putClientProperty("html.disable", true);
            loot.putClientProperty("html.disable", true);
            add(title);
            add(loot);
        }

        @Override public Component getListCellRendererComponent(JList<? extends String> list, String value,
                int index, boolean selected, boolean focused) {
            List<String> items = lootLabels(value);
            String preview = String.join("  ·  ", items.stream().limit(2).toList());
            if (items.size() > 2) preview += "  ·  +" + (items.size() - 2) + " more";
            title.setText("Vault " + (index + 1) + "  ·  " + items.size() + (items.size() == 1 ? " entry" : " entries"));
            loot.setText(preview);
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            Color foreground = selected ? list.getSelectionForeground() : list.getForeground();
            title.setForeground(foreground);
            loot.setForeground(foreground);
            return this;
        }
    }

    private void swap(int delta) {
        int i = vaultList.getSelectedIndex();
        int j = i + delta;
        if (i < 0 || j < 0 || j >= vaultModel.size()) return;
        String a = vaultModel.get(i);
        vaultModel.set(i, vaultModel.get(j));
        vaultModel.set(j, a);
        vaultList.setSelectedIndex(j);
        invalidateRecovery();
    }

    private void invalidateRecovery() {
        recoveredOffset = null;
        recoveryRevision++;
        useOffsetCheck.setSelected(false);
        useOffsetCheck.setEnabled(false);
        invalidateForecast();
        status("Ready.");
    }

    private void invalidateForecast() {
        forecastRevision++;
        outputArea.setText("");
        forecastIndicator.setText("Choose Find my loot to see a forecast.");
    }

    private void seedChanged() {
        invalidateRecovery();
        String value = seedField.getText().trim();
        if (value.isEmpty()) {
            seedIndicator.setText("Enter a world seed");
            seedIndicator.setForeground(SECONDARY);
            return;
        }
        try {
            OminousVaultPredictor.parseSeedString(value);
            seedIndicator.setText("Seed accepted");
            seedIndicator.setForeground(new Color(0x176547));
        } catch (NumberFormatException ex) {
            seedIndicator.setText("Enter a valid whole number or 0x hex seed");
            seedIndicator.setForeground(new Color(0xA12525));
        }
    }

    private void runRecovery() {
        Long seed = parseSeed();
        if (seed == null || vaultModel.isEmpty()) return;
        invalidateRecovery();
        long revision = recoveryRevision;
        List<List<String>> known = Collections.list(vaultModel.elements()).stream().map(VaultLoot::split).toList();
        status("Recovering offset...");
        SwingWorker<Integer, Void> w = new SwingWorker<>() {
            @Override protected Integer doInBackground() {
                return OminousVaultPredictor.recoverOffset(
                        OminousVaultPredictor.VaultRng.fromSeed(seed), known, 100_000);
            }
            @Override protected void done() {
                if (revision != recoveryRevision) return;
                try {
                    int off = get();
                    if (off == OminousVaultPredictor.AMBIGUOUS_OFFSET) {
                        status("Multiple offsets matched. Add more consecutive vault results.");
                    } else if (off == OminousVaultPredictor.NO_OFFSET) {
                        status("No offset matched - check order / solo-consumption assumption.");
                        JOptionPane.showMessageDialog(VaultPredictorGui.this,
                                "No consistent offset found within 100,000.\n\n" +
                                "Common causes:\n" +
                                "- vault entries not in the exact order you opened them\n" +
                                "- another player opened an ominous vault in between\n" +
                                "- a missing or incorrect loot entry\n" +
                                "- server loot or random sequence settings differ from vanilla 1.21.11");
                    } else {
                        recoveredOffset = off;
                        recoveryRevision++;
                        invalidateForecast();
                        useOffsetCheck.setEnabled(true);
                        useOffsetCheck.setSelected(true);
                        status("Recovered: " + off + " vaults before your first entry; "
                                + (off + vaultModel.size()) + " opened through your last entry.");
                        forecastIndicator.setText("Position recovered. Choose Find my loot to forecast.");
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
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 15));
        outputArea.setForeground(TEXT);
        outputArea.setBorder(new EmptyBorder(14, 16, 14, 16));
        outputArea.setLineWrap(true);
        outputArea.setWrapStyleWord(true);

        JPanel forecast = new JPanel(new BorderLayout(4, 4));
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(new JLabel("Search up to"));
        bar.add(forecastCapSpinner);
        bar.add(new JLabel("upcoming vaults"));
        JButton go = VaultLootEditor.primary("Find my loot!");
        go.addActionListener(e -> runForecast());
        bar.add(go);
        forecastIndicator.setForeground(TEXT);
        forecastIndicator.setBorder(new EmptyBorder(6, 10, 8, 10));
        JPanel forecastHeader = new JPanel(new BorderLayout());
        forecastHeader.add(bar, BorderLayout.NORTH);
        forecastHeader.add(forecastIndicator, BorderLayout.SOUTH);
        forecast.add(forecastHeader, BorderLayout.NORTH);
        forecast.add(new JScrollPane(outputArea), BorderLayout.CENTER);
        tabs.addTab("Forecast", forecast);

        JTextArea stats = new JTextArea(buildStatsText());
        stats.setEditable(false);
        stats.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 15));
        stats.setBorder(new EmptyBorder(14, 16, 14, 16));
        tabs.addTab("Odds & statistics", new JScrollPane(stats));
        return tabs;
    }

    private String buildStatsText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Per-vault probabilities from the verified 1.21.11 tables\n");
        sb.append("======================================================\n\n");
        sb.append("UNIQUE pool items (single slot per vault):\n");
        row(sb, "heavy_core", 0.75 * 1 / 10.0);
        row(sb, "enchanted_golden_apple", 0.75 * 3 / 10.0);
        row(sb, "flow_armor_trim_smithing_template", 0.75 * 3 / 10.0);
        row(sb, "flow_banner_pattern", 0.75 * 2 / 10.0);
        row(sb, "music_disc_creator", 0.75 * 1 / 10.0);
        sb.append("\nRARE-table items (exact; vault fires rare branch with p=0.8):\n");
        int[][] rareW = {{5, 29}, {4, 29}, {4, 29}, {3, 29}, {3, 29}, {3, 29}, {2, 29}, {2, 29}, {2, 29}, {1, 29}};
        String[] rareNames = {"emerald_block", "iron_block", "crossbow(any enchant)", "golden_apple",
                "diamond_axe(any enchant)", "diamond_chestplate(any enchant)",
                "book(one of 5 enchants)", "book(breach/density)", "book[wind_burst]", "diamond_block"};
        for (int i = 0; i < rareNames.length; i++) row(sb, rareNames[i], 0.8 * rareW[i][0] / (double) rareW[i][1]);
        sb.append("\nCOMMON stackables (approx: ignores multi-roll overlap;\n");
        sb.append("p >= value shown since pool 2 rolls 1..3 extra times):\n");
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
        int observedVaults = vaultModel.size();
        long revision = recoveryRevision;
        long forecastRun = ++forecastRevision;
        outputArea.setText("");
        status("Simulating...");
        forecastIndicator.setText("Searching upcoming vaults...");
        SwingWorker<String, Void> w = new SwingWorker<>() {
            @Override protected String doInBackground() {
                OminousVaultPredictor.VaultRng rng = OminousVaultPredictor.VaultRng.fromSeed(seed);
                long skip = useRec ? (long) off + observedVaults : 0L;
                for (long i = 0; i < skip; i++) OminousVaultPredictor.simulateVault(rng);
                StringBuilder sb = new StringBuilder();
                sb.append("Using seed ").append(seed).append(useRec ? (" + recovered offset " + off) : " (offset 0)").append("\n");
                if (!useRec && off == null && observedVaults > 0) {
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
                if (revision != recoveryRevision || forecastRun != forecastRevision) return;
                try {
                    outputArea.setText(get());
                    outputArea.setCaretPosition(0);
                    forecastIndicator.setText("Forecast ready. The result and vault contents are below.");
                    status("Forecast complete.");
                } catch (Exception ex) {
                    forecastIndicator.setText("Forecast failed. Check the status message.");
                    status("Forecast failed: " + ex.getMessage());
                }
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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new VaultPredictorGui().setVisible(true));
    }
}
