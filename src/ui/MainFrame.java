package ui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.LinkedHashMap;
import java.util.Map;

/** The main window: sidebar navigation on the left, the selected screen on the right. */
public class MainFrame extends JFrame implements Navigator {
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, NavButton> buttons = new LinkedHashMap<>();
    private String current = "dashboard";
    private final Runnable onLogout;
    private final service.AuthService auth = new service.AuthService();

    public MainFrame(Runnable onLogout) {
        super("Digital Accounting Process Automation System");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(1380, 840);
        setMinimumSize(new Dimension(1100, 680));
        setLocationRelativeTo(null);
        this.onLogout = onLogout;

        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBackground(Theme.NAVY);
        sidebar.setBorder(Theme.pad(18, 12, 18, 12));
        sidebar.setPreferredSize(new Dimension(236, 100));
        JLabel brand = Theme.label("DAPAS", 22, true, Color.WHITE);
        JLabel tag = Theme.label("Digital Accounting", 12, false, new Color(0xA8B3CF));
        brand.setAlignmentX(LEFT_ALIGNMENT);
        tag.setAlignmentX(LEFT_ALIGNMENT);
        sidebar.add(brand);
        sidebar.add(tag);
        sidebar.add(javax.swing.Box.createVerticalStrut(18));

        add("dashboard", "Dashboard", new DashboardPanel(this), sidebar);
        add("accounts", "Chart of Accounts", new AccountsPanel(), sidebar);
        add("journal", "General Journal", new JournalPanel(false, this), sidebar);
        add("ledger", "General Ledger", new LedgerPanel(), sidebar);
        add("columnar", "Columnar Journals", new ColumnarPanel(), sidebar);
        add("trial", "Trial Balance", new TrialBalancePanel(), sidebar);
        add("adjusting", "Adjusting Entries", new JournalPanel(true, this), sidebar);
        add("statements", "Financial Statements", new StatementsPanel(), sidebar);
        add("periods", "Accounting Periods", new PeriodsPanel(this), sidebar);
        add("audit", "Audit Trail", new AuditPanel(), sidebar);
        add("settings", "Settings", new SettingsPanel(this), sidebar);
        if ("ADMIN".equals(util.Session.role())) {
            add("users", "Users", new UsersPanel(), sidebar);
        }
        sidebar.add(javax.swing.Box.createVerticalGlue());
        String who = util.Session.fullName() == null ? util.Session.userName() : util.Session.fullName();
        JLabel user = Theme.label(who, 13, true, Color.WHITE);
        JLabel role = Theme.label(util.Session.role() == null ? "" : util.Session.role().toLowerCase(), 11, false, new Color(0xA8B3CF));
        user.setAlignmentX(LEFT_ALIGNMENT);
        role.setAlignmentX(LEFT_ALIGNMENT);
        sidebar.add(user);
        sidebar.add(role);
        sidebar.add(javax.swing.Box.createVerticalStrut(8));
        JButton logout = Theme.button("Log Out", Theme.Kind.SECONDARY);
        logout.setAlignmentX(LEFT_ALIGNMENT);
        logout.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        logout.addActionListener(e -> {
            if (Dialogs.confirm(this, "Log out", "Log out of the accounting system?")) {
                auth.logout();
                dispose();
                this.onLogout.run();
            }
        });
        sidebar.add(logout);

        content.setBackground(Theme.BG);
        JPanel root = new JPanel(new BorderLayout());
        root.add(sidebar, BorderLayout.WEST);
        root.add(content, BorderLayout.CENTER);
        setContentPane(root);
        goTo("dashboard");
    }

    private void add(String key, String title, Page page, JPanel sidebar) {
        pages.put(key, page);
        content.add(page, key);
        NavButton b = new NavButton(title);
        b.setAlignmentX(LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        b.addActionListener(e -> goTo(key));
        buttons.put(key, b);
        sidebar.add(b);
        sidebar.add(javax.swing.Box.createVerticalStrut(3));
    }

    @Override
    public void goTo(String page) {
        if (!pages.containsKey(page)) {
            return;
        }
        current = page;
        cards.show(content, page);
        for (Map.Entry<String, NavButton> e : buttons.entrySet()) {
            e.getValue().setSelectedPage(e.getKey().equals(page));
        }
        pages.get(page).refresh();
    }

    /** A sidebar button with a highlighted background when selected. */
    private static final class NavButton extends JButton {
        private boolean selectedPage;

        NavButton(String text) {
            super(text);
            setFont(Theme.font(Font.PLAIN, 14));
            setForeground(new Color(0xD5DBEE));
            setHorizontalAlignment(LEFT);
            setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 10));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        void setSelectedPage(boolean selected) {
            this.selectedPage = selected;
            setForeground(selected ? Color.WHITE : new Color(0xD5DBEE));
            setFont(Theme.font(selected ? Font.BOLD : Font.PLAIN, 14));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (selectedPage) {
                g2.setColor(Theme.NAVY_LIGHT);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.setColor(Theme.ACCENT);
                g2.fillRoundRect(0, 8, 4, getHeight() - 16, 4, 4);
            } else if (getModel().isRollover()) {
                g2.setColor(new Color(0x1E2B4E));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
