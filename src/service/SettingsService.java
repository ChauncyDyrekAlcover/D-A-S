package service;

import dao.AccountDao;
import dao.SettingsDao;
import database.Database;
import model.Account;
import util.AccountingException;
import util.Session;

import java.sql.Connection;
import java.sql.SQLException;

/** Business name, which accounts play special roles, and which specialized journals are switched on. */
public class SettingsService {
    public static final String BUSINESS_NAME = "business_name";
    public static final String CASH = "cash_account";
    public static final String AR = "ar_account";
    public static final String AP = "ap_account";
    public static final String CAPITAL = "capital_account";
    public static final String DRAWINGS = "drawings_account";
    public static final String INCOME_SUMMARY = "income_summary_account";
    public static final String SALES_ON = "sales_journal_enabled";
    public static final String PURCHASES_ON = "purchases_journal_enabled";

    private final SettingsDao dao = new SettingsDao();
    private final AccountDao accountDao = new AccountDao();
    private final AuditService audit = new AuditService();

    public String get(String key) {
        return Database.read(c -> dao.get(c, key, ""));
    }

    public String get(Connection c, String key) throws SQLException {
        return dao.get(c, key, "");
    }

    public boolean isOn(Connection c, String key) throws SQLException {
        return "true".equals(dao.get(c, key, "false"));
    }

    public boolean isOn(String key) {
        return Database.read(c -> isOn(c, key));
    }

    /** The account configured for a role (by account number), or null when not set / missing. */
    public Account accountFor(Connection c, String key) throws SQLException {
        String number = dao.get(c, key, "");
        return number.isEmpty() ? null : accountDao.findByNumber(c, number);
    }

    public Account requireAccount(Connection c, String key, String roleName) throws SQLException {
        Account a = accountFor(c, key);
        if (a == null) {
            throw new AccountingException("The " + roleName + " account is not set up. Choose it under Settings.");
        }
        return a;
    }

    public void save(java.util.Map<String, String> values) {
        Session.require("change settings", "ADMIN");
        Database.inTransaction(c -> {
            for (java.util.Map.Entry<String, String> e : values.entrySet()) {
                dao.set(c, e.getKey(), e.getValue());
            }
            audit.log(c, "UPDATE SETTINGS", "Settings", "", "Settings were changed.");
            return null;
        });
    }
}
