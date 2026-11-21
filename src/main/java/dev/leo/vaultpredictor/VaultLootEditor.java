package dev.leo.vaultpredictor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import static dev.leo.vaultpredictor.VaultLoot.*;

final class VaultLootEditor extends JDialog {
    private static final Color PAPER = new Color(0xF4F6F4);
    private static final Color INK = new Color(0x18332C);
    private static final Color MUTED = new Color(0x50685F);
    private static final Color BORDER = new Color(0xD6E0DA);
    private static final Color ACCENT = new Color(0x176547);
    private static final int MAX_QUANTITY = 64;

    private static final List<Item> QUICK_PICKS = List.of(Item.EMERALD, Item.WIND_CHARGE,
            Item.DIAMOND, Item.SLOWNESS_ARROW, Item.OMINOUS_BOTTLE, Item.HEAVY_CORE,
            Item.IRON_BLOCK, Item.GOLDEN_APPLE);

    private final JTextField search = new JTextField();
    private final JPanel matchesPanel = new JPanel(new GridLayout(0, 2, 6, 6));
    private final JPanel quickPicks = section("Quick picks", itemButtons(QUICK_PICKS, true));
    private final JLabel resultsHeading = heading("More items", 14);
    private final JLabel searchIndicator = caption("Type an item or enchantment name");
    private final DefaultListModel<String> loot = new DefaultListModel<>();
    private final JList<String> contents = new JList<>(loot);
    private final JPanel details = new JPanel(new BorderLayout(0, 8));
    private final JLabel detailTitle = heading("", 16);
    private final JLabel feedback = caption(" ");
    private final JPanel fields = new JPanel(new BorderLayout(0, 8));
    private final JButton save;
    private final JButton applyItem = primary("Add item");
    private final JButton editItem = new JButton("Edit item");
    private final JButton removeItem = new JButton("Remove item");
    private final JSpinner quantity = new JSpinner(new SpinnerNumberModel(1, 1, MAX_QUANTITY, 1));
    private final JFormattedTextField quantityInput = ((JSpinner.DefaultEditor) quantity.getEditor()).getTextField();
    private final JComboBox<String> bottles = new JComboBox<>(BOTTLE_LEVELS.toArray(String[]::new));
    private final JComboBox<Enchantment> bookEnchantment = new JComboBox<>();
    private final JComboBox<Integer> bookLevel = new JComboBox<>();
    private final Map<Enchantment, JComboBox<Integer>> gearLevels = new LinkedHashMap<>();

    private Item currentItem;
    private int editingIndex = -1;
    private List<Item> matches = List.of();
    private String result;

    VaultLootEditor(JFrame owner, int vaultNumber, String initialLoot) {
        super(owner, (initialLoot == null ? "Log vault " : "Edit vault ") + vaultNumber, true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        save = primary(initialLoot == null ? "Save vault" : "Save changes");
        if (initialLoot != null) split(initialLoot).forEach(loot::addElement);

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(PAPER);
        root.setBorder(new EmptyBorder(17, 20, 16, 20));
        setContentPane(root);
        InputStyle.apply(search);
        InputStyle.apply(quantityInput);
        bookEnchantment.addActionListener(e -> refreshBookLevels());
        quantityInput.setColumns(5);
        quantityInput.setFocusLostBehavior(JFormattedTextField.PERSIST);
        quantityInput.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "commitLoot");
        quantityInput.getActionMap().put("commitLoot", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { commitItem(); }
        });
        root.add(heading(getTitle(), 25), BorderLayout.NORTH);

        JPanel body = new JPanel(new GridLayout(1, 2, 14, 0));
        body.setOpaque(false);
        body.add(buildPicker());
        body.add(buildLootPanel());
        root.add(body, BorderLayout.CENTER);

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        save.addActionListener(e -> {
            result = String.join("; ", Collections.list(loot.elements()));
            dispose();
        });
        JPanel footerButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footerButtons.setOpaque(false);
        footerButtons.add(cancel);
        footerButtons.add(save);
        root.add(footerButtons, BorderLayout.SOUTH);

