package ui;

import model.AccountingPeriod;
import model.AuditRecord;
import model.JournalEntry.Status;
import service.AuditService;
import service.JournalService;
import service.PeriodService;
import service.ReportService;
import util.Money;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Module A: the home screen. */
public class DashboardPanel extends Page {
    private final JournalService journal = new JournalService();
    private final PeriodService periods = new PeriodService();
    private final ReportService reports = new ReportService();
    private final AuditService audit = new AuditService();
    private final JLabel periodValue = Theme.label("-", 20, true, Theme.TEXT);
    private final JLabel periodNote = Theme.label(" ", 12, false, Theme.MUTED);
    private final JLabel totalValue = Theme.label("0", 26, true, Theme.TEXT);
    private final JLabel draftValue = Theme.label("0", 26, true, Theme.AMBER);
    private final JLabel postedValue = Theme.label("0", 26, true, Theme.GREEN);
    private final JLabel debitValue = Theme.label("-", 16, true, Theme.TEXT);
    private final JLabel creditValue = Theme.label("-", 16, true, Theme.TEXT);
    private final JComboBox<Object> periodBox = new JComboBox<>(new Object[]{"All periods"});
    private final DataTable recent = new DataTable();
    private boolean loading = false;

    public DashboardPanel(Navigator nav) {
        super("Dashboard", "A quick view of your books. Encode a transaction once - the ledger, journals and reports update themselves.");
        recent.setSortable(false);
        periodBox.setPreferredSize(new Dimension(300, 32));

        JPanel cards = new JPanel(new GridLayout(1, 5, 12, 0));
        cards.setOpaque(false);
        cards.add(stat("CURRENT PERIOD", periodValue, periodNote, Theme.ACCENT));
        cards.add(stat("TOTAL ENTRIES", totalValue, Theme.label("all statuses", 12, false, Theme.MUTED), Theme.NAVY_LIGHT));
        cards.add(stat("UNPOSTED (DRAFT)", draftValue, Theme.label("waiting for review", 12, false, Theme.MUTED), Theme.AMBER));
        cards.add(stat("POSTED", postedValue, Theme.label("in the ledger", 12, false, Theme.MUTED), Theme.GREEN));
        JPanel totals = Theme.card(new BorderLayout(0, 4));
        totals.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 4, 0, 0, Theme.NAVY),
                BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.BORDER), Theme.pad(12, 12, 12, 12))));
        JPanel tv = new JPanel(new GridLayout(2, 1, 0, 0));
        tv.setOpaque(false);
        JPanel d = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        d.setOpaque(false);
        d.add(Theme.fieldLabel("DEBITS"));
        d.add(debitValue);
        JPanel c = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        c.setOpaque(false);
        c.add(Theme.fieldLabel("CREDITS"));
        c.add(creditValue);
        tv.add(d);
        tv.add(c);
        periodBox.setPreferredSize(new Dimension(200, 28));
        totals.add(periodBox, BorderLayout.NORTH);
        totals.setPreferredSize(new Dimension(260, 130));
        totals.add(tv, BorderLayout.CENTER);
        cards.add(totals);

        JPanel shortcuts = Theme.toolbar();
        shortcuts.add(Theme.fieldLabel("SHORTCUTS"));
        shortcuts.add(shortcut("General Journal", "journal", nav, Theme.Kind.PRIMARY));
        shortcuts.add(shortcut("General Ledger", "ledger", nav, Theme.Kind.SECONDARY));
        shortcuts.add(shortcut("Columnar Journals", "columnar", nav, Theme.Kind.SECONDARY));
        shortcuts.add(shortcut("Trial Balance", "trial", nav, Theme.Kind.SECONDARY));
        shortcuts.add(shortcut("Financial Statements", "statements", nav, Theme.Kind.SECONDARY));

        JPanel top = new JPanel(new BorderLayout(0, 12));
        top.setOpaque(false);
        top.add(cards, BorderLayout.NORTH);
        top.add(shortcuts, BorderLayout.SOUTH);

        JPanel activity = new JPanel(new BorderLayout(0, 8));
        activity.setOpaque(false);
        activity.add(Theme.label("Recent accounting activity", 16, true, Theme.TEXT), BorderLayout.NORTH);
        activity.add(recent, BorderLayout.CENTER);
        body.add(top, BorderLayout.NORTH);
        body.add(activity, BorderLayout.CENTER);
        recent.setColumnWidths(150, 180, 140, 520);

        periodBox.addActionListener(e -> {
            if (!loading) {
                Dialogs.guard(this, this::loadTotals);
            }
        });
    }

    private JButton shortcut(String text, String page, Navigator nav, Theme.Kind kind) {
        JButton b = Theme.button(text, kind);
        b.addActionListener(e -> nav.goTo(page));
        return b;
    }

    private JPanel stat(String title, JLabel value, JLabel note, Color accent) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBackground(Theme.CARD);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 4, 0, 0, accent),
                BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.BORDER), Theme.pad(12, 12, 12, 12))));
        p.add(Theme.fieldLabel(title), BorderLayout.NORTH);
        p.add(value, BorderLayout.CENTER);
        p.add(note, BorderLayout.SOUTH);
        return p;
    }

    @Override
    public void refresh() {
        Dialogs.guard(this, () -> {
            AccountingPeriod current = periods.current();
            periodValue.setText(current == null ? "None" : current.name());
            periodNote.setText(current == null ? "create one under Accounting Periods"
                    : current.start() + " to " + current.end() + (current.closed() ? " (closed)" : " (open)"));
            Map<Status, Integer> counts = journal.countByStatus();
            int posted = counts.get(Status.POSTED) + counts.get(Status.REVERSED);
            totalValue.setText(String.valueOf(posted + counts.get(Status.DRAFT)));
            draftValue.setText(String.valueOf(counts.get(Status.DRAFT)));
            postedValue.setText(String.valueOf(posted));

            loading = true;
            Object keep = periodBox.getSelectedItem();
            periodBox.removeAllItems();
            periodBox.addItem("All periods");
            Object pick = "All periods";
            for (AccountingPeriod p : periods.list()) {
                periodBox.addItem(p);
                if (keep instanceof AccountingPeriod k && k.id() == p.id()) {
                    pick = p;
                } else if (keep == null && current != null && p.id() == current.id()) {
                    pick = p;
                }
            }
            periodBox.setSelectedItem(pick);
            loading = false;
            loadTotals();

            List<Object[]> rows = new ArrayList<>();
            for (AuditRecord r : audit.search("", 10)) {
                rows.add(new Object[]{r.time(), r.action(), r.recordRef(), r.details()});
            }
            recent.setData(new String[]{"Time", "Action", "Reference", "Details"}, rows);
        });
    }

    private void loadTotals() {
        long[] t;
        if (periodBox.getSelectedItem() instanceof AccountingPeriod p) {
            t = reports.totals(p.start(), p.end());
        } else {
            t = reports.totals(null, null);
        }
        debitValue.setText(Money.format(t[0]));
        creditValue.setText(Money.format(t[1]));
    }
}
