package ui;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/** Base class for every screen: a title, a subtitle and a body area. */
public abstract class Page extends JPanel {
    protected final JPanel body = new JPanel(new BorderLayout(0, 12));

    protected Page(String title, String subtitle) {
        setLayout(new BorderLayout(0, 14));
        setBackground(Theme.BG);
        setBorder(Theme.pad(20, 26, 20, 26));
        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.add(Theme.label(title, 24, true, Theme.TEXT));
        head.add(javax.swing.Box.createVerticalStrut(2));
        head.add(Theme.label(subtitle, 13, false, Theme.MUTED));
        add(head, BorderLayout.NORTH);
        body.setOpaque(false);
        add(body, BorderLayout.CENTER);
    }

    /** Reloads data from the database. Called every time the screen is shown. */
    public abstract void refresh();
}
