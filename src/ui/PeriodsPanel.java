package ui;

import model.Account;
import model.AccountingPeriod;
import model.EntrySummary;
import model.JournalEntry;
import model.JournalLine;
import service.AccountService;
import service.PeriodService;
import util.AccountingException;
import util.Dates;
import util.Money;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Module J: accounting periods and the controlled closing procedure. */
public class PeriodsPanel extends Page {
    private final PeriodService service = new PeriodService();
    private final AccountService accountService = new AccountService();
    private final DataTable table = new DataTable();
    private final Navigator nav;

    public PeriodsPanel(Navigator nav) {
        super("Accounting Periods", "Open periods accept postings. Closing a period posts closing entries and locks it permanently.");
        this.nav = nav;
        JPanel bar = Theme.toolbar();
        JButton create = Theme.button("New Period", Theme.Kind.PRIMARY);
        JButton review = Theme.button("Review Unposted Entries", Theme.Kind.SECONDARY);
        JButton close = Theme.button("Close Selected Period", Theme.Kind.DANGER);
        JButton pctb = Theme.button("Post-Closing Trial Balance \u2192", Theme.Kind.SECONDARY);
        bar.add(create);
        bar.add(review);
        bar.add(close);
        bar.add(pctb);
        body.add(bar, BorderLayout.NORTH);
        body.add(table, BorderLayout.CENTER);
        table.setColumnWidths(200, 110, 110, 100, 170, 140);

        create.addActionListener(e -> Dialogs.guard(this, this::createPeriod));
        review.addActionListener(e -> Dialogs.guard(this, this::reviewDrafts));
        close.addActionListener(e -> Dialogs.guard(this, this::closeSelected));
        pctb.addActionListener(e -> nav.goTo("trial"));
    }

    @Override
    public void refresh() {
        Dialogs.guard(this, () -> {
            List<Object[]> rows = new ArrayList<>();
            List<Integer> ids = new ArrayList<>();
            for (AccountingPeriod p : service.list()) {
                int drafts = service.outstandingDrafts(p).size();
                rows.add(new Object[]{p.name(), p.start().toString(), p.end().toString(), p.closed() ? "CLOSED" : "OPEN",
                    p.closedAt() == null ? "" : p.closedAt(), drafts == 0 ? "none" : drafts + " unposted"});
                ids.add(p.id());
            }
            table.setSortable(false);
            table.setData(new String[]{"Period", "Start", "End", "Status", "Closed At", "Draft Entries"}, rows, ids, List.of());
        });
    }

    private AccountingPeriod selected() {
        int id = table.selectedId();
        if (id < 0) {
            throw new AccountingException("Please select a period in the table first.");
        }
        return service.get(id);
    }

    private void createPeriod() {
        LocalDate suggestedStart = LocalDate.now().withDayOfYear(1);
        List<AccountingPeriod> all = service.list();
        if (!all.isEmpty()) {
            suggestedStart = all.get(all.size() - 1).end().plusDays(1);
        }
        JTextField name = Theme.field(18);
        JTextField start = Theme.field(12);
        JTextField end = Theme.field(12);
        name.setText("FY " + suggestedStart.getYear());
        start.setText(suggestedStart.toString());
        end.setText(suggestedStart.plusYears(1).minusDays(1).toString());
        JPanel form = Forms.panel();
        form.setPreferredSize(new Dimension(380, 160));
        Forms.row(form, 0, "PERIOD NAME", name);
        Forms.row(form, 1, "START DATE (yyyy-MM-dd)", start);
        Forms.row(form, 2, "END DATE (yyyy-MM-dd)", end);
        if (JOptionPane.showConfirmDialog(this, form, "New Accounting Period", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            service.create(name.getText(), Dates.require(start.getText(), "start date"), Dates.require(end.getText(), "end date"));
            refresh();
        }
    }

    private void reviewDrafts() {
        AccountingPeriod p = selected();
        List<EntrySummary> drafts = service.outstandingDrafts(p);
        if (drafts.isEmpty()) {
            Dialogs.info(this, "There are no unposted draft entries in " + p.name() + ".");
            return;
        }
        StringBuilder sb = new StringBuilder("These draft entries are dated inside " + p.name() + " and must be posted or deleted before closing:\n\n");
        for (EntrySummary e : drafts) {
            sb.append(e.number()).append("   ").append(e.date()).append("   ").append(e.description())
                    .append("   ").append(Money.format(e.amount())).append("\n");
        }
        showText("Unposted entries", sb.toString());
    }

    private void closeSelected() {
        AccountingPeriod p = selected();
        if (p.closed()) {
            throw new AccountingException("\"" + p.name() + "\" is already closed.");
        }
        List<EntrySummary> drafts = service.outstandingDrafts(p);
        if (!drafts.isEmpty()) {
            throw new AccountingException("There are " + drafts.size() + " unposted draft entries in this period. Use \"Review Unposted Entries\", then post or delete them.");
        }
        List<JournalEntry> preview = service.previewClosing(p.id());
        Map<Integer, String> names = new HashMap<>();
        for (Account a : accountService.list("", true)) {
            names.put(a.id(), a.toString());
        }
        StringBuilder sb = new StringBuilder("Closing " + p.name() + " will post these closing entries dated " + p.end() + ":\n\n");
        if (preview.isEmpty()) {
            sb.append("(no temporary account balances to close)\n");
        }
        for (JournalEntry e : preview) {
            sb.append(e.description()).append("\n");
            for (JournalLine l : e.lines()) {
                sb.append(l.debit() > 0 ? "      Dr  " : "            Cr  ").append(String.format("%-42s", names.get(l.accountId())))
                        .append(Money.format(l.debit() > 0 ? l.debit() : l.credit())).append("\n");
            }
            sb.append("\n");
        }
        sb.append("After closing, no more entries can be posted into this period. This cannot be undone.");
        JTextArea area = new JTextArea(sb.toString(), 20, 70);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        if (JOptionPane.showConfirmDialog(this, new JScrollPane(area), "Review closing entries - close " + p.name() + "?",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        List<JournalEntry> posted = service.closePeriod(p.id());
        refresh();
        Dialogs.info(this, p.name() + " is now closed. " + posted.size() + " closing entries were posted.\n"
                + "Open \"Trial Balance\" and choose \"Post-Closing Trial Balance\" to verify.");
    }

    private void showText(String title, String text) {
        JTextArea area = new JTextArea(text, 14, 70);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JOptionPane.showMessageDialog(this, new JScrollPane(area), title, JOptionPane.PLAIN_MESSAGE);
    }
}
