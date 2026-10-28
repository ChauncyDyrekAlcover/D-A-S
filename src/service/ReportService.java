package service;

import dao.AccountDao;
import dao.JournalDao;
import dao.ReportDao;
import database.Database;
import model.Account;
import model.AccountingPeriod;
import model.ColumnarJournal;
import model.JournalEntry;
import model.JournalEntry.Journal;
import model.JournalEntry.Status;
import model.JournalEntry.Type;
import model.LedgerResult;
import model.LedgerRow;
import model.Statement;
import model.StatementLine;
import model.TrialBalance;
import model.TrialBalanceRow;
import util.AccountingException;
import util.Dates;
import util.Money;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * All reports are CALCULATED from posted journal lines every time they are requested.
 * Nothing is copied into separate "report tables", so reports cannot drift out of date.
 */
public class ReportService {

    public enum TbMode {
        UNADJUSTED("Unadjusted Trial Balance"),
        ADJUSTED("Adjusted Trial Balance"),
        POST_CLOSING("Post-Closing Trial Balance");
        private final String label;
        TbMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final ReportDao reportDao = new ReportDao();
    private final AccountDao accountDao = new AccountDao();
    private final JournalDao journalDao = new JournalDao();
    private final SettingsService settings = new SettingsService();

    private static final Set<Type> BEFORE_CLOSING = EnumSet.of(Type.REGULAR, Type.ADJUSTING);

    /** Amount of an account in the direction its TYPE normally increases (contra accounts come out negative). */
    private static long natural(Account a, Map<Integer, long[]> sums) {
        long[] s = sums.get(a.id());
        if (s == null) {
            return 0;
        }
        return a.type().natural() == Account.Normal.DEBIT ? s[0] - s[1] : s[1] - s[0];
    }

    // ------------------------------------------------------------------ dashboard

    /** [total debits, total credits] of posted, non-closing entries in a date range. */
    public long[] totals(LocalDate from, LocalDate to) {
        return Database.read(c -> {
            long dr = 0;
            long cr = 0;
            for (long[] s : reportDao.sums(c, from, to, BEFORE_CLOSING).values()) {
                dr += s[0];
                cr += s[1];
            }
            return new long[]{dr, cr};
        });
    }

    // ------------------------------------------------------------------ general ledger

    public LedgerResult ledger(int accountId, LocalDate from, LocalDate to) {
        return Database.read(c -> {
            Account a = accountDao.findById(c, accountId);
            if (a == null) {
                throw new AccountingException("Please choose an account.");
            }
            boolean debitNormal = a.normal() == Account.Normal.DEBIT;
            long balance = 0;
            if (from != null) {
                long[] open = reportDao.openingSums(c, accountId, from);
                balance = debitNormal ? open[0] - open[1] : open[1] - open[0];
            }
            long opening = balance;
            long totalDr = 0;
            long totalCr = 0;
            List<LedgerRow> rows = new ArrayList<>();
            for (ReportDao.LedgerLine l : reportDao.ledgerLines(c, accountId, from, to)) {
                balance += debitNormal ? l.debit() - l.credit() : l.credit() - l.debit();
                totalDr += l.debit();
                totalCr += l.credit();
                rows.add(new LedgerRow(l.entryId(), l.date(), l.number(), l.reference(), l.description(),
                        l.debit(), l.credit(), balance, l.period()));
            }
            return new LedgerResult(a, opening, rows, totalDr, totalCr, balance);
        });
    }

    // ------------------------------------------------------------------ trial balance

    private static void merge(Map<Integer, long[]> target, Map<Integer, long[]> extra) {
        for (Map.Entry<Integer, long[]> e : extra.entrySet()) {
            long[] t = target.computeIfAbsent(e.getKey(), k -> new long[2]);
            t[0] += e.getValue()[0];
            t[1] += e.getValue()[1];
        }
    }

