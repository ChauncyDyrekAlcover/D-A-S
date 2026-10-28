package ui;

import model.Account;
import model.EntrySummary;
import model.JournalEntry;
import model.JournalEntry.Journal;
import model.JournalEntry.Status;
import model.JournalLine;
import service.AccountService;
import service.JournalService;
import util.AccountingException;
import util.Dates;
import util.Money;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Module C (General Journal) and Module H (Adjusting Entries). The same screen is used for both:
 * the adjusting version only lists adjusting entries and offers starting templates.
 */
public class JournalPanel extends Page {
    private static final String[][] TEMPLATES = {
        {"Supplies used", "5040", "1030", "Supplies used during the period"},
        {"Depreciation", "5050", "1110", "Depreciation of equipment for the period"},
        {"Accrued expense (salaries)", "5030", "2020", "Salaries earned by employees but not yet paid"},
        {"Accrued revenue", "1020", "4010", "Revenue earned but not yet billed or collected"},
        {"Prepaid expense used (insurance)", "5060", "1040", "Prepaid insurance that has expired"},
        {"Unearned revenue now earned", "2030", "4010", "Advance payments now earned"},
        {"Blank adjusting entry", "", "", ""}
    };

    private final boolean adjusting;
    private final Navigator nav;
    private final JournalService service = new JournalService();
    private final AccountService accountService = new AccountService();
    private final DataTable table = new DataTable();
    private final JTextField from = Theme.field(9);
    private final JTextField to = Theme.field(9);
    private final JTextField search = Theme.field(14);
    private final JComboBox<Object> accountBox = new JComboBox<>(new Object[]{"All accounts"});
    private final JComboBox<Object> statusBox = new JComboBox<>(new Object[]{"All statuses", Status.DRAFT, Status.POSTED, Status.REVERSED});
    private final JComboBox<Object> typeBox = new JComboBox<>(new Object[]{"All types", JournalEntry.Type.REGULAR,
        JournalEntry.Type.ADJUSTING, JournalEntry.Type.CLOSING});
    private final JComboBox<String> templateBox = new JComboBox<>();
    private boolean loading = false;

