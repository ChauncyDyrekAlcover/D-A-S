package ui;

import model.Account;
import service.AccountService;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

/** Module B: Chart of Accounts. */
public class AccountsPanel extends Page {
    private final AccountService service = new AccountService();
    private final DataTable table = new DataTable();
    private final JTextField search = Theme.field(18);
    private final JCheckBox showInactive = new JCheckBox("Show inactive", true);

    public AccountsPanel() {
        super("Chart of Accounts", "The list of accounts used to record transactions. Accounts with history can be deactivated, never deleted.");
        showInactive.setOpaque(false);
        JPanel bar = Theme.toolbar();
        bar.add(Theme.fieldLabel("SEARCH"));
        bar.add(search);
        bar.add(showInactive);
        var add = Theme.button("Add Account", Theme.Kind.PRIMARY);
        var edit = Theme.button("Edit", Theme.Kind.SECONDARY);
        var toggle = Theme.button("Deactivate / Activate", Theme.Kind.SECONDARY);
        var delete = Theme.button("Delete (unused only)", Theme.Kind.DANGER);
        bar.add(add);
        bar.add(edit);
        bar.add(toggle);
        bar.add(delete);
        body.add(bar, BorderLayout.NORTH);
        body.add(table, BorderLayout.CENTER);
        table.setColumnWidths(90, 260, 130, 120, 300, 90);

        search.getDocument().addDocumentListener((SimpleDocListener) this::refresh);
        showInactive.addActionListener(e -> refresh());
        add.addActionListener(e -> Dialogs.guard(this, () -> openForm(null)));
        edit.addActionListener(e -> Dialogs.guard(this, this::editSelected));
        table.onDoubleClick(id -> Dialogs.guard(this, this::editSelected));
        toggle.addActionListener(e -> Dialogs.guard(this, this::toggleSelected));
        delete.addActionListener(e -> Dialogs.guard(this, this::deleteSelected));
    }

    @Override
    public void refresh() {
        Dialogs.guard(this, () -> {
            List<Object[]> rows = new ArrayList<>();
            List<Integer> ids = new ArrayList<>();
            for (Account a : service.list(search.getText(), showInactive.isSelected())) {
                rows.add(new Object[]{a.number(), a.name(), a.type().toString(), a.normal().name(),
                    a.description(), a.active() ? "Active" : "Inactive"});
                ids.add(a.id());
            }
            table.setData(new String[]{"Number", "Account Name", "Type", "Normal Balance", "Description", "Status"}, rows,
                    ids, List.of());
        });
    }

    private Account selected() {
        int id = table.selectedId();
        if (id < 0) {
            throw new util.AccountingException("Please select an account in the table first.");
        }
        return service.get(id);
    }

    private void editSelected() {
        openForm(selected());
    }

    private void toggleSelected() {
        Account a = selected();
        service.setActive(a.id(), !a.active());
        refresh();
    }

    private void deleteSelected() {
        Account a = selected();
        if (Dialogs.confirm(this, "Delete account", "Delete account " + a + "?\nThis only works for accounts that were never used.")) {
            service.delete(a.id());
            refresh();
        }
    }

    private void openForm(Account existing) {
        JTextField number = Theme.field(12);
        JTextField name = Theme.field(24);
        JComboBox<Account.Type> type = Theme.combo(Account.Type.values());
        JComboBox<Account.Normal> normal = Theme.combo(Account.Normal.values());
        JTextField description = Theme.field(24);
        if (existing != null) {
            number.setText(existing.number());
            name.setText(existing.name());
            type.setSelectedItem(existing.type());
            normal.setSelectedItem(existing.normal());
            description.setText(existing.description());
        } else {
            normal.setSelectedItem(((Account.Type) type.getSelectedItem()).natural());
            type.addActionListener(e -> normal.setSelectedItem(((Account.Type) type.getSelectedItem()).natural()));
        }
        JPanel form = Forms.panel();
        form.setPreferredSize(new Dimension(430, 250));
        Forms.row(form, 0, "ACCOUNT NUMBER", number);
        Forms.row(form, 1, "ACCOUNT NAME", name);
        Forms.row(form, 2, "ACCOUNT TYPE", type);
        Forms.row(form, 3, "NORMAL BALANCE", normal);
        Forms.row(form, 4, "DESCRIPTION (optional)", description);
        Forms.row(form, 5, "", Theme.label("Tip: contra accounts (e.g. Accumulated Depreciation) use the opposite normal balance.", 11, false, Theme.MUTED));
        while (true) {
            int choice = JOptionPane.showConfirmDialog(this, form, existing == null ? "Add Account" : "Edit Account",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
            try {
                Account input = new Account(existing == null ? 0 : existing.id(), number.getText(), name.getText(),
                        (Account.Type) type.getSelectedItem(), (Account.Normal) normal.getSelectedItem(),
                        description.getText(), true);
                if (existing == null) {
                    service.create(input);
                } else {
                    service.update(input);
                }
                refresh();
                return;
            } catch (util.AccountingException ex) {
                Dialogs.error(this, ex.getMessage());
            }
        }
    }
}