    /**
     * Trial balance as of a date. Everything BEFORE periodStart is included in full (earlier periods,
     * including their closing entries). Inside the period, the mode decides which entry types count:
     * unadjusted = regular only, adjusted = regular + adjusting, post-closing = everything.
     */
    public TrialBalance trialBalance(LocalDate periodStart, LocalDate asOf, TbMode mode) {
        if (asOf == null) {
            throw new AccountingException("Please enter the trial balance date.");
        }
        return Database.read(c -> {
            Map<Integer, long[]> sums = new HashMap<>();
            if (periodStart != null) {
                merge(sums, reportDao.sums(c, null, periodStart.minusDays(1), EnumSet.allOf(Type.class)));
            }
            Set<Type> types = switch (mode) {
                case UNADJUSTED -> EnumSet.of(Type.REGULAR);
                case ADJUSTED -> BEFORE_CLOSING;
                case POST_CLOSING -> EnumSet.allOf(Type.class);
            };
            merge(sums, reportDao.sums(c, periodStart, asOf, types));
            List<TrialBalanceRow> rows = new ArrayList<>();
            long totalDr = 0;
            long totalCr = 0;
            for (Account a : accountDao.findAll(c, "", true)) {
                long[] s = sums.get(a.id());
                if (s == null) {
                    continue;
                }
                long net = s[0] - s[1];
                if (net == 0) {
                    continue;
                }
                long dr = net > 0 ? net : 0;
                long cr = net < 0 ? -net : 0;
                rows.add(new TrialBalanceRow(a.number(), a.name(), a.type(), dr, cr));
                totalDr += dr;
                totalCr += cr;
            }
            return new TrialBalance(rows, totalDr, totalCr);
        });
    }

    // ------------------------------------------------------------------ columnar journals

    /**
     * Pivots the lines of one journal into columns: one column for every account/side that was used
     * (debit columns first, then credit columns), one row per entry, with monthly and period totals.
     * Each entry belongs to exactly ONE journal, so nothing is counted twice.
     */
    public ColumnarJournal columnar(Journal journal, LocalDate from, LocalDate to) {
        return Database.read(c -> {
            List<ReportDao.RawLine> lines = reportDao.journalLines(c, journal, from, to);
            TreeMap<String, String> drCols = new TreeMap<>();
            TreeMap<String, String> crCols = new TreeMap<>();
            for (ReportDao.RawLine l : lines) {
                if (l.debit() > 0) {
                    drCols.put(l.accountNumber(), l.accountNumber() + " " + l.accountName() + " (Dr)");
                }
                if (l.credit() > 0) {
                    crCols.put(l.accountNumber(), l.accountNumber() + " " + l.accountName() + " (Cr)");
                }
            }
            List<String> keys = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (Map.Entry<String, String> e : drCols.entrySet()) {
                keys.add("D" + e.getKey());
                labels.add(e.getValue());
            }
            int drCount = keys.size();
            for (Map.Entry<String, String> e : crCols.entrySet()) {
                keys.add("C" + e.getKey());
                labels.add(e.getValue());
            }
            int n = keys.size();
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < n; i++) {
                index.put(keys.get(i), i);
            }

            Map<Integer, List<ReportDao.RawLine>> byEntry = new LinkedHashMap<>();
            java.util.Set<String> months = new java.util.LinkedHashSet<>();
            for (ReportDao.RawLine l : lines) {
                byEntry.computeIfAbsent(l.entryId(), k -> new ArrayList<>()).add(l);
                months.add(l.date().getYear() + "-" + l.date().getMonthValue());
            }
            boolean showMonthly = months.size() > 1;

            List<String> headers = new ArrayList<>(List.of("Date", "Entry No.", "Ref. No.", "Description"));
            headers.addAll(labels);
            List<String[]> rows = new ArrayList<>();
            List<Integer> totalRows = new ArrayList<>();
            long[] grand = new long[n];
            long[] month = new long[n];
            LocalDate monthDate = null;

            for (List<ReportDao.RawLine> entryLines : byEntry.values()) {
                ReportDao.RawLine first = entryLines.get(0);
                if (showMonthly && monthDate != null
                        && (monthDate.getMonth() != first.date().getMonth() || monthDate.getYear() != first.date().getYear())) {
                    totalRows.add(rows.size());
                    rows.add(totalRow("TOTAL - " + Dates.monthLabel(monthDate), month, n));
                    month = new long[n];
                }
                monthDate = first.date();
                long[] amt = new long[n];
                for (ReportDao.RawLine l : entryLines) {
                    if (l.debit() > 0) {
                        amt[index.get("D" + l.accountNumber())] += l.debit();
                    }
                    if (l.credit() > 0) {
                        amt[index.get("C" + l.accountNumber())] += l.credit();
                    }
                }
                String[] row = new String[4 + n];
                row[0] = first.date().toString();
                row[1] = first.number();
                row[2] = first.reference();
                row[3] = first.description();
                for (int i = 0; i < n; i++) {
                    row[4 + i] = amt[i] == 0 ? "" : Money.format(amt[i]);
                    grand[i] += amt[i];
                    month[i] += amt[i];
                }
                rows.add(row);
            }
            if (showMonthly && monthDate != null) {
                totalRows.add(rows.size());
                rows.add(totalRow("TOTAL - " + Dates.monthLabel(monthDate), month, n));
            }
            if (!byEntry.isEmpty()) {
                totalRows.add(rows.size());
                rows.add(totalRow("PERIOD TOTAL", grand, n));
            }
            Map<String, Long> totals = new LinkedHashMap<>();
            long totalDr = 0;
            long totalCr = 0;
            for (int i = 0; i < n; i++) {
                totals.put(labels.get(i), grand[i]);
                if (i < drCount) {
                    totalDr += grand[i];
                } else {
                    totalCr += grand[i];
                }
            }
            return new ColumnarJournal(headers, rows, totalRows, totals, totalDr, totalCr);
        });
    }