    public JournalPanel(boolean adjusting, Navigator nav) {
        super(adjusting ? "Adjusting Entries" : "General Journal",
                adjusting ? "Record end-of-period adjustments. Amounts are never invented: you enter or confirm every amount."
                        : "Record transactions once. Review drafts, then post them to the General Ledger.");
        this.adjusting = adjusting;
        this.nav = nav;

        JPanel filters = Theme.toolbar();
        from.setToolTipText("yyyy-MM-dd (blank = no limit)");
        to.setToolTipText("yyyy-MM-dd (blank = no limit)");
        filters.add(Theme.fieldLabel("FROM"));
        filters.add(from);
        filters.add(Theme.fieldLabel("TO"));
        filters.add(to);
        filters.add(Theme.fieldLabel("ACCOUNT"));
        accountBox.setPreferredSize(new Dimension(210, 32));
        filters.add(accountBox);
        filters.add(Theme.fieldLabel("STATUS"));
        filters.add(statusBox);
        if (!adjusting) {
            filters.add(Theme.fieldLabel("TYPE"));
            filters.add(typeBox);
        }
        filters.add(Theme.fieldLabel("SEARCH"));
        search.setToolTipText("Entry number, reference number or description");
        filters.add(search);
        JButton go = Theme.button("Search", Theme.Kind.PRIMARY);
        filters.add(go);

        JPanel actions = Theme.toolbar();
        if (adjusting) {
            for (String[] t : TEMPLATES) {
                templateBox.addItem(t[0]);
            }
            templateBox.setPreferredSize(new Dimension(250, 32));
            actions.add(Theme.fieldLabel("START FROM"));
            actions.add(templateBox);
            JButton create = Theme.button("New Adjusting Entry", Theme.Kind.PRIMARY);
            create.addActionListener(e -> Dialogs.guard(this, this::newFromTemplate));
            actions.add(create);
        } else {
            JButton create = Theme.button("New Entry", Theme.Kind.PRIMARY);
            create.addActionListener(e -> Dialogs.guard(this, () -> {
                if (JournalEntryDialog.open(this, null, JournalEntry.Type.REGULAR)) {
                    refresh();
                }
            }));
            actions.add(create);
        }
        JButton open = Theme.button("Open / Edit", Theme.Kind.SECONDARY);
        JButton post = Theme.button("Post Selected", Theme.Kind.SUCCESS);
        JButton reverse = Theme.button("Reverse Selected", Theme.Kind.SECONDARY);
        JButton delete = Theme.button("Delete Draft", Theme.Kind.DANGER);
        JButton export = Theme.button("Export CSV", Theme.Kind.SECONDARY);
        JButton print = Theme.button("Print", Theme.Kind.SECONDARY);
        actions.add(open);
        actions.add(post);
        actions.add(reverse);
        actions.add(delete);
        actions.add(export);
        actions.add(print);
        if (adjusting) {
            JButton tb = Theme.button("Adjusted Trial Balance \u2192", Theme.Kind.SECONDARY);
            tb.addActionListener(e -> nav.goTo("trial"));
            actions.add(tb);
        }

        JPanel north = new JPanel(new BorderLayout(0, 8));
        north.setOpaque(false);
        north.add(filters, BorderLayout.NORTH);
        north.add(actions, BorderLayout.SOUTH);
        body.add(north, BorderLayout.NORTH);
        body.add(table, BorderLayout.CENTER);
        table.setColumnWidths(140, 100, 90, 290, 160, 120, 90, 120);

        go.addActionListener(e -> refresh());
        search.addActionListener(e -> refresh());
        from.addActionListener(e -> refresh());
        to.addActionListener(e -> refresh());
        accountBox.addActionListener(e -> refreshIfIdle());
        statusBox.addActionListener(e -> refreshIfIdle());
        typeBox.addActionListener(e -> refreshIfIdle());
        open.addActionListener(e -> Dialogs.guard(this, this::openSelected));
        table.onDoubleClick(id -> Dialogs.guard(this, this::openSelected));
        post.addActionListener(e -> Dialogs.guard(this, this::postSelected));
        reverse.addActionListener(e -> Dialogs.guard(this, this::reverseSelected));
        delete.addActionListener(e -> Dialogs.guard(this, this::deleteSelected));
        export.addActionListener(e -> TableTools.exportCsv(this, table.table(), adjusting ? "adjusting-entries" : "general-journal"));
        print.addActionListener(e -> TableTools.print(this, table.table(), adjusting ? "Adjusting Entries" : "General Journal"));
    }

    private void refreshIfIdle() {
        if (!loading) {
            refresh();
        }
    }

    @Override
    public void refresh() {
        Dialogs.guard(this, () -> {
            loading = true;
            Object keep = accountBox.getSelectedItem();
            accountBox.removeAllItems();
            accountBox.addItem("All accounts");
            Account match = null;
            for (Account a : accountService.list("", true)) {
                accountBox.addItem(a);
                if (keep instanceof Account && ((Account) keep).id() == a.id()) {
                    match = a;
                }
            }
            accountBox.setSelectedItem(match == null ? "All accounts" : match);
            loading = false;

            LocalDate f = Dates.parse(from.getText());
            LocalDate t = Dates.parse(to.getText());
            Integer accountId = accountBox.getSelectedItem() instanceof Account a ? a.id() : null;
            Status status = statusBox.getSelectedItem() instanceof Status s ? s : null;
            JournalEntry.Type type = adjusting ? JournalEntry.Type.ADJUSTING
                    : typeBox.getSelectedItem() instanceof JournalEntry.Type ty ? ty : null;
            List<Object[]> rows = new ArrayList<>();
            List<Integer> ids = new ArrayList<>();
            for (EntrySummary e : service.search(f, t, accountId, search.getText(), status, type)) {
                rows.add(new Object[]{e.number(), e.date().toString(), e.reference(), e.description(), e.journal().toString(),
                    e.type().toString() + (e.reversal() ? " (reversal)" : ""), e.status().name(), Money.format(e.amount())});
                ids.add(e.id());
            }
            table.setData(new String[]{"Entry No.", "Date", "Reference", "Description", "Journal", "Type", "Status", "Amount"},
                    rows, ids, List.of());
        });
    }

