package dao;

import model.AuditRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** The audit log is insert-and-read only. There is deliberately no update or delete method. */
public class AuditDao {
    public void insert(Connection c, String time, String user, String action, String type, String ref, String details)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_logs (created_at, user_name, action, record_type, record_ref, details) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, time);
            ps.setString(2, user);
            ps.setString(3, action);
            ps.setString(4, type);
            ps.setString(5, ref);
            ps.setString(6, details);
            ps.executeUpdate();
        }
    }

    public List<AuditRecord> search(Connection c, String text, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT log_id, created_at, user_name, action, record_type, record_ref, details FROM audit_logs");
        boolean hasText = text != null && !text.trim().isEmpty();
        if (hasText) {
            sql.append(" WHERE action LIKE ? OR record_type LIKE ? OR record_ref LIKE ? OR details LIKE ?");
        }
        sql.append(" ORDER BY log_id DESC LIMIT ?");
        List<AuditRecord> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            if (hasText) {
                String like = "%" + text.trim() + "%";
                for (int k = 0; k < 4; k++) {
                    ps.setString(i++, like);
                }
            }
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new AuditRecord(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7)));
                }
            }
        }
        return list;
    }
}