    private static String[] totalRow(String label, long[] amounts, int n) {
        String[] row = new String[4 + n];
        java.util.Arrays.fill(row, "");
        row[3] = label;
        for (int i = 0; i < n; i++) {
            row[4 + i] = amounts[i] == 0 ? "" : Money.format(amounts[i]);
        }
        return row;
    }

    // ------------------------------------------------------------------ financial statements

    private static long netIncome(List<Account> accounts, Map<Integer, long[]> sums) {
        long ni = 0;
        for (Account a : accounts) {
            if (a.type() == Account.Type.REVENUE) {
                ni += natural(a, sums);
            } else if (a.type() == Account.Type.EXPENSE) {
                ni -= natural(a, sums);
            }
        }
        return ni;
    }

    /** Total owner's equity = equity accounts + income earned but not yet closed. */
    private static long totalEquity(List<Account> accounts, Map<Integer, long[]> sums) {
        long eq = 0;
        for (Account a : accounts) {
            if (a.type() == Account.Type.EQUITY) {
                eq += natural(a, sums);
            }
        }
        return eq + netIncome(accounts, sums);
    }

    private void draftWarning(Connection c, LocalDate from, LocalDate to, List<String> warnings) throws SQLException {
        int drafts = journalDao.search(c, from, to, null, null, Status.DRAFT, null).size();
        if (drafts > 0) {
            warnings.add(drafts + " draft entr" + (drafts == 1 ? "y" : "ies")
                    + " in this date range " + (drafts == 1 ? "is" : "are")
                    + " not posted and NOT included in this report.");
        }
    }

    private String subtitle(Connection c, String text) throws SQLException {
        return settings.get(c, SettingsService.BUSINESS_NAME) + "   |   " + text;
    }

    public Statement incomeStatement(AccountingPeriod p) {
        return Database.read(c -> {
            Map<Integer, long[]> sums = reportDao.sums(c, p.start(), p.end(), BEFORE_CLOSING);
            List<Account> accounts = accountDao.findAll(c, "", true);
            List<StatementLine> lines = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            lines.add(new StatementLine("Revenues", null, 0, true));
            long revenue = 0;
            for (Account a : accounts) {
                long v = natural(a, sums);
                if (a.type() == Account.Type.REVENUE && v != 0) {
                    lines.add(new StatementLine(a.name(), v, 1, false));
                    revenue += v;
                }
            }
            lines.add(new StatementLine("Total Revenues", revenue, 0, true));
            lines.add(new StatementLine("Expenses", null, 0, true));
            long expenses = 0;
            for (Account a : accounts) {
                long v = natural(a, sums);
                if (a.type() == Account.Type.EXPENSE && v != 0) {
                    lines.add(new StatementLine(a.name(), v, 1, false));
                    expenses += v;
                }
            }
            lines.add(new StatementLine("Total Expenses", expenses, 0, true));
            long ni = revenue - expenses;
            lines.add(new StatementLine(ni >= 0 ? "NET INCOME" : "NET LOSS", ni, 0, true));

            draftWarning(c, p.start(), p.end(), warnings);
            if (sums.isEmpty()) {
                warnings.add("There are no posted transactions in this period.");
            }
            int adjusting = 0;
            for (var e : journalDao.search(c, p.start(), p.end(), null, null, null, Type.ADJUSTING)) {
                if (e.status() != Status.DRAFT) {
                    adjusting++;
                }
            }
            if (adjusting == 0) {
                warnings.add("No adjusting entries have been posted for this period. The statement shows UNADJUSTED balances.");
            }
            return new Statement("Income Statement", subtitle(c, "For the period " + p.start() + " to " + p.end()),
                    lines, warnings);
        });
    }

