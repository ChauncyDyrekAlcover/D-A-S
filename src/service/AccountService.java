package service;

import dao.AccountDao;
import dao.SettingsDao;
import database.Database;
import model.Account;
import util.AccountingException;
import util.Session;

import java.util.List;

/** Business rules for the Chart of Accounts. */
public class AccountService {
    private final AccountDao dao = new AccountDao();
    private final SettingsDao settingsDao = new SettingsDao();
    private final AuditService audit = new AuditService();

    private static final String[] ROLE_KEYS = {SettingsService.CASH, SettingsService.AR, SettingsService.AP,
        SettingsService.CAPITAL, SettingsService.DRAWINGS, SettingsService.INCOME_SUMMARY};

    public List<Account> list(String search, boolean includeInactive) {
        return Database.read(c -> dao.findAll(c, search, includeInactive));
    }

    public Account get(int id) {
        return Database.read(c -> dao.findById(c, id));
    }

    public boolean hasHistory(int id) {
        return Database.read(c -> dao.hasHistory(c, id));
    }

    private void validate(Account a) {
        String number = a.number() == null ? "" : a.number().trim();
        if (number.isEmpty()) {
            throw new AccountingException("Please enter an account number.");
        }
        if (!number.matches("[A-Za-z0-9\\-]{1,12}")) {
            throw new AccountingException("The account number may only contain letters, digits and dashes (maximum 12 characters).");
        }
        if (a.name() == null || a.name().trim().isEmpty()) {
            throw new AccountingException("Please enter an account name.");
        }
        if (a.name().trim().length() > 80) {
            throw new AccountingException("The account name is too long (maximum 80 characters).");
        }
        if (a.type() == null || a.normal() == null) {
            throw new AccountingException("Please choose the account type and normal balance.");
        }
    }

    private Account clean(Account a, int id, boolean active) {
        return new Account(id, a.number().trim(), a.name().trim(), a.type(), a.normal(),
                a.description() == null ? "" : a.description().trim(), active);
    }

    public Account create(Account input) {
        Session.require("change the chart of accounts", "ADMIN", "ACCOUNTANT");
        validate(input);
        return Database.inTransaction(c -> {
            if (dao.findByNumber(c, input.number().trim()) != null) {
                throw new AccountingException("Account number " + input.number().trim() + " already exists.");
            }
            int id = dao.insert(c, clean(input, 0, true));
            audit.log(c, "CREATE ACCOUNT", "Account", input.number().trim(), "Created account " + input.name().trim());
            return dao.findById(c, id);
        });
    }

    public Account update(Account input) {
        Session.require("change the chart of accounts", "ADMIN", "ACCOUNTANT");
        validate(input);
        return Database.inTransaction(c -> {
            Account old = dao.findById(c, input.id());
            if (old == null) {
                throw new AccountingException("That account no longer exists.");
            }
            Account other = dao.findByNumber(c, input.number().trim());
            if (other != null && other.id() != old.id()) {
                throw new AccountingException("Account number " + input.number().trim() + " already exists.");
            }
            boolean history = dao.hasHistory(c, old.id());
            if (history && !old.number().equals(input.number().trim())) {
                throw new AccountingException("This account already has accounting history, so its number cannot be changed.");
            }
            if (history && (old.type() != input.type() || old.normal() != input.normal())) {
                throw new AccountingException("This account already has accounting history, so its type and normal balance cannot be changed.");
            }
            if (!old.number().equals(input.number().trim()) && usedAsRole(c, old.number())) {
                throw new AccountingException("This account is selected in Settings (cash, capital, etc.), so its number cannot be changed.");
            }
            dao.update(c, clean(input, old.id(), old.active()));
            audit.log(c, "EDIT ACCOUNT", "Account", input.number().trim(), "Edited account " + input.name().trim());
            return dao.findById(c, old.id());
        });
    }

    private boolean usedAsRole(java.sql.Connection c, String number) throws java.sql.SQLException {
        for (String key : ROLE_KEYS) {
            if (number.equals(settingsDao.get(c, key, ""))) {
                return true;
            }
        }
        return false;
    }

    public void setActive(int id, boolean active) {
        Session.require("change the chart of accounts", "ADMIN", "ACCOUNTANT");
        Database.inTransaction(c -> {
            Account a = dao.findById(c, id);
            if (a == null) {
                throw new AccountingException("That account no longer exists.");
            }
            if (!active && usedAsRole(c, a.number())) {
                throw new AccountingException("This account is selected in Settings (cash, capital, etc.). Choose another account there before deactivating it.");
            }
            dao.setActive(c, id, active);
            audit.log(c, active ? "ACTIVATE ACCOUNT" : "DEACTIVATE ACCOUNT", "Account", a.number(), a.name());
            return null;
        });
    }

    /** Only accounts with no accounting history can be deleted. */
    public void delete(int id) {
        Session.require("change the chart of accounts", "ADMIN", "ACCOUNTANT");
        Database.inTransaction(c -> {
            Account a = dao.findById(c, id);
            if (a == null) {
                throw new AccountingException("That account no longer exists.");
            }
            if (dao.hasHistory(c, id)) {
                throw new AccountingException("This account has accounting history and cannot be deleted. Deactivate it instead.");
            }
            if (usedAsRole(c, a.number())) {
                throw new AccountingException("This account is selected in Settings and cannot be deleted.");
            }
            dao.delete(c, id);
            audit.log(c, "DELETE ACCOUNT", "Account", a.number(), "Deleted unused account " + a.name());
            return null;
        });
    }
}
