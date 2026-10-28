package service;

import dao.AuditDao;
import model.AuditRecord;
import database.Database;
import util.Dates;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Writes and reads the audit trail. log() runs inside the caller's transaction, so the log and the action commit together. */
public class AuditService {
    private final AuditDao dao = new AuditDao();

    public void log(Connection c, String action, String recordType, String recordRef, String details) throws SQLException {
        dao.insert(c, Dates.now(), util.Session.userName(), action, recordType, recordRef, details);
    }

    /** Logs an action for a named user (used for sign-up and login, when nobody is signed in yet). */
    public void logAs(Connection c, String user, String action, String recordType, String recordRef, String details) throws SQLException {
        dao.insert(c, Dates.now(), user, action, recordType, recordRef, details);
    }

    public List<AuditRecord> search(String text, int limit) {
        return Database.read(c -> dao.search(c, text, limit));
    }
}
