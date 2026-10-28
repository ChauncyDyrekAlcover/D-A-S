package database;

/**
 * All CREATE statements for the database. Every statement uses IF NOT EXISTS, so it is
 * safe to run them every time the program starts: existing data is never touched.
 *
 * Money columns hold CENTAVOS as INTEGER (never floating point).
 * Triggers are the database's own safety net: even if a bug in Java tried, SQLite itself
 * refuses to edit posted entries, post unbalanced entries, or change the audit log.
 */
final class Schema {
    private Schema() {
    }

    static final String[] STATEMENTS = {
        "CREATE TABLE IF NOT EXISTS accounts ("
            + " account_id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " account_number TEXT NOT NULL UNIQUE,"
            + " account_name TEXT NOT NULL,"
            + " account_type TEXT NOT NULL CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),"
            + " normal_balance TEXT NOT NULL CHECK (normal_balance IN ('DEBIT','CREDIT')),"
            + " description TEXT,"
            + " is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0,1)))",

        "CREATE TABLE IF NOT EXISTS accounting_periods ("
            + " period_id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " period_name TEXT NOT NULL UNIQUE,"
            + " start_date TEXT NOT NULL,"
            + " end_date TEXT NOT NULL,"
            + " status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')),"
            + " closed_at TEXT,"
            + " CHECK (end_date >= start_date))",

        "CREATE TABLE IF NOT EXISTS journal_entries ("
            + " entry_id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " entry_number TEXT NOT NULL UNIQUE,"
            + " entry_date TEXT NOT NULL,"
            + " description TEXT NOT NULL,"
            + " reference_no TEXT,"
            + " category TEXT,"
            + " notes TEXT,"
            + " status TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','POSTED','REVERSED')),"
            + " entry_type TEXT NOT NULL DEFAULT 'REGULAR' CHECK (entry_type IN ('REGULAR','ADJUSTING','CLOSING')),"
            + " journal_type TEXT NOT NULL DEFAULT 'GENERAL' CHECK (journal_type IN"
            + "   ('GENERAL','CASH_RECEIPTS','CASH_DISBURSEMENTS','SALES','PURCHASES')),"
            + " accounting_period_id INTEGER REFERENCES accounting_periods(period_id),"
            + " created_at TEXT NOT NULL,"
            + " posted_at TEXT,"
            + " created_by TEXT NOT NULL DEFAULT 'LOCAL USER',"
            + " reversal_of_entry_id INTEGER REFERENCES journal_entries(entry_id),"
            + " replaces_entry_id INTEGER REFERENCES journal_entries(entry_id))",

        "CREATE TABLE IF NOT EXISTS journal_lines ("
            + " line_id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " entry_id INTEGER NOT NULL REFERENCES journal_entries(entry_id) ON DELETE CASCADE,"
            + " account_id INTEGER NOT NULL REFERENCES accounts(account_id),"
            + " line_description TEXT,"
            + " debit_amount INTEGER NOT NULL DEFAULT 0 CHECK (debit_amount >= 0),"
            + " credit_amount INTEGER NOT NULL DEFAULT 0 CHECK (credit_amount >= 0),"
            + " line_number INTEGER NOT NULL,"
            + " CHECK ((debit_amount > 0 AND credit_amount = 0) OR (credit_amount > 0 AND debit_amount = 0)))",

        "CREATE TABLE IF NOT EXISTS audit_logs ("
            + " log_id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " created_at TEXT NOT NULL,"
            + " user_name TEXT NOT NULL,"
            + " action TEXT NOT NULL,"
            + " record_type TEXT,"
            + " record_ref TEXT,"
            + " details TEXT)",

        "CREATE TABLE IF NOT EXISTS users ("
            + " user_id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " username TEXT NOT NULL UNIQUE COLLATE NOCASE,"
            + " full_name TEXT NOT NULL,"
            + " password_hash TEXT NOT NULL,"
            + " password_salt TEXT NOT NULL,"
            + " role TEXT NOT NULL CHECK (role IN ('ADMIN','ACCOUNTANT','VIEWER')),"
            + " is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0,1)),"
            + " created_at TEXT NOT NULL)",

        "CREATE TABLE IF NOT EXISTS settings ("
            + " setting_key TEXT PRIMARY KEY,"
            + " setting_value TEXT NOT NULL)",

        "CREATE INDEX IF NOT EXISTS idx_entries_date ON journal_entries(entry_date)",
        "CREATE INDEX IF NOT EXISTS idx_entries_status ON journal_entries(status)",
        "CREATE INDEX IF NOT EXISTS idx_entries_journal ON journal_entries(journal_type)",
        "CREATE INDEX IF NOT EXISTS idx_lines_entry ON journal_lines(entry_id)",
        "CREATE INDEX IF NOT EXISTS idx_lines_account ON journal_lines(account_id)",

        // ---- Safety-net triggers ----
        "CREATE TRIGGER IF NOT EXISTS trg_entries_no_delete BEFORE DELETE ON journal_entries"
            + " WHEN OLD.status <> 'DRAFT'"
            + " BEGIN SELECT RAISE(ABORT, 'Posted entries cannot be deleted. Use a reversing entry.'); END",

        "CREATE TRIGGER IF NOT EXISTS trg_entries_lock BEFORE UPDATE ON journal_entries"
            + " WHEN OLD.status = 'REVERSED'"
            + "   OR (OLD.status = 'POSTED' AND NOT (NEW.status = 'REVERSED'"
            + "        AND NEW.entry_number = OLD.entry_number AND NEW.entry_date = OLD.entry_date"
            + "        AND NEW.description = OLD.description))"
            + " BEGIN SELECT RAISE(ABORT, 'Posted entries cannot be edited. Use a reversing entry.'); END",

        "CREATE TRIGGER IF NOT EXISTS trg_entries_post_check BEFORE UPDATE OF status ON journal_entries"
            + " WHEN OLD.status = 'DRAFT' AND NEW.status = 'POSTED'"
            + " BEGIN"
            + "  SELECT RAISE(ABORT, 'Cannot post: an entry needs at least two lines.')"
            + "   WHERE (SELECT COUNT(*) FROM journal_lines WHERE entry_id = NEW.entry_id) < 2;"
            + "  SELECT RAISE(ABORT, 'Cannot post: total debits do not equal total credits.')"
            + "   WHERE (SELECT COALESCE(SUM(debit_amount),0) FROM journal_lines WHERE entry_id = NEW.entry_id)"
            + "      <> (SELECT COALESCE(SUM(credit_amount),0) FROM journal_lines WHERE entry_id = NEW.entry_id);"
            + "  SELECT RAISE(ABORT, 'Cannot post into a closed or missing accounting period.')"
            + "   WHERE NOT EXISTS (SELECT 1 FROM accounting_periods"
            + "                     WHERE period_id = NEW.accounting_period_id AND status = 'OPEN');"
            + " END",

        "CREATE TRIGGER IF NOT EXISTS trg_lines_no_insert BEFORE INSERT ON journal_lines"
            + " WHEN (SELECT status FROM journal_entries WHERE entry_id = NEW.entry_id) <> 'DRAFT'"
            + " BEGIN SELECT RAISE(ABORT, 'Lines of a posted entry cannot be changed.'); END",

        "CREATE TRIGGER IF NOT EXISTS trg_lines_no_update BEFORE UPDATE ON journal_lines"
            + " WHEN (SELECT status FROM journal_entries WHERE entry_id = OLD.entry_id) <> 'DRAFT'"
            + " BEGIN SELECT RAISE(ABORT, 'Lines of a posted entry cannot be changed.'); END",

        "CREATE TRIGGER IF NOT EXISTS trg_lines_no_delete BEFORE DELETE ON journal_lines"
            + " WHEN (SELECT status FROM journal_entries WHERE entry_id = OLD.entry_id) <> 'DRAFT'"
            + " BEGIN SELECT RAISE(ABORT, 'Lines of a posted entry cannot be deleted.'); END",

        "CREATE TRIGGER IF NOT EXISTS trg_audit_no_update BEFORE UPDATE ON audit_logs"
            + " BEGIN SELECT RAISE(ABORT, 'The audit trail cannot be edited.'); END",

        "CREATE TRIGGER IF NOT EXISTS trg_audit_no_delete BEFORE DELETE ON audit_logs"
            + " BEGIN SELECT RAISE(ABORT, 'The audit trail cannot be deleted.'); END",

        "CREATE TRIGGER IF NOT EXISTS trg_accounts_no_delete BEFORE DELETE ON accounts"
            + " WHEN EXISTS (SELECT 1 FROM journal_lines WHERE account_id = OLD.account_id)"
            + " BEGIN SELECT RAISE(ABORT, 'This account has accounting history. Deactivate it instead.'); END"
    };
}