        bind(getRootPane(), "ctrl F", "searchLoot", this::focusSearch);
        bind(getRootPane(), "ESCAPE", "cancelLoot", () -> {
            if (currentItem != null) clearDetails();
            else dispose();
        });
        getRootPane().setDefaultButton(save);
        refreshPicker();
        refreshActions();
        setMinimumSize(new Dimension(840, 555));
        setSize(940, 650);
        setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(this::focusSearch);
    }

    String showEditor() {
        setVisible(true);
        return result;
    }

    private JPanel buildPicker() {
        JPanel panel = card();
        JPanel searchPanel = new JPanel(new BorderLayout(0, 5));
        searchPanel.setOpaque(false);
        JLabel searchLabel = caption("Search items or enchantments");
        searchLabel.setLabelFor(search);
        searchPanel.add(searchLabel, BorderLayout.NORTH);
        searchPanel.add(search, BorderLayout.CENTER);
        searchPanel.add(searchIndicator, BorderLayout.SOUTH);
        panel.add(searchPanel, BorderLayout.NORTH);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshPicker(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshPicker(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshPicker(); }
        });
        search.addActionListener(e -> {
            if (!search.getText().isBlank() && !matches.isEmpty()) choose(matches.getFirst());
        });

        JPanel sections = new JPanel();
        sections.setBackground(Color.WHITE);
        sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
        sections.add(quickPicks);
        matchesPanel.setOpaque(false);
        sections.add(section(resultsHeading, matchesPanel));
        JPanel topAligned = new PickerPane();
        topAligned.setBackground(Color.WHITE);
        topAligned.add(sections, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(topAligned);
        scroll.setBorder(new EmptyBorder(0, 0, 0, 0));
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(caption("Press Enter to choose the first result."), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel itemButtons(List<Item> items, boolean quick) {
        JPanel grid = new JPanel(new GridLayout(0, 2, 6, 6));
        grid.setOpaque(false);
        for (Item item : items) grid.add(itemButton(item, quick));
        return grid;
    }

    private JButton itemButton(Item item, boolean quick) {
        JButton button = new JButton(item.label);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setForeground(INK);
        button.setBackground(quick ? new Color(0xECF5EF) : PAPER);
        button.setOpaque(true);
        button.setToolTipText(item.profileLabel);
        button.setMargin(new Insets(5, 8, 5, 8));
        button.addActionListener(e -> choose(item));
        return button;
    }

    private void refreshPicker() {
        String query = search.getText();
        quickPicks.setVisible(query.isBlank());
        resultsHeading.setText(query.isBlank() ? "More items" : "Search results");
        matches = Arrays.stream(Item.values()).filter(item -> item.matches(query)).toList();
        searchIndicator.setText(query.isBlank() ? "Type an item or enchantment name"
                : matches.size() + (matches.size() == 1 ? " matching item" : " matching items"));
        matchesPanel.removeAll();
        for (Item item : matches) {
            if (!query.isBlank() || !QUICK_PICKS.contains(item)) matchesPanel.add(itemButton(item, false));
        }
        if (matches.isEmpty()) matchesPanel.add(caption("No items match."));
        matchesPanel.revalidate();
        matchesPanel.repaint();
    }

    private JPanel buildLootPanel() {
        JPanel panel = card();
        JPanel front = new JPanel(new BorderLayout(0, 8));
        front.setOpaque(false);
        front.add(heading("Vault loot", 18), BorderLayout.NORTH);
        details.setBackground(new Color(0xECF5EF));
        details.setBorder(new EmptyBorder(9, 10, 9, 10));
        details.add(detailTitle, BorderLayout.NORTH);
        fields.setOpaque(false);
        details.add(fields, BorderLayout.CENTER);
        JPanel itemActions = new JPanel(new BorderLayout());
        itemActions.setOpaque(false);
        itemActions.add(feedback, BorderLayout.NORTH);
        JPanel applyButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        applyButtons.setOpaque(false);
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> clearDetails());
        applyItem.addActionListener(e -> commitItem());
        applyButtons.add(cancel);
        applyButtons.add(applyItem);
        itemActions.add(applyButtons, BorderLayout.SOUTH);
        details.add(itemActions, BorderLayout.SOUTH);
        details.setVisible(false);
        front.add(details, BorderLayout.CENTER);
        panel.add(front, BorderLayout.NORTH);

        contents.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        contents.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                putClientProperty("html.disable", true);
                super.getListCellRendererComponent(list, display((String) value), index, selected, focus);
                setBorder(new EmptyBorder(7, 8, 7, 8));
                return this;
            }
        });
        contents.addListSelectionListener(e -> refreshActions());
        contents.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int index = contents.locationToIndex(e.getPoint());
                if (e.getClickCount() == 2 && index >= 0 && contents.getCellBounds(index, index).contains(e.getPoint())) {
                    editSelected();
                }
            }
        });
        bind(contents, "F2", "editLoot", this::editSelected);
        bind(contents, "DELETE", "removeLoot", this::removeSelected);
        JScrollPane scroll = new JScrollPane(contents);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER));
        panel.add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        editItem.addActionListener(e -> editSelected());
        removeItem.addActionListener(e -> removeSelected());
        buttons.add(editItem);
        buttons.add(removeItem);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private void choose(Item item) {
        if (currentItem != null) {
            feedback.setText("Add, update, or cancel this item first.");
            return;
        }
        if (item.kind == Kind.PLAIN || item.kind == Kind.FIXED_BOOK) {
            loot.addElement(new Entry(item, 1, "", List.of()).token());
            contents.setSelectedIndex(loot.size() - 1);
            contents.ensureIndexIsVisible(loot.size() - 1);
            search.setText("");
            focusSearch();
            refreshActions();
            return;
        }
        showDetails(item, null, -1);
    }

    private void editSelected() {
        int index = contents.getSelectedIndex();
        if (index < 0 || currentItem != null) return;
        parse(loot.get(index)).ifPresent(entry -> showDetails(entry.item(), entry, index));
    }

    private void removeSelected() {
        int index = contents.getSelectedIndex();
        if (index < 0 || currentItem != null) return;
        loot.remove(index);
        if (!loot.isEmpty()) contents.setSelectedIndex(Math.min(index, loot.size() - 1));
        refreshActions();
    }

    private void showDetails(Item item, Entry entry, int index) {
        currentItem = item;
        editingIndex = index;
        feedback.setText(" ");
        detailTitle.setText(item.label);
        fields.removeAll();
        JPanel controls = new JPanel(new GridLayout(0, item.kind == Kind.GEAR ? 3 : 2, 8, 6));
        controls.setOpaque(false);
        JComponent focus = applyItem;
        switch (item.kind) {
            case STACK, BOTTLE -> {
                quantity.setValue(entry == null ? 1 : entry.quantity());
                quantityInput.setValue(quantity.getValue());
                if (item.kind == Kind.BOTTLE) {
                    bottles.setSelectedItem(entry == null ? BOTTLE_LEVELS.getFirst() : entry.bottleLevel());
                    controls.add(labeled("Bottle level", bottles));
                }
                controls.add(labeled("Quantity", quantity));
                feedback.setText("Enter a whole number from 1 to 64.");
                focus = item.kind == Kind.BOTTLE ? bottles : quantityInput;
            }
            case BOOK -> {
                bookEnchantment.setModel(new DefaultComboBoxModel<>(item.enchantments.toArray(Enchantment[]::new)));
                EnchantedLevel existing = entry == null ? null : entry.enchantments().getFirst();
                if (existing == null) item.searchedEnchantment(search.getText()).ifPresent(bookEnchantment::setSelectedItem);
                if (existing != null) bookEnchantment.setSelectedItem(existing.enchantment());
                refreshBookLevels();
                if (existing != null) bookLevel.setSelectedItem(existing.level());
                controls.add(labeled("Enchantment", bookEnchantment));
                controls.add(labeled("Level", bookLevel));
                focus = bookEnchantment;
            }
            case GEAR -> {
                gearLevels.clear();
                for (Enchantment enchantment : item.enchantments) {
                    JComboBox<Integer> level = new JComboBox<>();
                    setLevels(level, 0, enchantment.maxLevel());
                    if (entry != null) {
                        entry.enchantments().stream().filter(e -> e.enchantment().equals(enchantment))
                                .findFirst().ifPresent(e -> level.setSelectedItem(e.level()));
                    }
                    gearLevels.put(enchantment, level);
                    controls.add(labeled(enchantment.toString(), level));
                }
                focus = gearLevels.values().iterator().next();
            }
            case FIXED_BOOK, PLAIN -> controls.add(caption("This item has no extra fields."));
        }
        fields.add(controls, BorderLayout.CENTER);
        applyItem.setText(index < 0 ? "Add item" : "Update item");
        details.setVisible(true);
        details.revalidate();
        details.repaint();
        refreshActions();
        getRootPane().setDefaultButton(applyItem);
        focus.requestFocusInWindow();
        if (focus instanceof JFormattedTextField input) input.selectAll();
    }

    private void refreshBookLevels() {
        Enchantment selected = (Enchantment) bookEnchantment.getSelectedItem();
        if (selected != null) setLevels(bookLevel, 1, selected.maxLevel());
    }

    private void commitItem() {
        if (currentItem == null) return;
        int count = 1;
        String bottle = "";
        List<EnchantedLevel> enchantments = List.of();
        switch (currentItem.kind) {
            case STACK, BOTTLE -> {
                try {
                    count = Integer.parseInt(quantityInput.getText().trim());
                } catch (NumberFormatException ex) {
                    count = 0;
                }
                if (count < 1 || count > MAX_QUANTITY) {
                    feedback.setText("Enter a whole number from 1 to " + MAX_QUANTITY + ".");
                    return;
                }
                if (currentItem.kind == Kind.BOTTLE) bottle = (String) bottles.getSelectedItem();
            }
            case BOOK -> enchantments = List.of(new EnchantedLevel((Enchantment) bookEnchantment.getSelectedItem(),
                    (Integer) bookLevel.getSelectedItem()));
            case GEAR -> {
                enchantments = gearLevels.entrySet().stream().filter(e -> e.getValue().getSelectedIndex() > 0)
                        .map(e -> new EnchantedLevel(e.getKey(), (Integer) e.getValue().getSelectedItem())).toList();
                if (enchantments.isEmpty()) {
                    feedback.setText("Choose an enchantment level.");
                    return;
                }
            }
            case PLAIN, FIXED_BOOK -> { }
        }
        String token = new Entry(currentItem, count, bottle, enchantments).token();
        int index = editingIndex >= 0 ? editingIndex : loot.size();
        if (editingIndex >= 0) loot.set(editingIndex, token);
        else loot.addElement(token);
        contents.setSelectedIndex(index);
        contents.ensureIndexIsVisible(index);
        search.setText("");
        clearDetails();
    }

    private void clearDetails() {
        currentItem = null;
        editingIndex = -1;
        details.setVisible(false);
        getRootPane().setDefaultButton(save);
        refreshActions();
        focusSearch();
    }

    private void refreshActions() {
        String selected = contents.getSelectedValue();
        boolean inDetails = currentItem != null;
        save.setEnabled(!inDetails && !loot.isEmpty());
        editItem.setEnabled(!inDetails && selected != null && parse(selected).isPresent());
        removeItem.setEnabled(!inDetails && selected != null);
    }

    private void focusSearch() { search.requestFocusInWindow(); }

    private static void setLevels(JComboBox<Integer> combo, int min, int max) {
        combo.setModel(new DefaultComboBoxModel<>(IntStream.rangeClosed(min, max).boxed().toArray(Integer[]::new)));
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                String text = value instanceof Integer level ? (level == 0 ? "None" : roman(level)) : "";
                return super.getListCellRendererComponent(list, text, index, selected, focus);
            }
        });
    }

    private static JPanel labeled(String text, JComponent control) {
        JPanel row = new JPanel(new BorderLayout(0, 3));
        row.setOpaque(false);
        JLabel label = caption(text);
        label.setLabelFor(control);
        row.add(label, BorderLayout.NORTH);
        row.add(control, BorderLayout.CENTER);
        return row;
    }

    private static JPanel section(String name, JComponent content) {
        return section(heading(name, 14), content);
    }

    private static JPanel section(JLabel title, JComponent content) {
        JPanel group = new JPanel(new BorderLayout(0, 6));
        group.setOpaque(false);
        group.setBorder(new EmptyBorder(0, 0, 12, 2));
        group.add(title, BorderLayout.NORTH);
        group.add(content, BorderLayout.CENTER);
        return group;
    }

    private static JPanel card() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(12, 12, 12, 12)));
        return panel;
    }

    private static JLabel heading(String text, int pointSize) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, (float) pointSize));
        label.setForeground(INK);
        return label;
    }

    private static JLabel caption(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        return label;
    }

    static JButton primary(String text) {
        JButton button = new JButton(text);
        button.setOpaque(true);
        button.setBackground(ACCENT);
        button.setForeground(Color.WHITE);
        button.addPropertyChangeListener("enabled", e -> button.setBackground(button.isEnabled() ? ACCENT : new Color(0xE7ECE8)));
        button.setMargin(new Insets(5, 12, 5, 12));
        return button;
    }

    private static void bind(JComponent control, String key, String name, Runnable run) {
        control.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key), name);
        control.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { run.run(); }
        });
    }

    private static final class PickerPane extends JPanel implements Scrollable {
        PickerPane() { super(new BorderLayout()); }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(16, visible.height - 16);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}

