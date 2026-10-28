package ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;

/** Colours, fonts and small widget factories, so every screen looks the same. */
public final class Theme {
    public static final Color NAVY = new Color(0x16213E);
    public static final Color NAVY_LIGHT = new Color(0x24345F);
    public static final Color ACCENT = new Color(0x2F6FED);
    public static final Color ACCENT_DARK = new Color(0x2457C5);
    public static final Color BG = new Color(0xF3F5F9);
    public static final Color CARD = Color.WHITE;
    public static final Color BORDER = new Color(0xDDE2EC);
    public static final Color TEXT = new Color(0x1F2937);
    public static final Color MUTED = new Color(0x6B7280);
    public static final Color GREEN = new Color(0x15803D);
    public static final Color GREEN_BG = new Color(0xDCFCE7);
    public static final Color AMBER = new Color(0xB45309);
    public static final Color AMBER_BG = new Color(0xFEF3C7);
    public static final Color RED = new Color(0xB91C1C);
    public static final Color RED_BG = new Color(0xFEE2E2);
    public static final Color ROW_ALT = new Color(0xF7F9FC);
    public static final Color HEADER_BG = new Color(0xEBF0F8);

    private static final String FONT = "Segoe UI";

    private Theme() {
    }

    public static Font font(int style, int size) {
        return new Font(FONT, style, size);
    }

    /** Call once before creating any window. */
    public static void install() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // the default look is fine
        }
        Font base = font(Font.PLAIN, 13);
        for (String key : new String[]{"Label.font", "Button.font", "TextField.font", "ComboBox.font", "TextArea.font",
            "Table.font", "CheckBox.font", "OptionPane.messageFont", "OptionPane.buttonFont", "List.font",
            "FormattedTextField.font", "TabbedPane.font"}) {
            UIManager.put(key, base);
        }
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("OptionPane.background", Color.WHITE);
        UIManager.put("Panel.background", Color.WHITE);
    }

    public static JLabel label(String text, int size, boolean bold, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(font(bold ? Font.BOLD : Font.PLAIN, size));
        l.setForeground(color);
        return l;
    }

    public static JLabel fieldLabel(String text) {
        return label(text, 12, true, MUTED);
    }

    public static Border pad(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(top, left, bottom, right);
    }

    /** White rounded-looking panel with a thin border. */
    public static JPanel card(LayoutManager layout) {
        JPanel p = new JPanel(layout);
        p.setBackground(CARD);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER), pad(12, 14, 12, 14)));
        return p;
    }

    public static JPanel toolbar() {
        return card(new WrapLayout(FlowLayout.LEFT, 10, 4));
    }

    public static JPanel row() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        p.setOpaque(false);
        return p;
    }

    public static JTextField field(int columns) {
        JTextField f = new JTextField(columns);
        f.setFont(font(Font.PLAIN, 13));
        f.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER), pad(5, 8, 5, 8)));
        return f;
    }

    public static <T> JComboBox<T> combo(T[] items) {
        JComboBox<T> c = new JComboBox<>(items);
        c.setFont(font(Font.PLAIN, 13));
        c.setBackground(Color.WHITE);
        c.setPreferredSize(new Dimension(Math.max(150, c.getPreferredSize().width), 32));
        return c;
    }

    public static void styleField(JComponent c) {
        c.setFont(font(Font.PLAIN, 13));
        c.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER), pad(5, 8, 5, 8)));
    }

    public enum Kind { PRIMARY, SECONDARY, SUCCESS, DANGER }

    public static JButton button(String text, Kind kind) {
        return new RoundButton(text, kind);
    }

    /** A flat, rounded button that looks identical on Windows, Mac and Linux. */
    private static final class RoundButton extends JButton {
        private final Kind kind;

        RoundButton(String text, Kind kind) {
            super(text);
            this.kind = kind;
            setFont(font(Font.BOLD, 13));
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(pad(8, 16, 8, 16));
            setForeground(kind == Kind.SECONDARY ? ACCENT_DARK : Color.WHITE);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = switch (kind) {
                case PRIMARY -> ACCENT;
                case SUCCESS -> GREEN;
                case DANGER -> RED;
                case SECONDARY -> Color.WHITE;
            };
            if (!isEnabled()) {
                base = new Color(0xE5E7EB);
                setForeground(new Color(0x9CA3AF));
            } else {
                setForeground(kind == Kind.SECONDARY ? ACCENT_DARK : Color.WHITE);
                if (getModel().isPressed()) {
                    base = base.darker();
                } else if (getModel().isRollover()) {
                    base = kind == Kind.SECONDARY ? new Color(0xEEF3FE) : base.brighter();
                }
            }
            g2.setColor(base);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            if (kind == Kind.SECONDARY) {
                g2.setColor(isEnabled() ? new Color(0xB9C7E6) : BORDER);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** A small coloured "pill" label, used for statuses and balance indicators. */
    public static JLabel pill(String text, Color fg, Color bg) {
        JLabel l = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setOpaque(false);
        l.setBackground(bg);
        l.setForeground(fg);
        l.setFont(font(Font.BOLD, 12));
        l.setBorder(pad(4, 12, 4, 12));
        return l;
    }

    public static void setPill(JLabel pill, String text, Color fg, Color bg) {
        pill.setText(text);
        pill.setForeground(fg);
        pill.setBackground(bg);
        pill.repaint();
    }

    public static JPanel north(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(c, BorderLayout.NORTH);
        return p;
    }
}
