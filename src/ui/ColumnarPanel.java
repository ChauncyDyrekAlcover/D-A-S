package ui;

import model.ColumnarJournal;
import model.JournalEntry.Journal;
import service.ReportService;
import service.SettingsService;
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
import java.util.Map;

/** Module F: columnar journals, filled automatically from posted entries. */
public class ColumnarPanel extends Page {
    private final ReportService reports = new ReportService();
    private final SettingsService settings = new SettingsService();
    private final JComboBox<Journal> journalBox = new JComboBox<>();
    private final JTextField from = Theme.field(9);
    private final JTextField to = Theme.field(9);
    private final DataTable table = new DataTable();
    private final JLabel summary = Theme.label(" ", 13, true, Theme.TEXT);
    private final JLabel rules = Theme.label(" ", 12, false, Theme.MUTED);
    private boolean loading = false;

    public ColumnarPanel() {
        super("Columnar Journals", "Cash Receipts, Cash Disbursements, Sales, Purchases and General journals - no re-typing, they fill themselves from posted entries.");
        int year = LocalDate.now().getYear();
        from.setText(year + "-01-01");
        to.setText(year + "-12-31");
        table.setSortable(false);
        journalBox.setPreferredSize(new Dimension(240, 32));
        JPanel bar = Theme.toolbar();
        bar.add(Theme.fieldLabel("JOURNAL"));
        bar.add(journalBox);
        bar.add(Theme.fieldLabel("FROM"));
        bar.add(from);
        bar.add(Theme.fieldLabel("TO"));
        bar.add(to);
        JButton show = Theme.button("Show Journal", Theme.Kind.PRIMARY);
        JButton export = Theme.button("Export CSV", Theme.Kind.SECONDARY);
        JButton print = Theme.button("Print", Theme.Kind.SECONDARY);
        bar.add(show);
        bar.add(export);
        bar.add(print);

        JPanel north = new JPanel(new BorderLayout(0, 8));
        north.setOpaque(false);
        north.add(bar, BorderLayout.NORTH);
        JPanel info = Theme.card(new BorderLayout(0, 4));
        info.add(summary, BorderLayout.NORTH);
        info.add(rules, BorderLayout.SOUTH);
        north.add(info, BorderLayout.SOUTH);
        body.add(north, BorderLayout.NORTH);
        body.add(table, BorderLayout.CENTER);

        show.addActionListener(e -> Dialogs.guard(this, this::load));
        journalBox.addActionListener(e -> {
            if (!loading) {
                Dialogs.guard(this, this::load);
            }
        });
        export.addActionListener(e -> TableTools.exportCsv(this, table.table(), "columnar-journal"));
        print.addActionListener(e -> TableTools.print(this, table.table(), String.valueOf(journalBox.getSelectedItem())));
    }

    @Override
    public void refresh() {
        Dialogs.guard(this, () -> {
            loading = true;
            Object keep = journalBox.getSelectedItem();
            journalBox.removeAllItems();
            journalBox.addItem(Journal.CASH_RECEIPTS);
            journalBox.addItem(Journal.CASH_DISBURSEMENTS);
            if (settings.isOn(SettingsService.SALES_ON)) {
                journalBox.addItem(Journal.SALES);
            }
            if (settings.isOn(SettingsService.PURCHASES_ON)) {
                journalBox.addItem(Journal.PURCHASES);
            }
            journalBox.addItem(Journal.GENERAL);
            journalBox.setSelectedItem(keep == null ? Journal.CASH_RECEIPTS : keep);
            loading = false;
            load();
        });
    }

    private void load() {
        Journal j = (Journal) journalBox.getSelectedItem();
        if (j == null) {
            return;
        }
        ColumnarJournal cj = reports.columnar(j, Dates.parse(from.getText()), Dates.parse(to.getText()));
        List<Object[]> rows = new ArrayList<>(cj.rows());
        table.setData(cj.headers().toArray(new String[0]), rows, new ArrayList<>(), cj.totalRows());
        table.setColumnWidths(95, 120, 100, 280);
        int entries = cj.rows().size() - cj.totalRows().size();
        summary.setText(j + "     |     " + entries + " entr" + (entries == 1 ? "y" : "ies")
                + "     |     Debit columns " + Money.format(cj.totalDebits()) + "     |     Credit columns "
                + Money.format(cj.totalCredits()) + (cj.totalDebits() == cj.totalCredits() ? "     (balanced)" : "     (OUT OF BALANCE)"));
        rules.setText(switch (j) {
            case CASH_RECEIPTS -> "Rule: entries where Cash is only debited. One column per account used (debit columns first, then credit columns).";
            case CASH_DISBURSEMENTS -> "Rule: entries where Cash is only credited. One column per account used.";
            case SALES -> "Rule: no cash, Accounts Receivable debited and a revenue account credited (if the Sales Journal is switched on in Settings).";
            case PURCHASES -> "Rule: no cash, Accounts Payable credited (if the Purchases Journal is switched on in Settings).";
            case GENERAL -> "Rule: everything else, plus all adjusting and closing entries. You can override the book on each entry.";
        });
    }
}
