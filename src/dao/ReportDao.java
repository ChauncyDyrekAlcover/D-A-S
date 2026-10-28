package dao;

import model.JournalEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only queries behind the ledger, trial balance, columnar journals and statements.
 *
 * IMPORTANT DESIGN CHOICE: there is no separate "ledger" table. The General Ledger is
 * calculated from journal_lines of POSTED (and later REVERSED) entries, so it can never
 * disagree with the journal. DRAFT entries are never counted.
 */
public class ReportDao {
    /** Only these statuses affect balances. A REVERSED entry stays; its reversal entry cancels it. */
    private static final String COUNTED = "e.status IN ('POSTED','REVERSED')";

    public record RawLine(int entryId, LocalDate date, String number, String reference, String description,
                          int accountId, String accountNumber, String accountName, long debit, long credit) {
    }

    /** Total debits and credits per account (accountId -> [debit, credit]) between two dates for given entry types. */
    public Map<Integer, long[]> sums(Connection c, LocalDate from, LocalDate to, Set<JournalEntry.Type> types)
            throws SQLException {
        Map<Integer, long[]> map = new HashMap<>();
        if (types.isEmpty()) {
            return map;
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < types.size(); i++) {
            in.append(i == 0 ? "?" : ",?");
        }
        String sql = "SELECT l.account_id, COALESCE(SUM(l.debit_amount),0), COALESCE(SUM(l.credit_amount),0)"
                + " FROM journal_lines l JOIN journal_entries e ON e.entry_id = l.entry_id"
                + " WHERE " + COUNTED + " AND e.entry_date >= ? AND e.entry_date <= ? AND e.entry_type IN (" + in + ")"
                + " GROUP BY l.account_id";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, from == null ? "0000-01-01" : from.toString());
            ps.setString(i++, to == null ? "9999-12-31" : to.toString());
            for (JournalEntry.Type t : types) {
                ps.setString(i++, t.name());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getInt(1), new long[]{rs.getLong(2), rs.getLong(3)});
                }
            }
        }
        return map;
    }

    /** [debit, credit] of one account for all counted entries BEFORE the given date. */
    public long[] openingSums(Connection c, int accountId, LocalDate before) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(l.debit_amount),0), COALESCE(SUM(l.credit_amount),0)"
                        + " FROM journal_lines l JOIN journal_entries e ON e.entry_id = l.entry_id"
                        + " WHERE " + COUNTED + " AND l.account_id = ? AND e.entry_date < ?")) {
            ps.setInt(1, accountId);
            ps.setString(2, before.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new long[]{rs.getLong(1), rs.getLong(2)};
            }
        }
    }

    public record LedgerLine(int entryId, LocalDate date, String number, String reference, String description,
                             long debit, long credit, String period) {
    }

    public List<LedgerLine> ledgerLines(Connection c, int accountId, LocalDate from, LocalDate to) throws SQLException {
        List<LedgerLine> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT e.entry_id, e.entry_date, e.entry_number, e.reference_no,"
                        + " COALESCE(NULLIF(l.line_description,''), e.description), l.debit_amount, l.credit_amount,"
                        + " COALESCE(p.period_name,'')"
                        + " FROM journal_lines l JOIN journal_entries e ON e.entry_id = l.entry_id"
                        + " LEFT JOIN accounting_periods p ON p.period_id = e.accounting_period_id"
                        + " WHERE " + COUNTED + " AND l.account_id = ? AND e.entry_date >= ? AND e.entry_date <= ?"
                        + " ORDER BY e.entry_date, e.entry_id, l.line_number")) {
            ps.setInt(1, accountId);
            ps.setString(2, from == null ? "0000-01-01" : from.toString());
            ps.setString(3, to == null ? "9999-12-31" : to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ref = rs.getString(4);
                    list.add(new LedgerLine(rs.getInt(1), LocalDate.parse(rs.getString(2)), rs.getString(3),
                            ref == null ? "" : ref, rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getString(8)));
                }
            }
        }
        return list;
    }

    public List<RawLine> journalLines(Connection c, JournalEntry.Journal journal, LocalDate from, LocalDate to)
            throws SQLException {
        List<RawLine> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT e.entry_id, e.entry_date, e.entry_number, e.reference_no, e.description,"
                        + " a.account_id, a.account_number, a.account_name, l.debit_amount, l.credit_amount"
                        + " FROM journal_lines l JOIN journal_entries e ON e.entry_id = l.entry_id"
                        + " JOIN accounts a ON a.account_id = l.account_id"
                        + " WHERE " + COUNTED + " AND e.journal_type = ? AND e.entry_date >= ? AND e.entry_date <= ?"
                        + " ORDER BY e.entry_date, e.entry_id, l.line_number")) {
            ps.setString(1, journal.name());
            ps.setString(2, from == null ? "0000-01-01" : from.toString());
            ps.setString(3, to == null ? "9999-12-31" : to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ref = rs.getString(4);
                    list.add(new RawLine(rs.getInt(1), LocalDate.parse(rs.getString(2)), rs.getString(3),
                            ref == null ? "" : ref, rs.getString(5), rs.getInt(6), rs.getString(7), rs.getString(8),
                            rs.getLong(9), rs.getLong(10)));
                }
            }
        }
        return list;
    }
}