    private int selectedId() {
        int id = table.selectedId();
        if (id < 0) {
            throw new AccountingException("Please select an entry in the table first.");
        }
        return id;
    }

    private void openSelected() {
        JournalEntry e = service.get(selectedId());
        if (JournalEntryDialog.open(this, e, e.type())) {
            refresh();
        }
    }

    private void postSelected() {
        JournalEntry e = service.get(selectedId());
        if (e.status() != Status.DRAFT) {
            throw new AccountingException("Entry " + e.number() + " is already " + e.status().name().toLowerCase() + " and cannot be posted again.");
        }
        if (Dialogs.confirm(this, "Post entry", "Post " + e.number() + " (" + Money.format(e.totalDebit()) + ") to the General Ledger?\n\n"
                + "A posted entry cannot be edited or deleted. Mistakes are corrected with a reversing entry.")) {
            service.post(e.id());
            refresh();
        }
    }

    private void deleteSelected() {
        JournalEntry e = service.get(selectedId());
        if (Dialogs.confirm(this, "Delete draft", "Delete draft " + e.number() + "? This cannot be undone.")) {
            service.deleteDraft(e.id());
            refresh();
        }
    }

    private void reverseSelected() {
        JournalEntry e = service.get(selectedId());
        if (e.status() != Status.POSTED) {
            throw new AccountingException("Only posted entries can be reversed.");
        }
        JTextField date = Theme.field(12);
        date.setText(LocalDate.now().toString());
        JTextField reason = Theme.field(28);
        JPanel form = Forms.panel();
        form.setPreferredSize(new Dimension(420, 150));
        Forms.row(form, 0, "ENTRY", Theme.label(e.number() + " - " + e.description(), 13, true, Theme.TEXT));
        Forms.row(form, 1, "REVERSAL DATE (yyyy-MM-dd)", date);
        Forms.row(form, 2, "REASON", reason);
        if (JOptionPane.showConfirmDialog(this, form, "Reverse entry", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        JournalEntry reversal = service.reverse(e.id(), Dates.require(date.getText(), "reversal date"), reason.getText());
        refresh();
        if (Dialogs.confirm(this, "Reversal posted", "Reversing entry " + reversal.number() + " was posted.\n\n"
                + "Do you want to enter the corrected replacement entry now?")) {
            JournalEntry template = service.replacementTemplate(e.id());
            if (JournalEntryDialog.open(this, template, template.type())) {
                refresh();
            }
        }
    }

    private void newFromTemplate() {
        String[] t = TEMPLATES[Math.max(0, templateBox.getSelectedIndex())];
        List<JournalLine> lines = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            if (!t[i].isEmpty()) {
                for (Account a : accountService.list("", false)) {
                    if (a.number().equals(t[i])) {
                        lines.add(JournalLine.of(a.id(), "", 0, 0));
                    }
                }
            }
        }
        JournalEntry template = JournalEntry.newDraft(LocalDate.now(), t[3], "", "Adjustment",
                t[3].isEmpty() ? "" : "Suggested accounts are only a starting point - review them and enter the amount you calculated.",
                JournalEntry.Type.ADJUSTING, Journal.GENERAL, lines);
        if (!t[3].isEmpty()) {
            Dialogs.info(this, "The suggested accounts are filled in, but the AMOUNT is left empty on purpose.\n"
                    + "Enter the amount you calculated and review the accounts before saving.");
        }
        if (JournalEntryDialog.open(this, template, JournalEntry.Type.ADJUSTING)) {
            refresh();
        }
    }
}
