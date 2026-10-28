package ui;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/** Tiny helper for "label : field" forms. */
public final class Forms {
    private Forms() {
    }

    public static JPanel panel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(java.awt.Color.WHITE);
        return p;
    }

    public static void row(JPanel p, int row, String label, JComponent field) {
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.gridy = row;
        l.anchor = GridBagConstraints.NORTHWEST;
        l.insets = new Insets(8, 0, 2, 14);
        p.add(Theme.fieldLabel(label), l);
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 1;
        f.gridy = row;
        f.weightx = 1;
        f.fill = GridBagConstraints.HORIZONTAL;
        f.insets = new Insets(4, 0, 2, 0);
        p.add(field, f);
    }
}
