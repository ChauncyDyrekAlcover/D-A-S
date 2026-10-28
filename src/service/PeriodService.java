package service;

import dao.AccountDao;
import dao.JournalDao;
import dao.PeriodDao;
import dao.ReportDao;
import database.Database;
import model.Account;
import model.AccountingPeriod;
import model.EntrySummary;
import model.JournalEntry;
import model.JournalEntry.Journal;
import model.JournalEntry.Status;
import model.JournalEntry.Type;
import model.JournalLine;
import util.AccountingException;
import util.Session;
import util.Dates;
import util.Money;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/** Accounting periods and the controlled period-closing procedure. */
public class PeriodService {
    private final PeriodDao periodDao = new PeriodDao();
    private final JournalDao journalDao = new JournalDao();
    private final AccountDao accountDao = new AccountDao();
    private final ReportDao reportDao = new ReportDao();
    private final AuditService audit = new AuditService();
    private final SettingsService settings = new SettingsService();

    public List<AccountingPeriod> list() {
        return Database.read(periodDao::findAll);
    }

    public AccountingPeriod get(int id) {
        return Database.read(c -> {
            AccountingPeriod p = periodDao.findById(c, id);
            if (p == null) {
                throw new AccountingException("That accounting period no longer exists.");
            }
            return p;
        });
    }

    /** The period that contains today, otherwise the latest open period, otherwise null. */
    public AccountingPeriod current() {
        return Database.read(c -> {
            AccountingPeriod p = periodDao.findByDate(c, LocalDate.now());
            if (p != null) {
                return p;
            }
            AccountingPeriod latestOpen = null;
            for (AccountingPeriod q : periodDao.findAll(c)) {
                if (!q.closed()) {
                    latestOpen = q;
                }
            }
            return latestOpen;
        });
    }

    public void create(String name, LocalDate start, LocalDate end) {
        Session.require("manage accounting periods", "ADMIN");
        if (name == null || name.trim().isEmpty()) {
            throw new AccountingException("Please enter a name for the period (for example \"FY 2027\" or \"January 2027\").");
        }
        if (start == null || end == null) {
            throw new AccountingException("Please enter both the start date and the end date (yyyy-MM-dd).");
        }
        if (end.isBefore(start)) {
            throw new AccountingException("The end date cannot be before the start date.");
        }
        Database.inTransaction(c -> {
            if (periodDao.nameExists(c, name.trim())) {
                throw new AccountingException("A period named \"" + name.trim() + "\" already exists.");
            }
            if (periodDao.overlaps(c, start, end)) {
                throw new AccountingException("These dates overlap an existing accounting period.");
            }
            periodDao.insert(c, name.trim(), start, end);
            audit.log(c, "OPEN PERIOD", "Accounting Period", name.trim(), start + " to " + end);
            return null;
        });
    }

    /** Draft entries dated inside the period. They must be posted or deleted before closing. */
    public List<EntrySummary> outstandingDrafts(AccountingPeriod p) {
        return Database.read(c -> journalDao.search(c, p.start(), p.end(), null, null, Status.DRAFT, null));
    }

    /** Builds (but does not save) the closing entries so the accountant can review them first. */
    public List<JournalEntry> previewClosing(int periodId) {
        return Database.read(c -> {
            AccountingPeriod p = periodDao.findById(c, periodId);
            if (p == null) {
                throw new AccountingException("That accounting period no longer exists.");
            }
            return buildClosingEntries(c, p);
        });
    }

