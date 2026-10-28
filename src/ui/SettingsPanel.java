package ui;

import database.Database;
import model.Account;
import service.AccountService;
import service.BackupService;
import service.SettingsService;
import util.AccountingException;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Settings: business name, account roles, which journals are on, backup and restore. */
public class SettingsPanel extends Page {
    private final SettingsService settings = new SettingsService();
    private final AccountService accountService = new AccountService();
    private final BackupService backups = new BackupService();
    private final Navigator nav;
    private final JTextField businessName = Theme.field(28);
    private final Map<String, JComboBox<Account>> roleBoxes = new LinkedHashMap<>();
    private final JCheckBox salesOn = new JCheckBox("Use a Sales Journal (credit sales: Accounts Receivable debit, revenue credit)");
    private final JCheckBox purchasesOn = new JCheckBox("Use a Purchases Journal (purchases on account: Accounts Payable credit)");

    public SettingsPanel(Navigator nav) {
        super("Settings", "Tell the system which accounts play special roles, and manage backups of your data.");
        this.nav = nav;
        salesOn.setOpaque(false);
        purchasesOn.setOpaque(false);

        JPanel setup = Theme.card(new BorderLayout(0, 10));
        setup.add(Theme.label("Business and account mapping", 16, true, Theme.TEXT), BorderLayout.NORTH);
        JPanel form = Forms.panel();
        int row = 0;
        Forms.row(form, row++, "BUSINESS NAME", businessName);
        String[][] roles = {
            {SettingsService.CASH, "CASH ACCOUNT (drives the Cash Receipts / Disbursements journals)"},
            {SettingsService.AR, "ACCOUNTS RECEIVABLE (Sales Journal)"},
            {SettingsService.AP, "ACCOUNTS PAYABLE (Purchases Journal)"},
            {SettingsService.CAPITAL, "OWNER'S CAPITAL (receives net income when closing)"},
            {SettingsService.DRAWINGS, "OWNER'S DRAWINGS (closed to capital)"},
            {SettingsService.INCOME_SUMMARY, "INCOME SUMMARY (temporary closing account)"}
        };
        for (String[] r : roles) {
            JComboBox<Account> box = new JComboBox<>();
            box.setPreferredSize(new Dimension(320, 32));
            roleBoxes.put(r[0], box);
            Forms.row(form, row++, r[1], box);
        }
        Forms.row(form, row++, "SPECIALIZED JOURNALS", salesOn);
        Forms.row(form, row++, "", purchasesOn);
        setup.add(form, BorderLayout.CENTER);
        JPanel saveRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        saveRow.setOpaque(false);
        JButton save = Theme.button("Save Settings", Theme.Kind.PRIMARY);
        saveRow.add(save);
        setup.add(saveRow, BorderLayout.SOUTH);

        JPanel backup = Theme.card(new BorderLayout(0, 10));
        backup.add(Theme.label("Backup and restore", 16, true, Theme.TEXT), BorderLayout.NORTH);
        JPanel info = new JPanel(new BorderLayout(0, 4));
        info.setOpaque(false);
        info.add(Theme.label("Live database file: " + new File(Database.getPath()).getAbsolutePath(), 12, false, Theme.MUTED), BorderLayout.NORTH);
        info.add(Theme.label("Backups are consistent snapshots and never overwrite an existing file. Restoring first saves your current data to data/backups/.", 12, false, Theme.MUTED), BorderLayout.SOUTH);
        backup.add(info, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.setOpaque(false);
        JButton doBackup = Theme.button("Back Up Now...", Theme.Kind.SUCCESS);
        JButton doRestore = Theme.button("Restore From Backup...", Theme.Kind.DANGER);
        buttons.add(doBackup);
        buttons.add(doRestore);
        backup.add(buttons, BorderLayout.SOUTH);

        JPanel stack = new JPanel(new BorderLayout(0, 14));
        stack.setOpaque(false);
        stack.add(setup, BorderLayout.NORTH);
        stack.add(backup, BorderLayout.CENTER);
        body.add(Theme.north(stack), BorderLayout.CENTER);

        save.addActionListener(e -> Dialogs.guard(this, this::save));
        doBackup.addActionListener(e -> Dialogs.guard(this, this::backupNow));
        doRestore.addActionListener(e -> Dialogs.guard(this, this::restore));
    }

    @Override
    public void refresh() {
        Dialogs.guard(this, () -> {
            businessName.setText(settings.get(SettingsService.BUSINESS_NAME));
            salesOn.setSelected(settings.isOn(SettingsService.SALES_ON));
            purchasesOn.setSelected(settings.isOn(SettingsService.PURCHASES_ON));
            for (Map.Entry<String, JComboBox<Account>> e : roleBoxes.entrySet()) {
                JComboBox<Account> box = e.getValue();
                box.removeAllItems();
                String number = settings.get(e.getKey());
                for (Account a : accountService.list("", true)) {
                    box.addItem(a);
                    if (a.number().equals(number)) {
                        box.setSelectedItem(a);
                    }
                }
            }
        });
    }

    private void save() {
        if (businessName.getText().isBlank()) {
            throw new AccountingException("Please enter the business name.");
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put(SettingsService.BUSINESS_NAME, businessName.getText().trim());
        for (Map.Entry<String, JComboBox<Account>> e : roleBoxes.entrySet()) {
            Account a = (Account) e.getValue().getSelectedItem();
            if (a == null) {
                throw new AccountingException("Please choose an account for every role.");
            }
            values.put(e.getKey(), a.number());
        }
        values.put(SettingsService.SALES_ON, String.valueOf(salesOn.isSelected()));
        values.put(SettingsService.PURCHASES_ON, String.valueOf(purchasesOn.isSelected()));
        settings.save(values);
        Dialogs.info(this, "Settings saved.");
    }

    private void backupNow() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("accounting-backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".db"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            backups.backup(fc.getSelectedFile());
            Dialogs.info(this, "Backup saved to\n" + fc.getSelectedFile().getAbsolutePath());
        }
    }

    private void restore() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        if (!Dialogs.confirm(this, "Restore backup", "Replace ALL current data with the contents of\n" + fc.getSelectedFile().getName()
                + "?\n\nYour current data is saved first to data/backups/ so nothing is lost.")) {
            return;
        }
        File safety = backups.restore(fc.getSelectedFile());
        Dialogs.info(this, "Restore complete.\nYour previous data was saved as " + safety.getName() + ".");
        nav.goTo("dashboard");
    }
}
