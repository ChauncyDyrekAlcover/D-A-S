package ui;

import model.AccountingPeriod;
import model.TrialBalance;
import model.TrialBalanceRow;
import service.PeriodService;
import service.ReportService;
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

/** Modules G, H and J: Trial Balance, Adjusted Trial Balance and Post-Closing Trial Balance. */
public class TrialBalancePanel extends Page {
    private final PeriodService periods = new PeriodService();
    private final ReportService reports = new ReportService();
    private final JComboBox<AccountingPeriod> periodBox = new JComboBox<>();
    private final JComboBox<ReportService.TbMode> modeBox = new JComboBox<>(ReportService.TbMode.values());
    private final JTextField asOf = Theme.field(9);
    private final DataTable table = new DataTable();
    private final JLabel title = Theme.label(" ", 16, true, Theme.TEXT);
    private final JLabel status = Theme.pill(" ", Theme.MUTED, Theme.HEADER_BG);
    private boolean loading = false;

    public TrialBalancePanel() {
        super("Trial Balance", "Calculated from posted ledger balances. Switch between unadjusted, adjusted and post-closing.");
        table.setSortable(false);
        periodBox.setPreferredSize(new Dimension(330, 32));
        modeBox.setPreferredSize(new Dimension(230, 32));
        JPanel bar = Theme.toolbar();
        bar.add(Theme.fieldLabel("PERIOD"));
        bar.add(periodBox);
        bar.add(Theme.fieldLabel("AS OF DATE"));
        bar.add(asOf);
        bar.add(Theme.fieldLabel("REPORT"));
        bar.add(modeBox);
        JButton go = Theme.button("Generate", Theme.Kind.PRIMARY);
        JButton export = Theme.button("Export CSV", Theme.Kind.SECONDARY);
        JButton print = Theme.button("Print", Theme.Kind.SECONDARY);
        bar.add(go);
        bar.add(export);
        bar.add(print);

        JPanel head = Theme.card(new BorderLayout(10, 0));
        head.add(title, BorderLayout.WEST);
        head.add(status, BorderLayout.EAST);
        JPanel north = new JPanel(new BorderLayout(0, 8));
        north.setOpaque(false);
        north.add(bar, BorderLayout.NORTH);
        north.add(head, BorderLayout.SOUTH);
        body.add(north, BorderLayout.NORTH);
        body.add(table, BorderLayout.CENTER);
        table.setColumnWidths(110, 380, 150, 150, 150);

        go.addActionListener(e -> Dialogs.guard(this, this::load));
        modeBox.addActionListener(e -> {
            if (!loading) {
                Dialogs.guard(this, this::load);
            }
        });
        periodBox.addActionListener(e -> {
            if (!loading && periodBox.getSelectedItem() != null) {
                asOf.setText(((AccountingPeriod) periodBox.getSelectedItem()).end().toString());
                Dialogs.guard(this, this::load);
            }
        });
        export.addActionListener(e -> TableTools.exportCsv(this, table.table(), "trial-balance"));
        print.addActionListener(e -> TableTools.print(this, table.table(), String.valueOf(modeBox.getSelectedItem())));
    }

    @Override
    public void refresh() {
        Dialogs.guard(this, () -> {
            loading = true;
            AccountingPeriod keep = (AccountingPeriod) periodBox.getSelectedItem();
            periodBox.removeAllItems();
            AccountingPeriod pick = null;
            for (AccountingPeriod p : periods.list()) {
                periodBox.addItem(p);
                if (keep != null && keep.id() == p.id()) {
                    pick = p;
                }
            }
            if (pick == null) {
                pick = periods.current();
            }
            if (pick != null) {
                periodBox.setSelectedIndex(indexOf(pick));
                if (keep == null || asOf.getText().isBlank()) {
                    asOf.setText(pick.end().toString());
                }
            }
            loading = false;
            load();
        });
    }

    private int indexOf(AccountingPeriod p) {
        for (int i = 0; i < periodBox.getItemCount(); i++) {
            if (periodBox.getItemAt(i).id() == p.id()) {
                return i;
            }
        }
        return 0;
    }

    private void load() {
        AccountingPeriod p = (AccountingPeriod) periodBox.getSelectedItem();
        LocalDate date = Dates.require(asOf.getText(), "as-of date");
        ReportService.TbMode mode = (ReportService.TbMode) modeBox.getSelectedItem();
        TrialBalance tb = reports.trialBalance(p == null ? null : p.start(), date, mode);
        List<Object[]> rows = new ArrayList<>();
        for (TrialBalanceRow r : tb.rows()) {
            rows.add(new Object[]{r.number(), r.name(), r.type().toString(), Money.formatOrDash(r.debit()),
                Money.formatOrDash(r.credit())});
        }
        List<Integer> bold = new ArrayList<>();
        bold.add(rows.size());
        rows.add(new Object[]{"", "TOTALS", "", Money.format(tb.totalDebit()), Money.format(tb.totalCredit())});
        table.setData(new String[]{"Account No.", "Account Name", "Type", "Debit", "Credit"}, rows, new ArrayList<>(), bold);
        title.setText(modeBox.getSelectedItem() + " as of " + date);
        if (tb.rows().isEmpty()) {
            Theme.setPill(status, "No posted transactions", Theme.MUTED, Theme.HEADER_BG);
        } else if (tb.balanced()) {
            Theme.setPill(status, "BALANCED  \u2022  difference " + Money.format(0), Theme.GREEN, Theme.GREEN_BG);
        } else {
            Theme.setPill(status, "OUT OF BALANCE  \u2022  difference " + Money.format(Math.abs(tb.totalDebit() - tb.totalCredit())),
                    Theme.RED, Theme.RED_BG);
        }
    }
}