    public Statement equityStatement(AccountingPeriod p) {
        return Database.read(c -> {
            List<Account> accounts = accountDao.findAll(c, "", true);
            Account capital = settings.accountFor(c, SettingsService.CAPITAL);
            Account drawings = settings.accountFor(c, SettingsService.DRAWINGS);
            Account summary = settings.accountFor(c, SettingsService.INCOME_SUMMARY);
            List<String> warnings = new ArrayList<>();
            if (capital == null || drawings == null) {
                warnings.add("The Owner's Capital or Owner's Drawings account is not set under Settings, so investments and drawings cannot be shown separately.");
            }
            Map<Integer, long[]> before = reportDao.sums(c, null, p.start().minusDays(1), EnumSet.allOf(Type.class));
            Map<Integer, long[]> during = reportDao.sums(c, p.start(), p.end(), BEFORE_CLOSING);
            long beginning = totalEquity(accounts, before);
            long ni = netIncome(accounts, during);
            long investments = 0;
            long draws = 0;
            long other = 0;
            for (Account a : accounts) {
                if (a.type() != Account.Type.EQUITY) {
                    continue;
                }
                long v = natural(a, during);
                if (capital != null && a.id() == capital.id()) {
                    investments += v;
                } else if (drawings != null && a.id() == drawings.id()) {
                    draws += -v; // natural is negative for a debit balance
                } else if (summary == null || a.id() != summary.id()) {
                    other += v;
                }
            }
            long ending = beginning + investments + ni - draws + other;
            List<StatementLine> lines = new ArrayList<>();
            lines.add(new StatementLine("Owner's Capital, beginning of period", beginning, 0, true));
            lines.add(new StatementLine("Add: Additional investments", investments, 1, false));
            lines.add(new StatementLine(ni >= 0 ? "Add: Net income" : "Less: Net loss", ni, 1, false));
            lines.add(new StatementLine("Less: Owner's drawings", -draws, 1, false));
            if (other != 0) {
                lines.add(new StatementLine("Other changes in equity", other, 1, false));
            }
            lines.add(new StatementLine("Owner's Capital, end of period", ending, 0, true));

            long balanceSheetEquity = totalEquity(accounts, reportDao.sums(c, null, p.end(), EnumSet.allOf(Type.class)));
            if (balanceSheetEquity != ending) {
                warnings.add("Ending equity (" + Money.format(ending) + ") does not agree with the Balance Sheet ("
                        + Money.format(balanceSheetEquity) + "). Check for entries posted directly to equity accounts.");
            }
            draftWarning(c, p.start(), p.end(), warnings);
            return new Statement("Statement of Changes in Equity",
                    subtitle(c, "For the period " + p.start() + " to " + p.end()), lines, warnings);
        });
    }

    public Statement balanceSheet(LocalDate asOf) {
        if (asOf == null) {
            throw new AccountingException("Please enter the balance sheet date.");
        }
        return Database.read(c -> {
            Map<Integer, long[]> sums = reportDao.sums(c, null, asOf, EnumSet.allOf(Type.class));
            List<Account> accounts = accountDao.findAll(c, "", true);
            List<StatementLine> lines = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            lines.add(new StatementLine("Assets", null, 0, true));
            long assets = 0;
            for (Account a : accounts) {
                long v = natural(a, sums);
                if (a.type() == Account.Type.ASSET && v != 0) {
                    lines.add(new StatementLine(a.name(), v, 1, false));
                    assets += v;
                }
            }
            lines.add(new StatementLine("Total Assets", assets, 0, true));

            lines.add(new StatementLine("Liabilities", null, 0, true));
            long liabilities = 0;
            for (Account a : accounts) {
                long v = natural(a, sums);
                if (a.type() == Account.Type.LIABILITY && v != 0) {
                    lines.add(new StatementLine(a.name(), v, 1, false));
                    liabilities += v;
                }
            }
            lines.add(new StatementLine("Total Liabilities", liabilities, 0, true));

            lines.add(new StatementLine("Owner's Equity", null, 0, true));
            long equity = 0;
            for (Account a : accounts) {
                long v = natural(a, sums);
                if (a.type() == Account.Type.EQUITY && v != 0) {
                    lines.add(new StatementLine(a.name(), v, 1, false));
                    equity += v;
                }
            }
            long ni = netIncome(accounts, sums);
            if (ni != 0) {
                lines.add(new StatementLine(ni > 0 ? "Net income to date (not yet closed)" : "Net loss to date (not yet closed)", ni, 1, false));
                equity += ni;
            }
            lines.add(new StatementLine("Total Owner's Equity", equity, 0, true));
            lines.add(new StatementLine("TOTAL LIABILITIES AND OWNER'S EQUITY", liabilities + equity, 0, true));

            if (assets != liabilities + equity) {
                warnings.add("The Balance Sheet does NOT balance: Assets " + Money.format(assets)
                        + " vs Liabilities + Equity " + Money.format(liabilities + equity) + ". Review the Trial Balance.");
            }
            draftWarning(c, null, asOf, warnings);
            return new Statement("Balance Sheet", subtitle(c, "As of " + asOf), lines, warnings);
        });
    }
}
