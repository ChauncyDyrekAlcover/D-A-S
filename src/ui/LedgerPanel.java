package ui;

import model.Account;
import model.JournalEntry;
import model.LedgerResult;
import model.LedgerRow;
import service.AccountService;
import service.JournalService;
import service.ReportService;
import util.AccountingException;
import util.Dates;
import util.Money;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Module E: the General Ledger, calculated from posted journal entries. */
public class LedgerPanel extends Page {
    private final AccountService accountService = new AccountService();
    private final ReportService reports = new ReportService();
    private final JournalService journal = new JournalService();
    private final JComboBox<Account> accountBox = new JComboBox<>();
    private final JTextField from = Theme.field(9);
    private final JTextField to = Theme.field(9);
    private final DataTable table = new DataTable();
    private final JLabel summary = Theme.label(" ", 13, true, Theme.TEXT);
    private boolean loading = false;

    public LedgerPanel() {
        super("General Ledger", "Each account's posted activity with a running balance. Double-click a line to open the journal entry.");
        int year = LocalDate.now().getYear();
        from.setText(year + "-01-01");
        to.setText(year + "-12-31");
        table.setSortable(false);
        accountBox.setPreferredSize(new Dimension(280, 32));

        JPanel bar = Theme.toolbar();
        bar.add(Theme.fieldLabel("ACCOUNT"));
        bar.add(accountBox);
        bar.add(Theme.fieldLabel("FROM"));
        bar.add(from);
        bar.add(Theme.fieldLabel("TO"));
        bar.add(to);
        JButton show = Theme.button("Show Ledger", Theme.Kind.PRIMARY);
        JButton export = Theme.button("Export CSV", Theme.Kind.SECONDARY);
        JButton print = Theme.button("Print", Theme.Kind.SECONDARY);
        bar.add(show);
        bar.add(export);
        bar.add(print);

        JPanel north = new JPanel(new BorderLayout(0, 8));
        north.setOpaque(false);
        north.add(bar, BorderLayout.NORTH);
        JPanel sum = Theme.card(new BorderLayout());
        sum.add(summary, BorderLayout.CENTER);
        north.add(sum, BorderLayout.SOUTH);
        body.add(north, BorderLayout.NORTH);
        body.add(table, BorderLayout.CENTER);
        table.setColumnWidths(105, 150, 80, 300, 115, 115, 125, 80);

        show.addActionListener(e -> Dialogs.guard(this, this::load));
        accountBox.addActionListener(e -> {
            if (!loading) {
                Dialogs.guard(this, this::load);
            }
        });
        table.onDoubleClick(id -> Dialogs.guard(this, () -> {
            JournalEntry e = journal.get(id);
            JournalEntryDialog.open(this, e, e.type());
        }));
        export.addActionListener(e -> TableTools.exportCsv(this, table.table(), "general-ledger"));
        print.addActionListener(e -> TableTools.print(this, table.table(), "General Ledger - " + accountBox.getSelectedItem()));
    }

    @Override
    public void refresh() {
        Dialogs.guard(this, () -> {
            loading = true;
            Account keep = (Account) accountBox.getSelectedItem();
            accountBox.removeAllItems();
            Account match = null;
            for (Account a : accountService.list("", true)) {
                accountBox.addItem(a);
                if (keep != null && keep.id() == a.id()) {
                    match = a;
                }
            }
            if (match != null) {
                accountBox.setSelectedItem(match);
            }
            loading = false;
            load();
        });
    }

    /** Lets other screens jump straight to one account. */
    public void showAccount(Account a) {
        accountBox.setSelectedItem(a);
    }

    private void load() {
        Account a = (Account) accountBox.getSelectedItem();
        if (a == null) {
            table.setData(new String[]{"Date"}, new ArrayList<>());
            summary.setText("No accounts yet.");
            return;
        }
        LocalDate f = Dates.parse(from.getText());
        LocalDate t = Dates.parse(to.getText());
        LedgerResult r = reports.ledger(a.id(), f, t);
        List<Object[]> rows = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        List<Integer> bold = new ArrayList<>();
        if (f != null) {
            bold.add(rows.size());
            rows.add(new Object[]{"", "", "", "Opening balance before " + f, "", "", Money.format(r.opening()), ""});
            ids.add(-1);
        }
        for (LedgerRow l : r.rows()) {
            rows.add(new Object[]{l.date().toString(), l.entryNumber(), l.reference(), l.description(),
                Money.formatOrDash(l.debit()), Money.formatOrDash(l.credit()), Money.format(l.balance()), l.period()});
            ids.add(l.entryId());
        }
        bold.add(rows.size());
        rows.add(new Object[]{"", "", "", "TOTALS / ENDING BALANCE", Money.format(r.totalDebit()),
            Money.format(r.totalCredit()), Money.format(r.closing()), ""});
        ids.add(-1);
        table.setData(new String[]{"Date", "Entry No.", "Reference", "Description", "Debit", "Credit", "Balance", "Period"},
                rows, ids, bold);
        summary.setText(a.number() + " \u2014 " + a.name() + "     |     " + a.type() + "     |     Normal balance: "
                + a.normal().name().toLowerCase() + "     |     Ending balance: " + Money.format(r.closing()));
        if (r.rows().isEmpty()) {
            summary.setText(summary.getText() + "     (no posted activity in this range)");
        }
    }
}
