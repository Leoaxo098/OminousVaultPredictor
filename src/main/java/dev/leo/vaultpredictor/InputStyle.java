package dev.leo.vaultpredictor;

import java.awt.Color;
import java.awt.Font;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import javax.swing.BorderFactory;
import javax.swing.text.JTextComponent;

final class InputStyle {
    private InputStyle() {}

    static void apply(JTextComponent field) {
        field.setFont(field.getFont().deriveFont(Font.PLAIN, 15f));
        field.setForeground(new Color(0x18332C));
        field.setBackground(Color.WHITE);
        field.setCaretColor(new Color(0x176547));
        setBorder(field, false);
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { setBorder(field, true); }
            @Override public void focusLost(FocusEvent e) { setBorder(field, false); }
        });
    }

    private static void setBorder(JTextComponent field, boolean focused) {
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(focused ? 0x176547 : 0x82988D), 2),
                BorderFactory.createEmptyBorder(5, 7, 5, 7)));
    }
}

