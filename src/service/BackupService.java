package service;

import database.Database;
import util.AccountingException;
import util.Session;
import util.Dates;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Backup and restore of the SQLite database file. Existing files are never silently overwritten. */
public class BackupService {
    private final AuditService audit = new AuditService();

    /** Writes a consistent snapshot of the database to a NEW file (VACUUM INTO). */
    public void backup(File target) {
        Session.require("create backups", "ADMIN", "ACCOUNTANT");
        if (target.exists()) {
            throw new AccountingException("That file already exists. Choose a new file name - existing backups are never overwritten.");
        }
        try (Connection c = Database.open(); Statement st = c.createStatement()) {
            st.execute("VACUUM INTO '" + target.getAbsolutePath().replace("'", "''") + "'");
        } catch (SQLException e) {
            throw new AccountingException("Backup failed: " + e.getMessage(), e);
        }
        Database.inTransaction(c -> {
            audit.log(c, "BACKUP DATABASE", "Database", target.getName(), "Backup written to " + target.getAbsolutePath());
            return null;
        });
    }

    /**
     * Replaces the live database with a backup. First the current database is saved to
     * data/backups/before-restore-*.db so nothing is lost.
     */
    public File restore(File source) {
        Session.require("use backup and restore", "ADMIN");
        if (!source.isFile()) {
            throw new AccountingException("The selected backup file does not exist.");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + source.getAbsolutePath());
             Statement st = c.createStatement()) {
            for (String table : new String[]{"accounts", "journal_entries", "journal_lines", "accounting_periods", "audit_logs"}) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    rs.next();
                }
            }
        } catch (SQLException e) {
            throw new AccountingException("That file is not a valid accounting database backup: " + e.getMessage(), e);
        }
        Path live = Paths.get(Database.getPath()).toAbsolutePath();
        File safety;
        try {
            Path dir = live.getParent().resolve("backups");
            Files.createDirectories(dir);
            safety = dir.resolve("before-restore-" + Dates.now().replace(":", "").replace(" ", "_") + ".db").toFile();
        } catch (IOException e) {
            throw new AccountingException("Could not create the backups folder: " + e.getMessage(), e);
        }
        backup(safety);
        try {
            Files.copy(source.toPath(), live, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new AccountingException("Restore failed: " + e.getMessage(), e);
        }
        Database.initialize();
        Database.inTransaction(c -> {
            audit.log(c, "RESTORE DATABASE", "Database", source.getName(), "Restored from " + source.getAbsolutePath()
                    + ". Previous data saved to " + safety.getName());
            return null;
        });
        return safety;
    }
}
