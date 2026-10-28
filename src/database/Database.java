package database;

import util.AccountingException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

/**
 * Everything about opening the SQLite file lives here.
 *
 * How Java talks to SQLite: JDBC. DriverManager.getConnection("jdbc:sqlite:data/accounting.db")
 * opens (or creates, if missing) the file. Each Connection is short-lived: open, do work, close.
 * The file itself is never replaced or recreated by the program on start-up.
 */
public final class Database {
    private static String path = "data/accounting.db";

    private Database() {
    }

    /** A piece of database work that receives an open Connection. */
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    public static void setPath(String newPath) {
        path = newPath;
    }

    public static String getPath() {
        return path;
    }

    /** Opens a new connection with foreign keys switched ON (SQLite has them off by default). */
    public static Connection open() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("The SQLite JDBC driver was not found. Copy sqlite-jdbc-*.jar into the lib folder "
                    + "and add lib/* to the classpath (see README.md).");
        }
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = 5000");
        }
        return c;
    }

    /** Runs read-only work and converts SQL errors into AccountingException. */
    public static <T> T read(Work<T> work) {
        try (Connection c = open()) {
            return work.run(c);
        } catch (SQLException e) {
            throw new AccountingException("Database error: " + e.getMessage(), e);
        }
    }

    /**
     * Runs work inside ONE database transaction: either everything succeeds and is committed,
     * or anything fails and EVERYTHING is rolled back. This is how posting stays atomic.
     */
    public static <T> T inTransaction(Work<T> work) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // the original error is more important
                }
                if (e instanceof AccountingException) {
                    throw (AccountingException) e;
                }
                if (e instanceof SQLException) {
                    throw new AccountingException("Database error (nothing was saved): " + e.getMessage(), e);
                }
                throw (RuntimeException) e;
            }
        } catch (SQLException e) {
            throw new AccountingException("Database error: " + e.getMessage(), e);
        }
    }

    /** Creates the file/tables if needed and fills in starter data only on the very first run. */
    public static void initialize() {
        try {
            Path file = Paths.get(path).toAbsolutePath();
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
        } catch (IOException e) {
            throw new AccountingException("Could not create the data folder: " + e.getMessage(), e);
        }
        inTransaction(c -> {
            try (Statement st = c.createStatement()) {
                for (String sql : Schema.STATEMENTS) {
                    st.execute(sql);
                }
            }
            seedIfEmpty(c);
            return null;
        });
    }

    private static boolean isEmpty(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() && rs.getInt(1) == 0;
        }
    }

    private static void seedIfEmpty(Connection c) throws SQLException {
        if (isEmpty(c, "accounts")) {
            String[][] accounts = {
                {"1010", "Cash", "ASSET", "DEBIT", "Cash on hand and in bank"},
                {"1020", "Accounts Receivable", "ASSET", "DEBIT", "Amounts customers owe"},
                {"1030", "Supplies", "ASSET", "DEBIT", "Unused office supplies"},
                {"1040", "Prepaid Insurance", "ASSET", "DEBIT", "Insurance paid in advance"},
                {"1100", "Equipment", "ASSET", "DEBIT", "Office and business equipment"},
                {"1110", "Accumulated Depreciation - Equipment", "ASSET", "CREDIT", "Contra-asset: total depreciation taken"},
                {"2010", "Accounts Payable", "LIABILITY", "CREDIT", "Amounts owed to suppliers"},
                {"2020", "Salaries Payable", "LIABILITY", "CREDIT", "Accrued, unpaid salaries"},
                {"2030", "Unearned Revenue", "LIABILITY", "CREDIT", "Payments received before service is done"},
                {"3010", "Owner's Capital", "EQUITY", "CREDIT", "Owner's investment and retained income"},
                {"3020", "Owner's Drawings", "EQUITY", "DEBIT", "Withdrawals by the owner"},
                {"3030", "Income Summary", "EQUITY", "CREDIT", "Temporary account used only when closing"},
                {"4010", "Service Revenue", "REVENUE", "CREDIT", "Income from services"},
                {"5010", "Rent Expense", "EXPENSE", "DEBIT", ""},
                {"5020", "Utilities Expense", "EXPENSE", "DEBIT", ""},
                {"5030", "Salaries Expense", "EXPENSE", "DEBIT", ""},
                {"5040", "Supplies Expense", "EXPENSE", "DEBIT", ""},
                {"5050", "Depreciation Expense", "EXPENSE", "DEBIT", ""},
                {"5060", "Insurance Expense", "EXPENSE", "DEBIT", ""}
            };
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO accounts (account_number, account_name, account_type, normal_balance, description, is_active)"
                            + " VALUES (?,?,?,?,?,1)")) {
                for (String[] a : accounts) {
                    for (int i = 0; i < 5; i++) {
                        ps.setString(i + 1, a[i]);
                    }
                    ps.executeUpdate();
                }
            }
        }
        String[][] settings = {
            {"business_name", "My Business"},
            {"cash_account", "1010"},
            {"ar_account", "1020"},
            {"ap_account", "2010"},
            {"capital_account", "3010"},
            {"drawings_account", "3020"},
            {"income_summary_account", "3030"},
            {"sales_journal_enabled", "true"},
            {"purchases_journal_enabled", "true"}
        };
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO settings (setting_key, setting_value) VALUES (?,?)")) {
            for (String[] s : settings) {
                ps.setString(1, s[0]);
                ps.setString(2, s[1]);
                ps.executeUpdate();
            }
        }
        if (isEmpty(c, "accounting_periods")) {
            int year = LocalDate.now().getYear();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO accounting_periods (period_name, start_date, end_date, status) VALUES (?,?,?, 'OPEN')")) {
                ps.setString(1, "FY " + year);
                ps.setString(2, year + "-01-01");
                ps.setString(3, year + "-12-31");
                ps.executeUpdate();
            }
        }
    }
}
