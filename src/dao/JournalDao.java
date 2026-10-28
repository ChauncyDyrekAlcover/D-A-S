package dao;

import model.EntrySummary;
import model.JournalEntry;
import model.JournalLine;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** SQL for journal_entries and journal_lines. */
public class JournalDao {

    private static void setIntOrNull(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static Integer getIntOrNull(ResultSet rs, int index) throws SQLException {
        int v = rs.getInt(index);
        return rs.wasNull() ? null : v;
    }

    /** Next free number such as JE-2026-00001 (prefix JE, AJE, CJE or RJE). */
    public String nextNumber(Connection c, String prefix, int year) throws SQLException {
        String start = prefix + "-" + year + "-";
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(MAX(CAST(SUBSTR(entry_number, ?) AS INTEGER)), 0) + 1"
                        + " FROM journal_entries WHERE entry_number LIKE ?")) {
            ps.setInt(1, start.length() + 1);
            ps.setString(2, start + "%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return String.format("%s%05d", start, rs.getInt(1));
            }
        }
    }

    public int insertEntry(Connection c, JournalEntry e, String number, Integer periodId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO journal_entries (entry_number, entry_date, description, reference_no, category, notes,"
                        + " status, entry_type, journal_type, accounting_period_id, created_at, created_by,"
                        + " reversal_of_entry_id, replaces_entry_id)"
                        + " VALUES (?,?,?,?,?,?, 'DRAFT', ?,?,?,?,?,?,?)")) {
            ps.setString(1, number);
            ps.setString(2, e.date().toString());
            ps.setString(3, e.description());
            ps.setString(4, e.reference());
            ps.setString(5, e.category());
            ps.setString(6, e.notes());
            ps.setString(7, e.type().name());
            ps.setString(8, e.journal().name());
            setIntOrNull(ps, 9, periodId);
            ps.setString(10, util.Dates.now());
            ps.setString(11, util.Session.userName());
            setIntOrNull(ps, 12, e.reversalOfId());
            setIntOrNull(ps, 13, e.replacesId());
            ps.executeUpdate();
        }
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    public void insertLines(Connection c, int entryId, List<JournalLine> lines) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO journal_lines (entry_id, account_id, line_description, debit_amount, credit_amount, line_number)"
                        + " VALUES (?,?,?,?,?,?)")) {
            int n = 1;
            for (JournalLine l : lines) {
                ps.setInt(1, entryId);
                ps.setInt(2, l.accountId());
                ps.setString(3, l.memo());
                ps.setLong(4, l.debit());
                ps.setLong(5, l.credit());
                ps.setInt(6, n++);
                ps.executeUpdate();
            }
        }
    }

    public void deleteLines(Connection c, int entryId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM journal_lines WHERE entry_id = ?")) {
            ps.setInt(1, entryId);
            ps.executeUpdate();
        }
    }

    public void updateHeader(Connection c, JournalEntry e, Integer periodId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE journal_entries SET entry_date=?, description=?, reference_no=?, category=?, notes=?,"
                        + " journal_type=?, accounting_period_id=? WHERE entry_id=? AND status='DRAFT'")) {
            ps.setString(1, e.date().toString());
            ps.setString(2, e.description());
            ps.setString(3, e.reference());
            ps.setString(4, e.category());
            ps.setString(5, e.notes());
            ps.setString(6, e.journal().name());
            setIntOrNull(ps, 7, periodId);
            ps.setInt(8, e.id());
            ps.executeUpdate();
        }
    }

    public void deleteEntry(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM journal_entries WHERE entry_id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    /** Returns true only if exactly one DRAFT row was switched to POSTED (blocks double posting). */
    public boolean markPosted(Connection c, int id, String postedAt, int periodId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE journal_entries SET status='POSTED', posted_at=?, accounting_period_id=?"
                        + " WHERE entry_id=? AND status='DRAFT'")) {
            ps.setString(1, postedAt);
            ps.setInt(2, periodId);
            ps.setInt(3, id);
            return ps.executeUpdate() == 1;
        }
    }

    public boolean markReversed(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE journal_entries SET status='REVERSED' WHERE entry_id=? AND status='POSTED'")) {
            ps.setInt(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    public JournalEntry findById(Connection c, int id) throws SQLException {
        JournalEntry header;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT entry_id, entry_number, entry_date, description, reference_no, category, notes, status,"
                        + " entry_type, journal_type, accounting_period_id, created_at, posted_at,"
                        + " reversal_of_entry_id, replaces_entry_id FROM journal_entries WHERE entry_id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                header = new JournalEntry(rs.getInt(1), rs.getString(2), LocalDate.parse(rs.getString(3)),
                        rs.getString(4), nz(rs.getString(5)), nz(rs.getString(6)), nz(rs.getString(7)),
                        JournalEntry.Status.valueOf(rs.getString(8)), JournalEntry.Type.valueOf(rs.getString(9)),
                        JournalEntry.Journal.valueOf(rs.getString(10)), getIntOrNull(rs, 11),
                        rs.getString(12), rs.getString(13), getIntOrNull(rs, 14), getIntOrNull(rs, 15),
                        new ArrayList<>());
            }
        }
        List<JournalLine> lines = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT l.line_id, l.account_id, a.account_number, a.account_name, l.line_description,"
                        + " l.debit_amount, l.credit_amount FROM journal_lines l"
                        + " JOIN accounts a ON a.account_id = l.account_id WHERE l.entry_id = ? ORDER BY l.line_number")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(new JournalLine(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                            nz(rs.getString(5)), rs.getLong(6), rs.getLong(7)));
                }
            }
        }
        return new JournalEntry(header.id(), header.number(), header.date(), header.description(), header.reference(),
                header.category(), header.notes(), header.status(), header.type(), header.journal(), header.periodId(),
                header.createdAt(), header.postedAt(), header.reversalOfId(), header.replacesId(), lines);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public String numberOf(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT entry_number FROM journal_entries WHERE entry_id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Entry number of the reversal that reverses the given entry (null if none). */
    public String reversalNumberFor(Connection c, int originalId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT entry_number FROM journal_entries WHERE reversal_of_entry_id = ?")) {
            ps.setInt(1, originalId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public List<EntrySummary> search(Connection c, LocalDate from, LocalDate to, Integer accountId, String text,
                                     JournalEntry.Status status, JournalEntry.Type type) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT e.entry_id, e.entry_number, e.entry_date, e.description, e.reference_no, e.status,"
                        + " e.entry_type, e.journal_type, e.reversal_of_entry_id,"
                        + " (SELECT COALESCE(SUM(debit_amount),0) FROM journal_lines WHERE entry_id = e.entry_id)"
                        + " FROM journal_entries e WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (from != null) {
            sql.append(" AND e.entry_date >= ?");
            params.add(from.toString());
        }
        if (to != null) {
            sql.append(" AND e.entry_date <= ?");
            params.add(to.toString());
        }
        if (accountId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM journal_lines l WHERE l.entry_id = e.entry_id AND l.account_id = ?)");
            params.add(accountId);
        }
        if (text != null && !text.trim().isEmpty()) {
            sql.append(" AND (e.entry_number LIKE ? OR e.reference_no LIKE ? OR e.description LIKE ?)");
            String like = "%" + text.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (status != null) {
            sql.append(" AND e.status = ?");
            params.add(status.name());
        }
        if (type != null) {
            sql.append(" AND e.entry_type = ?");
            params.add(type.name());
        }
        sql.append(" ORDER BY e.entry_date DESC, e.entry_id DESC");
        List<EntrySummary> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rs.getInt(9);
                    boolean reversal = !rs.wasNull();
                    list.add(new EntrySummary(rs.getInt(1), rs.getString(2), LocalDate.parse(rs.getString(3)),
                            rs.getString(4), nz(rs.getString(5)), JournalEntry.Status.valueOf(rs.getString(6)),
                            JournalEntry.Type.valueOf(rs.getString(7)), JournalEntry.Journal.valueOf(rs.getString(8)),
                            reversal, rs.getLong(10)));
                }
            }
        }
        return list;
    }

    public Map<JournalEntry.Status, Integer> countByStatus(Connection c) throws SQLException {
        Map<JournalEntry.Status, Integer> map = new EnumMap<>(JournalEntry.Status.class);
        for (JournalEntry.Status s : JournalEntry.Status.values()) {
            map.put(s, 0);
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT status, COUNT(*) FROM journal_entries GROUP BY status");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.put(JournalEntry.Status.valueOf(rs.getString(1)), rs.getInt(2));
            }
        }
        return map;
    }
}
