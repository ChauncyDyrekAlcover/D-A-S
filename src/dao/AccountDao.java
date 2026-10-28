package dao;

import model.Account;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * DAO = Data Access Object. Its only job is to run SQL for ONE table and turn rows into
 * Java objects (and back). It contains no accounting rules - those live in the service layer.
 */
public class AccountDao {
    private static final String COLUMNS =
            "account_id, account_number, account_name, account_type, normal_balance, description, is_active";

    private Account map(ResultSet rs) throws SQLException {
        return new Account(rs.getInt(1), rs.getString(2), rs.getString(3),
                Account.Type.valueOf(rs.getString(4)), Account.Normal.valueOf(rs.getString(5)),
                rs.getString(6) == null ? "" : rs.getString(6), rs.getInt(7) == 1);
    }

    public List<Account> findAll(Connection c, String search, boolean includeInactive) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM accounts WHERE 1=1");
        List<String> params = new ArrayList<>();
        if (search != null && !search.trim().isEmpty()) {
            sql.append(" AND (account_number LIKE ? OR account_name LIKE ? OR description LIKE ?)");
            String like = "%" + search.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (!includeInactive) {
            sql.append(" AND is_active = 1");
        }
        sql.append(" ORDER BY account_number");
        List<Account> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(map(rs));
                }
            }
        }
        return list;
    }

    public Account findById(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM accounts WHERE account_id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public Account findByNumber(Connection c, String number) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM accounts WHERE account_number = ?")) {
            ps.setString(1, number);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public int insert(Connection c, Account a) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO accounts (account_number, account_name, account_type, normal_balance, description, is_active)"
                        + " VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, a.number());
            ps.setString(2, a.name());
            ps.setString(3, a.type().name());
            ps.setString(4, a.normal().name());
            ps.setString(5, a.description());
            ps.setInt(6, a.active() ? 1 : 0);
            ps.executeUpdate();
        }
        return findByNumber(c, a.number()).id();
    }

    public void update(Connection c, Account a) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE accounts SET account_number=?, account_name=?, account_type=?, normal_balance=?, description=?"
                        + " WHERE account_id=?")) {
            ps.setString(1, a.number());
            ps.setString(2, a.name());
            ps.setString(3, a.type().name());
            ps.setString(4, a.normal().name());
            ps.setString(5, a.description());
            ps.setInt(6, a.id());
            ps.executeUpdate();
        }
    }

    public void setActive(Connection c, int id, boolean active) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE accounts SET is_active=? WHERE account_id=?")) {
            ps.setInt(1, active ? 1 : 0);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    public boolean hasHistory(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM journal_lines WHERE account_id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    public void delete(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM accounts WHERE account_id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }
}
