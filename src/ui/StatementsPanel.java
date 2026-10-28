package ui;

import model.AccountingPeriod;
import model.Statement;
import model.StatementLine;
import service.PeriodService;
import service.ReportService;
import util.Dates;
import util.Money;

import javax.swing.BoxLayout;
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

/** Module I: Income Statement, Statement of Changes in Equity, Balance Sheet. */
public class StatementsPanel extends Page {
    private static final String INCOME = "Income Statement";
    private static final String EQUITY = "Statement of Changes in Equity";
    private static final String BALANCE = "Balance Sheet";

    private final PeriodService periods = new PeriodService();
    private final ReportService reports = new ReportService();
    private final JComboBox<String> reportBox = new JComboBox<>(new String[]{INCOME, EQUITY, BALANCE});
    private final JComboBox<AccountingPeriod> periodBox = new JComboBox<>();
    private final JTextField asOf = Theme.field(9);
    private final DataTable table = new DataTable();
    private final JLabel title = Theme.label(" ", 18, true, Theme.TEXT);
    private final JLabel subtitle = Theme.label(" ", 12, false, Theme.MUTED);
    private final JPanel warnings = new JPanel();
    private boolean loading = false;

    public StatementsPanel() {
        super("Financial Statements", "Generated from saved, posted records. Warnings appear when the data is incomplete or inconsistent.");
        table.setSortable(false);
        periodBox.setPreferredSize(new Dimension(330, 32));
        reportBox.setPreferredSize(new Dimension(250, 32));
        JPanel bar = Theme.toolbar();
        bar.add(Theme.fieldLabel("STATEMENT"));
        bar.add(reportBox);
        bar.add(Theme.fieldLabel("PERIOD"));
        bar.add(periodBox);
        bar.add(Theme.fieldLabel("BALANCE SHEET DATE"));
        bar.add(asOf);
        JButton go = Theme.button("Generate", Theme.Kind.PRIMARY);
        JButton export = Theme.button("Export CSV", Theme.Kind.SECONDARY);
        JButton print = Theme.button("Print", Theme.Kind.SECONDARY);
        bar.add(go);
        bar.add(export);
        bar.add(print);

        warnings.setLayout(new BoxLayout(warnings, BoxLayout.Y_AXIS));
        warnings.setOpaque(false);
        JPanel head = Theme.card(new BorderLayout(0, 2));
        JPanel titles = new JPanel(new BorderLayout());
        titles.setOpaque(false);
        titles.add(title, BorderLayout.NORTH);
        titles.add(subtitle, BorderLayout.SOUTH);
        head.add(titles, BorderLayout.CENTER);

        JPanel north = new JPanel(new BorderLayout(0, 8));
        north.setOpaque(false);
        north.add(bar, BorderLayout.NORTH);
        JPanel mid = new JPanel(new BorderLayout(0, 8));
        mid.setOpaque(false);
        mid.add(head, BorderLayout.NORTH);
        mid.add(warnings, BorderLayout.SOUTH);
        north.add(mid, BorderLayout.SOUTH);
        body.add(north, BorderLayout.NORTH);
        body.add(table, BorderLayout.CENTER);
        table.setColumnWidths(560, 200);

        go.addActionListener(e -> Dialogs.guard(this, this::load));
        reportBox.addActionListener(e -> {
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
        export.addActionListener(e -> TableTools.exportCsv(this, table.table(), "financial-statement"));
        print.addActionListener(e -> TableTools.print(this, table.table(), String.valueOf(reportBox.getSelectedItem())));
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
                for (int i = 0; i < periodBox.getItemCount(); i++) {
                    if (periodBox.getItemAt(i).id() == pick.id()) {
                        periodBox.setSelectedIndex(i);
                    }
                }
                if (keep == null || asOf.getText().isBlank()) {
                    asOf.setText(pick.end().toString());
                }
            }
            loading = false;
            load();
        });
    }

    private void load() {
        AccountingPeriod p = (AccountingPeriod) periodBox.getSelectedItem();
        if (p == null) {
            Dialogs.warn(this, "Create an accounting period first (Accounting Periods).");
            return;
        }
        String which = (String) reportBox.getSelectedItem();
        Statement s;
        if (BALANCE.equals(which)) {
            LocalDate date = Dates.require(asOf.getText(), "balance sheet date");
            s = reports.balanceSheet(date);
        } else if (EQUITY.equals(which)) {
            s = reports.equityStatement(p);
        } else {
            s = reports.incomeStatement(p);
        }
        List<Object[]> rows = new ArrayList<>();
        List<Integer> bold = new ArrayList<>();
        for (StatementLine l : s.lines()) {
            if (l.bold()) {
                bold.add(rows.size());
            }
            rows.add(new Object[]{"      ".repeat(l.indent()) + l.label(), l.amount() == null ? "" : Money.statement(l.amount())});
        }
        table.setData(new String[]{"Description", "Amount"}, rows, new ArrayList<>(), bold);
        title.setText(s.title());
        subtitle.setText(s.subtitle());
        warnings.removeAll();
        for (String w : s.warnings()) {
            JPanel box = new JPanel(new BorderLayout());
            box.setBackground(Theme.AMBER_BG);
            box.setBorder(Theme.pad(8, 12, 8, 12));
            box.add(Theme.label("Warning: " + w, 12, true, Theme.AMBER), BorderLayout.CENTER);
            box.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            warnings.add(box);
            warnings.add(javax.swing.Box.createVerticalStrut(4));
        }
        warnings.revalidate();
        warnings.repaint();
    }
}
