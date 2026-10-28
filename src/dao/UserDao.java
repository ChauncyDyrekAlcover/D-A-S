package dao;

import model.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class UserDao {
    /** A user together with the stored password hash and salt. */
    public record Row(User user, String hash, String salt) {
    }

    private static final String COLUMNS = "user_id, username, full_name, role, is_active, created_at, password_hash, password_salt";

    private Row map(ResultSet rs) throws SQLException {
        return new Row(new User(rs.getInt(1), rs.getString(2), rs.getString(3), User.Role.valueOf(rs.getString(4)),
                rs.getInt(5) == 1, rs.getString(6)), rs.getString(7), rs.getString(8));
    }

    public int count(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM users"); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    public int countActiveAdmins(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND is_active=1");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    public Row findByUsername(Connection c, String username) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public Row findById(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM users WHERE user_id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public List<User> findAll(Connection c) throws SQLException {
        List<User> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM users ORDER BY username");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(map(rs).user());
            }
        }
        return list;
    }

    public int insert(Connection c, String username, String fullName, String hash, String salt, User.Role role,
                      String createdAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (username, full_name, password_hash, password_salt, role, is_active, created_at)"
                        + " VALUES (?,?,?,?,?,1,?)")) {
            ps.setString(1, username);
            ps.setString(2, fullName);
            ps.setString(3, hash);
            ps.setString(4, salt);
            ps.setString(5, role.name());
            ps.setString(6, createdAt);
            ps.executeUpdate();
        }
        return findByUsername(c, username).user().id();
    }

    public void updateRole(Connection c, int id, User.Role role) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE users SET role = ? WHERE user_id = ?")) {
            ps.setString(1, role.name());
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    public void setActive(Connection c, int id, boolean active) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE users SET is_active = ? WHERE user_id = ?")) {
            ps.setInt(1, active ? 1 : 0);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    public void updatePassword(Connection c, int id, String hash, String salt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE users SET password_hash = ?, password_salt = ? WHERE user_id = ?")) {
            ps.setString(1, hash);
            ps.setString(2, salt);
            ps.setInt(3, id);
            ps.executeUpdate();
        }
    }
}
