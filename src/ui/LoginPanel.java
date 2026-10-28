package ui;

import model.User;
import service.AuthService;
import util.AccountingException;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.util.function.Consumer;

/** The Sign In / Sign Up screen: a brand panel on the left, the forms on the right. */
public class LoginPanel extends JPanel {
    private final AuthService auth = new AuthService();
    private final Consumer<User> onSuccess;
    private final CardLayout cards = new CardLayout();
    private final JPanel forms = new JPanel(cards);
    private final JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));

    private final JTextField inUser = Theme.field(20);
    private final JPasswordField inPass = new JPasswordField(20);
    private final JLabel inMsg = Theme.label(" ", 12, true, Theme.RED);

    private final JTextField upName = Theme.field(20);
    private final JTextField upUser = Theme.field(20);
    private final JPasswordField upPass = new JPasswordField(20);
    private final JPasswordField upConfirm = new JPasswordField(20);
    private final JLabel upMsg = Theme.label(" ", 12, true, Theme.RED);

    public LoginPanel(Consumer<User> onSuccess) {
        this.onSuccess = onSuccess;
        for (JLabel m : new JLabel[]{inMsg, upMsg}) {
            m.setVerticalAlignment(javax.swing.SwingConstants.TOP);
            m.setPreferredSize(new Dimension(300, 38));
            m.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            m.setMinimumSize(new Dimension(300, 38));
        }
        setLayout(new BorderLayout());
        add(brand(), BorderLayout.WEST);

        JPanel right = new JPanel(new GridBagLayout());
        right.setBackground(Theme.BG);
        JPanel card = Theme.card(new BorderLayout(0, 14));
        card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.BORDER), Theme.pad(26, 30, 26, 30)));
        card.setPreferredSize(new Dimension(400, 500));
        tabs.setOpaque(false);
        card.add(tabs, BorderLayout.NORTH);
        forms.setOpaque(false);
        forms.add(signInForm(), "in");
        forms.add(signUpForm(), "up");
        card.add(forms, BorderLayout.CENTER);
        right.add(card);
        add(right, BorderLayout.CENTER);

        for (JPasswordField f : new JPasswordField[]{inPass, upPass, upConfirm}) {
            Theme.styleField(f);
        }
        inPass.addActionListener(e -> doLogin());
        inUser.addActionListener(e -> inPass.requestFocusInWindow());
        upConfirm.addActionListener(e -> doSignUp());

        if (!auth.hasUsers()) {
            show("up");
            upMsgInfo("No accounts exist yet. The account you create now becomes the administrator.");
        } else {
            show("in");
        }
    }

    private JPanel brand() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Theme.NAVY);
        p.setBorder(Theme.pad(60, 40, 40, 40));
        p.setPreferredSize(new Dimension(380, 100));
        JLabel logo = Theme.label("DAPAS", 40, true, Color.WHITE);
        JLabel name = Theme.label("Digital Accounting", 16, false, new Color(0xA8B3CF));
        JLabel name2 = Theme.label("Process Automation System", 16, false, new Color(0xA8B3CF));
        for (JLabel l : new JLabel[]{logo, name, name2}) {
            l.setAlignmentX(LEFT_ALIGNMENT);
            p.add(l);
        }
        p.add(Box.createVerticalStrut(40));
        for (String t : new String[]{"Encode a transaction once", "Ledger, journals and reports update themselves",
            "Balanced entries, audit trail, controlled closing"}) {
            JLabel l = Theme.label("<html><div style='width:230px'>\u2022  " + t + "</div></html>", 13, false, new Color(0xD5DBEE));
            l.setAlignmentX(LEFT_ALIGNMENT);
            p.add(l);
            p.add(Box.createVerticalStrut(10));
        }
        p.add(Box.createVerticalGlue());
        JLabel foot = Theme.label("Java Swing  \u2022  SQLite", 11, false, new Color(0x7F8BAA));
        foot.setAlignmentX(LEFT_ALIGNMENT);
        p.add(foot);
        return p;
    }

    private static void addField(JPanel form, String label, JComponent field) {
        JLabel l = Theme.fieldLabel(label);
        l.setAlignmentX(LEFT_ALIGNMENT);
        field.setAlignmentX(LEFT_ALIGNMENT);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        field.setPreferredSize(new Dimension(300, 36));
        form.add(l);
        form.add(Box.createVerticalStrut(4));
        form.add(field);
        form.add(Box.createVerticalStrut(12));
    }

    private JPanel signInForm() {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);
        JLabel title = Theme.label("Welcome back", 22, true, Theme.TEXT);
        JLabel sub = Theme.label("Sign in to continue to your books.", 13, false, Theme.MUTED);
        title.setAlignmentX(LEFT_ALIGNMENT);
        sub.setAlignmentX(LEFT_ALIGNMENT);
        form.add(title);
        form.add(sub);
        form.add(Box.createVerticalStrut(18));
        addField(form, "USERNAME", inUser);
        addField(form, "PASSWORD", inPass);
        JCheckBox show = new JCheckBox("Show password");
        show.setOpaque(false);
        show.setAlignmentX(LEFT_ALIGNMENT);
        char echo = inPass.getEchoChar();
        show.addActionListener(e -> inPass.setEchoChar(show.isSelected() ? (char) 0 : echo));
        form.add(show);
        form.add(Box.createVerticalStrut(10));
        inMsg.setAlignmentX(LEFT_ALIGNMENT);
        form.add(inMsg);
        form.add(Box.createVerticalStrut(8));
        JButton go = Theme.button("Sign In", Theme.Kind.PRIMARY);
        go.setAlignmentX(LEFT_ALIGNMENT);
        go.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        go.addActionListener(e -> doLogin());
        form.add(go);
        form.add(Box.createVerticalStrut(14));
        JLabel hint = Theme.label("New here? Choose the Sign Up tab to create an account.", 12, false, Theme.MUTED);
        hint.setAlignmentX(LEFT_ALIGNMENT);
        form.add(hint);
        return form;
    }

    private JPanel signUpForm() {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);
        JLabel title = Theme.label("Create an account", 22, true, Theme.TEXT);
        JLabel sub = Theme.label("Password: 8+ characters with a letter and a number.", 13, false, Theme.MUTED);
        title.setAlignmentX(LEFT_ALIGNMENT);
        sub.setAlignmentX(LEFT_ALIGNMENT);
        form.add(title);
        form.add(sub);
        form.add(Box.createVerticalStrut(14));
        addField(form, "FULL NAME", upName);
        addField(form, "USERNAME", upUser);
        addField(form, "PASSWORD", upPass);
        addField(form, "CONFIRM PASSWORD", upConfirm);
        upMsg.setAlignmentX(LEFT_ALIGNMENT);
        form.add(upMsg);
        form.add(Box.createVerticalStrut(6));
        JButton go = Theme.button("Create Account", Theme.Kind.SUCCESS);
        go.setAlignmentX(LEFT_ALIGNMENT);
        go.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        go.addActionListener(e -> doSignUp());
        form.add(go);
        return form;
    }

    private void show(String which) {
        cards.show(forms, which);
        boolean in = which.equals("in");
        tabs.removeAll();
        JButton tabIn = Theme.button("Sign In", in ? Theme.Kind.PRIMARY : Theme.Kind.SECONDARY);
        JButton tabUp = Theme.button("Sign Up", in ? Theme.Kind.SECONDARY : Theme.Kind.PRIMARY);
        tabIn.addActionListener(e -> show("in"));
        tabUp.addActionListener(e -> show("up"));
        tabs.add(tabIn);
        tabs.add(tabUp);
        tabs.revalidate();
        tabs.repaint();
        (in ? inUser : upName).requestFocusInWindow();
    }

    private void upMsgInfo(String text) {
        upMsg.setForeground(Theme.ACCENT_DARK);
        upMsg.setText("<html>" + text + "</html>");
    }

    private void doLogin() {
        try {
            User u = auth.login(inUser.getText(), inPass.getPassword());
            inPass.setText("");
            inMsg.setText(" ");
            onSuccess.accept(u);
        } catch (AccountingException ex) {
            inPass.setText("");
            inMsg.setForeground(Theme.RED);
            inMsg.setText("<html>" + ex.getMessage() + "</html>");
        }
    }

    private void doSignUp() {
        try {
            User u = auth.signUp(upUser.getText(), upName.getText(), upPass.getPassword(), upConfirm.getPassword());
            String username = u.username();
            upName.setText("");
            upUser.setText("");
            upPass.setText("");
            upConfirm.setText("");
            upMsg.setText(" ");
            inUser.setText(username);
            inPass.setText("");
            inMsg.setForeground(Theme.GREEN);
            inMsg.setText("<html>Account created (" + u.role().name().toLowerCase() + "). Please sign in.</html>");
            show("in");
            inPass.requestFocusInWindow();
        } catch (AccountingException ex) {
            upMsg.setForeground(Theme.RED);
            upMsg.setText("<html>" + ex.getMessage() + "</html>");
        }
    }
}
