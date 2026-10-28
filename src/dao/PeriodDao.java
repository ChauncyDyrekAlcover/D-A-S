package dao;

import model.AccountingPeriod;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class PeriodDao {
    private static final String COLUMNS = "period_id, period_name, start_date, end_date, status, closed_at";

    private AccountingPeriod map(ResultSet rs) throws SQLException {
        return new AccountingPeriod(rs.getInt(1), rs.getString(2), LocalDate.parse(rs.getString(3)),
                LocalDate.parse(rs.getString(4)), "CLOSED".equals(rs.getString(5)), rs.getString(6));
    }

    public List<AccountingPeriod> findAll(Connection c) throws SQLException {
        List<AccountingPeriod> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM accounting_periods ORDER BY start_date");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(map(rs));
            }
        }
        return list;
    }

    public AccountingPeriod findById(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM accounting_periods WHERE period_id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public AccountingPeriod findByDate(Connection c, LocalDate date) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLUMNS + " FROM accounting_periods WHERE start_date <= ? AND end_date >= ?")) {
            ps.setString(1, date.toString());
            ps.setString(2, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public boolean nameExists(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM accounting_periods WHERE period_name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /** Does any existing period overlap the given dates? */
    public boolean overlaps(Connection c, LocalDate start, LocalDate end) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM accounting_periods WHERE start_date <= ? AND end_date >= ?")) {
            ps.setString(1, end.toString());
            ps.setString(2, start.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    public void insert(Connection c, String name, LocalDate start, LocalDate end) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO accounting_periods (period_name, start_date, end_date, status) VALUES (?,?,?, 'OPEN')")) {
            ps.setString(1, name);
            ps.setString(2, start.toString());
            ps.setString(3, end.toString());
            ps.executeUpdate();
        }
    }

    public void markClosed(Connection c, int id, String closedAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE accounting_periods SET status='CLOSED', closed_at=? WHERE period_id=? AND status='OPEN'")) {
            ps.setString(1, closedAt);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }
}
