package ui;

import model.Account;
import model.JournalEntry;
import model.JournalEntry.Journal;
import model.JournalEntry.Status;
import model.JournalLine;
import service.AccountService;
import service.JournalService;
import util.AccountingException;
import util.Dates;
import util.Money;

import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The journal entry form: date, description, any number of debit/credit lines, live totals.
 * Draft entries can be edited; posted entries open in read-only mode.
 */
public class JournalEntryDialog extends JDialog {
    private final JournalService service = new JournalService();
    private final JournalEntry.Type type;
    private final JournalEntry entry;
    private final boolean editable;
    private boolean changed = false;
    private boolean journalTouched = false;
    private boolean settingJournal = false;

    private final JTextField dateField = Theme.field(11);
    private final JTextField refField = Theme.field(14);
    private final JTextField descField = Theme.field(40);
    private final JComboBox<String> categoryBox = new JComboBox<>(new String[]{"", "Cash receipt", "Cash payment",
        "Sale on account", "Purchase on account", "Owner investment", "Owner withdrawal", "Adjustment", "Other"});
    private final JComboBox<Journal> journalBox = Theme.combo(Journal.values());
    private final JTextArea notesArea = new JTextArea(2, 40);
    private final LineModel model = new LineModel();
    private final JTable linesTable = new JTable(model);
    private final JLabel totalDebit = Theme.label("", 14, true, Theme.TEXT);
    private final JLabel totalCredit = Theme.label("", 14, true, Theme.TEXT);
    private final JLabel balancePill = Theme.pill("", Theme.MUTED, new java.awt.Color(0xE5E7EB));
    private final List<Account> accounts;

    /** Opens the form. entry == null means a new entry. Returns true if something was saved. */
    public static boolean open(Component parent, JournalEntry entry, JournalEntry.Type type) {
        JournalEntryDialog d = new JournalEntryDialog(parent, entry, type);
        d.setVisible(true);
        return d.changed;
    }

    private JournalEntryDialog(Component parent, JournalEntry entry, JournalEntry.Type type) {
        super(SwingUtilities.getWindowAncestor(parent), "Journal Entry", ModalityType.APPLICATION_MODAL);
        this.entry = entry;
        this.type = entry == null ? type : entry.type();
        this.editable = entry == null || entry.status() == Status.DRAFT;
        this.accounts = new AccountService().list("", false);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Theme.BG);
        root.setBorder(Theme.pad(16, 20, 16, 20));
        root.add(buildHeader(), BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(0, 12));
        center.setOpaque(false);
        center.add(buildForm(), BorderLayout.NORTH);
        center.add(buildLines(), BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        setContentPane(root);
        setSize(960, 760);
        setLocationRelativeTo(parent);

        loadEntry();
        updateTotals();
    }

    // ------------------------------------------------------------------ layout

