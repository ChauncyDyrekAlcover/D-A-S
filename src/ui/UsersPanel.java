package ui;

import model.User;
import service.AuthService;
import util.AccountingException;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

/** Administrator-only screen: manage who can sign in and what they may do. */
public class UsersPanel extends Page {
    private final AuthService auth = new AuthService();
    private final DataTable table = new DataTable();
    private List<User> users = new ArrayList<>();

    public UsersPanel() {
        super("Users", "Admin: full access.  Accountant: records, posts and reverses entries.  Viewer: read-only.");
        JPanel bar = Theme.toolbar();
        JButton role = Theme.button("Change Role", Theme.Kind.PRIMARY);
        JButton toggle = Theme.button("Deactivate / Activate", Theme.Kind.SECONDARY);
        JButton reset = Theme.button("Reset Password", Theme.Kind.SECONDARY);
        bar.add(role);
        bar.add(toggle);
        bar.add(reset);
        bar.add(Theme.label("New people create their own account with Sign Up (they start as Accountants).", 12, false, Theme.MUTED));
        body.add(bar, BorderLayout.NORTH);
        body.add(table, BorderLayout.CENTER);
        table.setColumnWidths(160, 240, 120, 100, 180);
        role.addActionListener(e -> Dialogs.guard(this, this::changeRole));
        toggle.addActionListener(e -> Dialogs.guard(this, this::toggle));
        reset.addActionListener(e -> Dialogs.guard(this, this::resetPassword));
    }

    @Override
    public void refresh() {
        Dialogs.guard(this, () -> {
            users = auth.listUsers();
            List<Object[]> rows = new ArrayList<>();
            List<Integer> ids = new ArrayList<>();
            for (User u : users) {
                rows.add(new Object[]{u.username(), u.fullName(), u.role().name(), u.active() ? "Active" : "Inactive", u.createdAt()});
                ids.add(u.id());
            }
            table.setData(new String[]{"Username", "Full Name", "Role", "Status", "Created"}, rows, ids, List.of());
        });
    }

    private User selected() {
        int id = table.selectedId();
        for (User u : users) {
            if (u.id() == id) {
                return u;
            }
        }
        throw new AccountingException("Please select a user in the table first.");
    }

    private void changeRole() {
        User u = selected();
        JComboBox<User.Role> box = Theme.combo(User.Role.values());
        box.setSelectedItem(u.role());
        if (JOptionPane.showConfirmDialog(this, box, "Role for " + u.username(), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            auth.setRole(u.id(), (User.Role) box.getSelectedItem());
            refresh();
        }
    }

    private void toggle() {
        User u = selected();
        auth.setActive(u.id(), !u.active());
        refresh();
    }

    private void resetPassword() {
        User u = selected();
        JPasswordField pw = new JPasswordField(20);
        pw.setPreferredSize(new Dimension(260, 32));
        if (JOptionPane.showConfirmDialog(this, pw, "New password for " + u.username() + " (8+ characters, letter and number)",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            auth.resetPassword(u.id(), pw.getPassword());
            Dialogs.info(this, "Password changed for " + u.username() + ".");
        }
    }
}