    private List<JournalEntry> buildClosingEntries(Connection c, AccountingPeriod p) throws SQLException {
        Account summary = settings.requireAccount(c, SettingsService.INCOME_SUMMARY, "Income Summary");
        Account capital = settings.requireAccount(c, SettingsService.CAPITAL, "Owner's Capital");
        Account drawings = settings.requireAccount(c, SettingsService.DRAWINGS, "Owner's Drawings");

        Map<Integer, long[]> sums = reportDao.sums(c, null, p.end(), EnumSet.allOf(Type.class));
        List<JournalLine> revenueLines = new ArrayList<>();
        List<JournalLine> expenseLines = new ArrayList<>();
        long revenueTotal = 0;   // credit-natural
        long expenseTotal = 0;   // debit-natural
        for (Account a : accountDao.findAll(c, "", true)) {
            long[] s = sums.get(a.id());
            if (s == null) {
                continue;
            }
            long net = s[0] - s[1]; // debit minus credit
            if (net == 0) {
                continue;
            }
            JournalLine closing = net > 0 ? JournalLine.of(a.id(), "Close " + a.name(), 0, net)
                    : JournalLine.of(a.id(), "Close " + a.name(), -net, 0);
            if (a.type() == Account.Type.REVENUE) {
                revenueLines.add(closing);
                revenueTotal += -net;
            } else if (a.type() == Account.Type.EXPENSE) {
                expenseLines.add(closing);
                expenseTotal += net;
            }
        }
        List<JournalEntry> result = new ArrayList<>();
        String ref = "CLOSE-" + p.name();
        if (!revenueLines.isEmpty()) {
            result.add(closingEntry(p, "Closing entry: transfer revenue accounts to Income Summary", ref,
                    withBalancingLine(revenueLines, summary)));
        }
        if (!expenseLines.isEmpty()) {
            result.add(closingEntry(p, "Closing entry: transfer expense accounts to Income Summary", ref,
                    withBalancingLine(expenseLines, summary)));
        }
        long netIncome = revenueTotal - expenseTotal;
        if (netIncome != 0) {
            List<JournalLine> l = new ArrayList<>();
            if (netIncome > 0) {
                l.add(JournalLine.of(summary.id(), "Net income", netIncome, 0));
                l.add(JournalLine.of(capital.id(), "Net income", 0, netIncome));
            } else {
                l.add(JournalLine.of(capital.id(), "Net loss", -netIncome, 0));
                l.add(JournalLine.of(summary.id(), "Net loss", 0, -netIncome));
            }
            result.add(closingEntry(p, "Closing entry: transfer " + (netIncome > 0 ? "net income" : "net loss")
                    + " to Owner's Capital", ref, l));
        }
        long[] d = sums.get(drawings.id());
        long drawingsNet = d == null ? 0 : d[0] - d[1];
        if (drawingsNet != 0) {
            List<JournalLine> l = new ArrayList<>();
            if (drawingsNet > 0) {
                l.add(JournalLine.of(capital.id(), "Close drawings", drawingsNet, 0));
                l.add(JournalLine.of(drawings.id(), "Close drawings", 0, drawingsNet));
            } else {
                l.add(JournalLine.of(drawings.id(), "Close drawings", -drawingsNet, 0));
                l.add(JournalLine.of(capital.id(), "Close drawings", 0, -drawingsNet));
            }
            result.add(closingEntry(p, "Closing entry: transfer Owner's Drawings to Owner's Capital", ref, l));
        }
        return result;
    }

    private static List<JournalLine> withBalancingLine(List<JournalLine> lines, Account summary) {
        long debits = 0;
        long credits = 0;
        for (JournalLine l : lines) {
            debits += l.debit();
            credits += l.credit();
        }
        List<JournalLine> all = new ArrayList<>(lines);
        long diff = debits - credits;
        all.add(diff > 0 ? JournalLine.of(summary.id(), "Income Summary", 0, diff)
                : JournalLine.of(summary.id(), "Income Summary", -diff, 0));
        return all;
    }

    private static JournalEntry closingEntry(AccountingPeriod p, String description, String ref, List<JournalLine> lines) {
        return JournalEntry.newDraft(p.end(), description, ref, "Closing", "", Type.CLOSING, Journal.GENERAL, lines);
    }

    /**
     * Closes a period: checks the rules, creates and posts the closing entries, then marks the
     * period CLOSED - all in one transaction. Nothing is deleted and no balance is "reset";
     * temporary accounts reach zero because real closing entries are posted.
     */
    public List<JournalEntry> closePeriod(int periodId) {
        Session.require("manage accounting periods", "ADMIN");
        return Database.inTransaction(c -> {
            AccountingPeriod p = periodDao.findById(c, periodId);
            if (p == null) {
                throw new AccountingException("That accounting period no longer exists.");
            }
            if (p.closed()) {
                throw new AccountingException("\"" + p.name() + "\" is already closed.");
            }
            for (AccountingPeriod q : periodDao.findAll(c)) {
                if (q.end().isBefore(p.start()) && !q.closed()) {
                    throw new AccountingException("Close the earlier period \"" + q.name() + "\" first.");
                }
            }
            List<EntrySummary> drafts = journalDao.search(c, p.start(), p.end(), null, null, Status.DRAFT, null);
            if (!drafts.isEmpty()) {
                throw new AccountingException("There are " + drafts.size() + " unposted draft entries in this period (for example "
                        + drafts.get(0).number() + "). Post or delete them before closing.");
            }
            Map<Integer, long[]> sums = reportDao.sums(c, null, p.end(), EnumSet.allOf(Type.class));
            long dr = 0;
            long cr = 0;
            for (long[] s : sums.values()) {
                dr += s[0];
                cr += s[1];
            }
            if (dr != cr) {
                throw new AccountingException("The ledger is out of balance (debits " + Money.format(dr) + ", credits "
                        + Money.format(cr) + "). The period cannot be closed.");
            }
            List<JournalEntry> posted = new ArrayList<>();
            for (JournalEntry e : buildClosingEntries(c, p)) {
                String number = journalDao.nextNumber(c, "CJE", e.date().getYear());
                int id = journalDao.insertEntry(c, e, number, p.id());
                journalDao.insertLines(c, id, e.lines());
                if (!journalDao.markPosted(c, id, Dates.now(), p.id())) {
                    throw new AccountingException("A closing entry could not be posted.");
                }
                audit.log(c, "POST ENTRY", "Journal Entry", number, e.description());
                posted.add(journalDao.findById(c, id));
            }
            periodDao.markClosed(c, p.id(), Dates.now());
            audit.log(c, "CLOSE PERIOD", "Accounting Period", p.name(), posted.size() + " closing entries posted");
            return posted;
        });
    }
}