    private JPanel buildHeader() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        p.setOpaque(false);
        String title = entry == null || entry.id() == 0
                ? (type == JournalEntry.Type.ADJUSTING ? "New Adjusting Entry" : "New Journal Entry")
                : entry.number() + (entry.isReversal() ? "  (reversing entry)" : "");
        p.add(Theme.label(title, 20, true, Theme.TEXT));
        if (entry != null && entry.id() != 0) {
            Status s = entry.status();
            p.add(Theme.pill(s.name(), s == Status.DRAFT ? Theme.AMBER : s == Status.POSTED ? Theme.GREEN : Theme.RED,
                    s == Status.DRAFT ? Theme.AMBER_BG : s == Status.POSTED ? Theme.GREEN_BG : Theme.RED_BG));
            String relations = service.relationText(entry);
            if (!relations.isEmpty()) {
                p.add(Theme.label(relations, 12, false, Theme.MUTED));
            }
        }
        if (type == JournalEntry.Type.ADJUSTING) {
            p.add(Theme.pill("ADJUSTING ENTRY", Theme.ACCENT_DARK, new java.awt.Color(0xE0EAFF)));
        }
        return p;
    }

    private JPanel buildForm() {
        JPanel card = Theme.card(new BorderLayout());
        JPanel form = Forms.panel();

        JPanel dateRef = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        dateRef.setOpaque(false);
        dateRef.add(dateField);
        dateRef.add(javax.swing.Box.createHorizontalStrut(24));
        dateRef.add(Theme.fieldLabel("REFERENCE NO.   "));
        dateRef.add(refField);
        dateField.setToolTipText("yyyy-MM-dd");
        Forms.row(form, 0, "DATE (yyyy-MM-dd)", dateRef);
        Forms.row(form, 1, "DESCRIPTION", descField);

        categoryBox.setEditable(true);
        categoryBox.setFont(Theme.font(Font.PLAIN, 13));
        JPanel catJournal = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        catJournal.setOpaque(false);
        categoryBox.setPreferredSize(new Dimension(190, 32));
        catJournal.add(categoryBox);
        catJournal.add(javax.swing.Box.createHorizontalStrut(24));
        catJournal.add(Theme.fieldLabel("RECORD IN   "));
        catJournal.add(journalBox);
        if (type != JournalEntry.Type.REGULAR) {
            journalBox.setSelectedItem(Journal.GENERAL);
            journalBox.setEnabled(false);
        } else {
            JButton suggest = Theme.button("Suggest", Theme.Kind.SECONDARY);
            suggest.addActionListener(e -> Dialogs.guard(this, () -> {
                journalTouched = false;
                autoSuggest();
            }));
            catJournal.add(javax.swing.Box.createHorizontalStrut(8));
            catJournal.add(suggest);
            catJournal.add(javax.swing.Box.createHorizontalStrut(10));
            catJournal.add(Theme.label("Suggested from the accounts used - please confirm.", 11, false, Theme.MUTED));
        }
        journalBox.addActionListener(e -> {
            if (!settingJournal) {
                journalTouched = true;
            }
        });
        Forms.row(form, 2, "CATEGORY", catJournal);

        notesArea.setLineWrap(true);
        notesArea.setWrapStyleWord(true);
        Theme.styleField(notesArea);
        Forms.row(form, 3, "SUPPORTING NOTES", new JScrollPane(notesArea));
        card.add(form, BorderLayout.CENTER);

        if (!editable) {
            for (JTextField f : new JTextField[]{dateField, refField, descField}) {
                f.setEditable(false);
            }
            notesArea.setEditable(false);
            categoryBox.setEnabled(false);
            journalBox.setEnabled(false);
        }
        return card;
    }

    private JPanel buildLines() {
        JPanel card = Theme.card(new BorderLayout(0, 8));
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(Theme.label("Debit and credit lines", 14, true, Theme.TEXT), BorderLayout.WEST);
        if (editable) {
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.setOpaque(false);
            JButton add = Theme.button("+ Add Line", Theme.Kind.SECONDARY);
            JButton remove = Theme.button("Remove Line", Theme.Kind.SECONDARY);
            add.addActionListener(e -> model.addRow());
            remove.addActionListener(e -> {
                int r = linesTable.getSelectedRow();
                if (r >= 0) {
                    stopEditing();
                    model.removeRow(r);
                }
            });
            buttons.add(add);
            buttons.add(remove);
            top.add(buttons, BorderLayout.EAST);
        }
        card.add(top, BorderLayout.NORTH);

        linesTable.setRowHeight(34);
        linesTable.setShowGrid(true);
        linesTable.setGridColor(Theme.BORDER);
        linesTable.setFont(Theme.font(Font.PLAIN, 13));
        linesTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        linesTable.setSurrendersFocusOnKeystroke(true);
        DataTable.styleHeader(linesTable);
        linesTable.getColumnModel().getColumn(0).setPreferredWidth(320);
        linesTable.getColumnModel().getColumn(1).setPreferredWidth(240);
        linesTable.getColumnModel().getColumn(2).setPreferredWidth(130);
        linesTable.getColumnModel().getColumn(3).setPreferredWidth(130);

        JComboBox<Account> accountBox = new JComboBox<>(accounts.toArray(new Account[0]));
        accountBox.setFont(Theme.font(Font.PLAIN, 13));
        accountBox.setEditable(false);
        linesTable.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(accountBox));
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        linesTable.getColumnModel().getColumn(2).setCellRenderer(right);
        linesTable.getColumnModel().getColumn(3).setCellRenderer(right);
        JTextField amountEditorField = new JTextField();
        amountEditorField.setHorizontalAlignment(SwingConstants.RIGHT);
        DefaultCellEditor amountEditor = new DefaultCellEditor(amountEditorField);
        linesTable.getColumnModel().getColumn(2).setCellEditor(amountEditor);
        linesTable.getColumnModel().getColumn(3).setCellEditor(amountEditor);

        JScrollPane sp = new JScrollPane(linesTable);
        sp.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        sp.getViewport().setBackground(java.awt.Color.WHITE);
        card.add(sp, BorderLayout.CENTER);

        JPanel totals = new JPanel(new FlowLayout(FlowLayout.RIGHT, 18, 4));
        totals.setOpaque(false);
        totals.add(Theme.fieldLabel("TOTAL DEBITS"));
        totals.add(totalDebit);
        totals.add(Theme.fieldLabel("TOTAL CREDITS"));
        totals.add(totalCredit);
        totals.add(balancePill);
        card.add(totals, BorderLayout.SOUTH);
        return card;
    }

    private JPanel buildFooter() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        p.setOpaque(false);
        JButton close = Theme.button(editable ? "Cancel" : "Close", Theme.Kind.SECONDARY);
        close.addActionListener(e -> dispose());
        p.add(close);
        if (editable) {
            JButton draft = Theme.button("Save Draft", Theme.Kind.PRIMARY);
            JButton post = Theme.button("Save & Post", Theme.Kind.SUCCESS);
            draft.addActionListener(e -> Dialogs.guard(this, this::saveDraft));
            post.addActionListener(e -> Dialogs.guard(this, this::saveAndPost));
            p.add(draft);
            p.add(post);
        }
        return p;
    }

    // ------------------------------------------------------------------ data

    private void loadEntry() {
        if (entry == null) {
            dateField.setText(LocalDate.now().toString());
            if (type == JournalEntry.Type.ADJUSTING) {
                categoryBox.setSelectedItem("Adjustment");
            }
            model.addRow();
            model.addRow();
            return;
        }
        dateField.setText(entry.date().toString());
        refField.setText(entry.reference());
        descField.setText(entry.description());
        categoryBox.setSelectedItem(entry.category());
        notesArea.setText(entry.notes());
        settingJournal = true;
        journalBox.setSelectedItem(entry.journal());
        settingJournal = false;
        journalTouched = entry.id() != 0;
        for (JournalLine l : entry.lines()) {
            Account a = null;
            for (Account x : accounts) {
                if (x.id() == l.accountId()) {
                    a = x;
                }
            }
            if (a == null && l.accountId() > 0) {
                String number = l.accountNumber().isEmpty() ? "#" + l.accountId() : l.accountNumber();
                a = new Account(l.accountId(), number, l.accountName(), Account.Type.ASSET, Account.Normal.DEBIT, "", false);
            }
            model.addRow(a, l.memo(), l.debit() > 0 ? Money.plain(l.debit()) : "", l.credit() > 0 ? Money.plain(l.credit()) : "");
        }
        if (model.getRowCount() == 0) {
            model.addRow();
            model.addRow();
        }
    }

    private void stopEditing() {
        if (linesTable.isEditing()) {
            linesTable.getCellEditor().stopCellEditing();
        }
    }

    private JournalEntry buildEntry() {
        stopEditing();
        LocalDate date = Dates.require(dateField.getText(), "transaction date");
        List<JournalLine> lines = new ArrayList<>();
        for (LineRow r : model.rows) {
            String d = r.debit.trim();
            String c = r.credit.trim();
            if (r.account == null && d.isEmpty() && c.isEmpty()) {
                continue; // an untouched blank row
            }
            lines.add(new JournalLine(0, r.account == null ? 0 : r.account.id(), "", "", r.memo.trim(),
                    Money.parse(d), Money.parse(c)));
        }
        Object cat = categoryBox.getSelectedItem();
        return new JournalEntry(entry == null ? 0 : entry.id(), entry == null ? null : entry.number(), date,
                descField.getText(), refField.getText(), cat == null ? "" : cat.toString(), notesArea.getText(),
                Status.DRAFT, type, (Journal) journalBox.getSelectedItem(), null, null, null,
                entry == null ? null : entry.reversalOfId(), entry == null ? null : entry.replacesId(), lines);
    }

    private void saveDraft() {
        JournalEntry saved = service.saveDraft(buildEntry());
        changed = true;
        Dialogs.info(this, "Draft saved as " + saved.number() + ".\nIt does not affect the ledger until you post it.");
        dispose();
    }

    private void saveAndPost() {
        JournalEntry e = buildEntry();
        if (e.totalDebit() != e.totalCredit()) {
            throw new AccountingException("The entry is not balanced yet: debits " + Money.format(e.totalDebit())
                    + " and credits " + Money.format(e.totalCredit()) + ".");
        }
        if (!Dialogs.confirm(this, "Post entry",
                "Post this entry for " + Money.format(e.totalDebit()) + " to the General Ledger?\n\n"
                        + "A posted entry cannot be edited or deleted. Mistakes are corrected with a reversing entry.")) {
            return;
        }
        JournalEntry posted = service.saveAndPost(e);
        changed = true;
        Dialogs.info(this, "Entry " + posted.number() + " was posted.");
        dispose();
    }

    private void autoSuggest() {
        if (journalTouched || !editable || type != JournalEntry.Type.REGULAR) {
            return;
        }
        List<JournalLine> lines = new ArrayList<>();
        for (LineRow r : model.rows) {
            if (r.account == null) {
                continue;
            }
            long d;
            long c;
            try {
                d = Money.parse(r.debit);
                c = Money.parse(r.credit);
            } catch (AccountingException ex) {
                continue;
            }
            if (d > 0 || c > 0) {
                lines.add(JournalLine.of(r.account.id(), "", d, c));
            }
        }
        if (lines.size() < 2) {
            return;
        }
        settingJournal = true;
        journalBox.setSelectedItem(service.suggestJournal(type, lines));
        settingJournal = false;
    }

    private void updateTotals() {
        long d = 0;
        long c = 0;
        for (LineRow r : model.rows) {
            try {
                d += Money.parse(r.debit);
                c += Money.parse(r.credit);
            } catch (AccountingException ignored) {
                // an invalid amount is reported when saving
            }
        }
        totalDebit.setText(Money.format(d));
        totalCredit.setText(Money.format(c));
        if (d == 0 && c == 0) {
            Theme.setPill(balancePill, "Enter amounts", Theme.MUTED, new java.awt.Color(0xE5E7EB));
        } else if (d == c) {
            Theme.setPill(balancePill, "BALANCED", Theme.GREEN, Theme.GREEN_BG);
        } else {
            Theme.setPill(balancePill, "DIFFERENCE " + Money.format(Math.abs(d - c)), Theme.RED, Theme.RED_BG);
        }
        try {
            autoSuggest();
        } catch (RuntimeException ignored) {
            // suggestion is only a convenience
        }
    }

    // ------------------------------------------------------------------ table model

    private static class LineRow {
        Account account;
        String memo = "";
        String debit = "";
        String credit = "";
    }

    private class LineModel extends AbstractTableModel {
        private final List<LineRow> rows = new ArrayList<>();
        private final String[] names = {"Account", "Memo (optional)", "Debit", "Credit"};

        void addRow() {
            addRow(null, "", "", "");
        }

        void addRow(Account a, String memo, String debit, String credit) {
            LineRow r = new LineRow();
            r.account = a;
            r.memo = memo;
            r.debit = debit;
            r.credit = credit;
            rows.add(r);
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
            updateTotals();
        }

        void removeRow(int index) {
            rows.remove(index);
            fireTableRowsDeleted(index, index);
            updateTotals();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int c) {
            return names[c];
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return editable;
        }

        @Override
        public Object getValueAt(int r, int c) {
            LineRow row = rows.get(r);
            return switch (c) {
                case 0 -> row.account == null ? "" : row.account;
                case 1 -> row.memo;
                case 2 -> row.debit;
                default -> row.credit;
            };
        }

        @Override
        public void setValueAt(Object value, int r, int c) {
            LineRow row = rows.get(r);
            String text = value == null ? "" : value.toString().trim();
            switch (c) {
                case 0 -> row.account = value instanceof Account ? (Account) value : null;
                case 1 -> row.memo = text;
                case 2 -> {
                    row.debit = tidy(text);
                    if (!text.isEmpty()) {
                        row.credit = "";
                    }
                }
                default -> {
                    row.credit = tidy(text);
                    if (!text.isEmpty()) {
                        row.debit = "";
                    }
                }
            }
            fireTableRowsUpdated(r, r);
            updateTotals();
        }

        /** "5000" -> "5,000.00"; text that is not a number is kept so the error message can quote it. */
        private String tidy(String text) {
            if (text.isEmpty()) {
                return "";
            }
            try {
                return Money.plain(Money.parse(text));
            } catch (AccountingException e) {
                return text;
            }
        }
    }
}
