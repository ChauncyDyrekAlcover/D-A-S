import dao.JournalDao;
import database.Database;
import model.Account;
import model.AccountingPeriod;
import model.ColumnarJournal;
import model.JournalEntry;
import model.JournalEntry.Journal;
import model.JournalEntry.Status;
import model.JournalEntry.Type;
import model.JournalLine;
import model.LedgerResult;
import model.Statement;
import model.StatementLine;
import model.TrialBalance;
import service.AccountService;
import service.AuditService;
import service.AuthService;
import model.User;
import util.Session;
import service.BackupService;
import service.JournalService;
import service.PeriodService;
import service.ReportService;
import util.AccountingException;
import util.Money;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Automated test checklist. Run it with:  java -cp "out;lib/*" AccountingTests   (Mac/Linux: use : instead of ;)
 * It uses its OWN database file (data/test-accounting.db), so your real data is never touched.
 *
 * The sample transactions and expected totals below are also written in the README so you can
 * compare them by hand with the application.
 */
public class AccountingTests {
    static int passed = 0;
    static int failed = 0;
    static final AccountService accounts = new AccountService();
    static final JournalService journal = new JournalService();
    static final PeriodService periods = new PeriodService();
    static final ReportService reports = new ReportService();
    static int year = LocalDate.now().getYear();

    static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }

    static void expectError(String name, Runnable r) {
        try {
            r.run();
            check(name + " (expected an error, got none)", false);
        } catch (AccountingException e) {
            check(name + "  -> \"" + e.getMessage() + "\"", true);
        }
    }

    static int acct(String number) {
        for (Account a : accounts.list("", true)) {
            if (a.number().equals(number)) {
                return a.id();
            }
        }
        throw new IllegalStateException("No account " + number);
    }

    static LocalDate d(int month, int day) {
        return LocalDate.of(year, month, day);
    }

    static JournalEntry entry(LocalDate date, String desc, Type type, String drNo, String crNo, String pesos) {
        long cents = Money.parse(pesos);
        List<JournalLine> lines = new ArrayList<>();
        lines.add(JournalLine.of(acct(drNo), "", cents, 0));
        lines.add(JournalLine.of(acct(crNo), "", 0, cents));
        JournalEntry draft = JournalEntry.newDraft(date, desc, "REF", "", "", type, Journal.GENERAL, lines);
        return new JournalEntry(0, null, date, desc, "REF", "", "", Status.DRAFT, type,
                journal.suggestJournal(type, lines), null, null, null, null, null, lines);
    }

    static JournalEntry post(LocalDate date, String desc, Type type, String drNo, String crNo, String pesos) {
        return journal.saveAndPost(entry(date, desc, type, drNo, crNo, pesos));
    }

    static long amountOf(Statement s, String label) {
        for (StatementLine l : s.lines()) {
            if (l.label().equals(label) && l.amount() != null) {
                return l.amount();
            }
        }
        throw new IllegalStateException("No line " + label);
    }

    static int tableCount(String table) {
        return Database.read(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rs.next();
                return rs.getInt(1);
            }
        });
    }

    public static void main(String[] args) throws Exception {
        File dbFile = new File("data/test-accounting.db");
        dbFile.getParentFile().mkdirs();
        dbFile.delete();
        Database.setPath(dbFile.getPath());

        System.out.println("1. Database initialisation");
        Database.initialize();
        int seeded = tableCount("accounts");
        check("tables created and starter chart of accounts seeded (" + seeded + " accounts)", seeded >= 19);
        Database.initialize();
        check("running initialize() again does not duplicate data or recreate the file", tableCount("accounts") == seeded);
        check("a default accounting period exists", periods.list().size() == 1);

        System.out.println("2. Chart of accounts");
        Account created = accounts.create(new Account(0, "6010", "Test Expense", Account.Type.EXPENSE,
                Account.Normal.DEBIT, "temp", true));
        check("account created and retrieved", accounts.get(created.id()).name().equals("Test Expense"));
        expectError("duplicate account number rejected", () -> accounts.create(new Account(0, "6010", "Dup",
                Account.Type.EXPENSE, Account.Normal.DEBIT, "", true)));
        expectError("blank account name rejected", () -> accounts.create(new Account(0, "6020", " ",
                Account.Type.EXPENSE, Account.Normal.DEBIT, "", true)));
        accounts.setActive(created.id(), false);
        check("account deactivated", !accounts.get(created.id()).active());
        accounts.delete(created.id());
        check("unused account can be deleted", accounts.get(created.id()) == null);

        System.out.println("3. Validation of journal entries");
        List<JournalLine> unbalanced = List.of(JournalLine.of(acct("5010"), "", 500000, 0),
                JournalLine.of(acct("1010"), "", 0, 400000));
        expectError("unbalanced entry rejected", () -> journal.saveDraft(JournalEntry.newDraft(d(1, 5), "Rent",
                "R1", "", "", Type.REGULAR, Journal.GENERAL, unbalanced)));
        expectError("zero amount rejected", () -> journal.saveDraft(JournalEntry.newDraft(d(1, 5), "Rent",
                "R1", "", "", Type.REGULAR, Journal.GENERAL,
                List.of(JournalLine.of(acct("5010"), "", 0, 0), JournalLine.of(acct("1010"), "", 0, 100)))));
        expectError("single-line entry rejected", () -> journal.saveDraft(JournalEntry.newDraft(d(1, 5), "Rent",
                "R1", "", "", Type.REGULAR, Journal.GENERAL, List.of(JournalLine.of(acct("5010"), "", 100, 0)))));
        expectError("missing description rejected", () -> journal.saveDraft(entry(d(1, 5), " ", Type.REGULAR, "5010", "1010", "100")));
        check("no entry was stored by the rejected attempts", tableCount("journal_entries") == 0);

        System.out.println("4. Drafts and posting");
        JournalEntry draft = journal.saveDraft(entry(d(1, 2), "Owner invests cash", Type.REGULAR, "1010", "3010", "100000"));
        check("draft saved with a generated entry number " + draft.number(), draft.number().startsWith("JE-" + year + "-") && draft.status() == Status.DRAFT);
        check("draft does not affect the trial balance", reports.trialBalance(d(1, 1), d(12, 31), ReportService.TbMode.UNADJUSTED).rows().isEmpty());
        JournalEntry posted = journal.post(draft.id());
        check("entry posted", posted.status() == Status.POSTED);
        expectError("posting the same entry twice rejected", () -> journal.post(draft.id()));
        expectError("editing a posted entry rejected", () -> journal.saveDraft(new JournalEntry(posted.id(), posted.number(), posted.date(),
                "changed", "", "", "", Status.DRAFT, Type.REGULAR, Journal.GENERAL, null, null, null, null, null, posted.lines())));
        expectError("database trigger blocks changing a posted line", () -> Database.inTransaction(c -> {
            try (var st = c.createStatement()) {
                st.executeUpdate("UPDATE journal_lines SET debit_amount = 1 WHERE entry_id = " + posted.id());
            }
            return null;
        }));
        expectError("database trigger blocks posting an unbalanced entry", () -> Database.inTransaction(c -> {
            JournalDao dao = new JournalDao();
            JournalEntry e = entry(d(1, 3), "Bad", Type.REGULAR, "5010", "1010", "10");
            int id = dao.insertEntry(c, e, "JE-TEST-1", periods.list().get(0).id());
            dao.insertLines(c, id, List.of(JournalLine.of(acct("5010"), "", 1000, 0), JournalLine.of(acct("1010"), "", 0, 900)));
            dao.markPosted(c, id, "now", periods.list().get(0).id());
            return null;
        }));

        System.out.println("5. Sample transactions");
        post(d(1, 5), "Paid rent", Type.REGULAR, "5010", "1010", "5000");
        post(d(1, 10), "Bought supplies on account", Type.REGULAR, "1030", "2010", "3000");
        post(d(1, 15), "Service revenue on account", Type.REGULAR, "1020", "4010", "20000");
        post(d(2, 1), "Collected from customers", Type.REGULAR, "1010", "1020", "12000");
        post(d(2, 10), "Paid utilities", Type.REGULAR, "5020", "1010", "2500");
        post(d(2, 20), "Owner drawings", Type.REGULAR, "3020", "1010", "4000");
        post(d(3, 1), "Bought equipment for cash", Type.REGULAR, "1100", "1010", "30000");
        check("8 entries posted", tableCount("journal_entries") == 8);

        System.out.println("6. General ledger and running balances");
        LedgerResult cash = reports.ledger(acct("1010"), null, null);
        check("Cash ledger has 6 lines", cash.rows().size() == 6);
        check("Cash running balance after 2nd line is P95,000.00", cash.rows().get(1).balance() == 9_500_000);
        check("Cash ending balance is P70,500.00", cash.closing() == 7_050_000);
        LedgerResult cashFeb = reports.ledger(acct("1010"), d(2, 1), d(12, 31));
        check("Cash opening balance at Feb 1 is P95,000.00", cashFeb.opening() == 9_500_000 && cashFeb.rows().size() == 4);
        LedgerResult rev = reports.ledger(acct("4010"), null, null);
        check("Service Revenue (credit-normal) balance is P20,000.00", rev.closing() == 2_000_000);

        System.out.println("7. Columnar journals");
        ColumnarJournal crj = reports.columnar(Journal.CASH_RECEIPTS, d(1, 1), d(12, 31));
        check("Cash Receipts: Cash (Dr) total P112,000.00", crj.totals().get("1010 Cash (Dr)") == 11_200_000);
        check("Cash Receipts: debits equal credits", crj.totalDebits() == crj.totalCredits());
        ColumnarJournal cdj = reports.columnar(Journal.CASH_DISBURSEMENTS, d(1, 1), d(12, 31));
        check("Cash Disbursements: Cash (Cr) total P41,500.00", cdj.totals().get("1010 Cash (Cr)") == 4_150_000);
        ColumnarJournal sj = reports.columnar(Journal.SALES, d(1, 1), d(12, 31));
        check("Sales Journal total P20,000.00", sj.totalDebits() == 2_000_000);
        ColumnarJournal pj = reports.columnar(Journal.PURCHASES, d(1, 1), d(12, 31));
        check("Purchases Journal total P3,000.00", pj.totalDebits() == 300_000);
        int dataRows = 0;
        for (Journal j : Journal.values()) {
            ColumnarJournal cj = reports.columnar(j, d(1, 1), d(12, 31));
            dataRows += cj.rows().size() - cj.totalRows().size();
        }
        check("every entry appears in exactly one journal (8 rows in total)", dataRows == 8);
        check("date filter works (January only has 1 cash receipt)",
                reports.columnar(Journal.CASH_RECEIPTS, d(1, 1), d(1, 31)).rows().size() - 1 == 1);

        System.out.println("8. Trial balance");
        TrialBalance tb = reports.trialBalance(d(1, 1), d(12, 31), ReportService.TbMode.UNADJUSTED);
        check("trial balance balanced at P123,000.00", tb.balanced() && tb.totalDebit() == 12_300_000);

        System.out.println("9. Adjusting entries");
        post(d(12, 31), "Supplies used", Type.ADJUSTING, "5040", "1030", "1000");
        post(d(12, 31), "Depreciation", Type.ADJUSTING, "5050", "1110", "500");
        post(d(12, 31), "Accrued salaries", Type.ADJUSTING, "5030", "2020", "1500");
        TrialBalance adj = reports.trialBalance(d(1, 1), d(12, 31), ReportService.TbMode.ADJUSTED);
        check("adjusted trial balance balanced at P125,000.00", adj.balanced() && adj.totalDebit() == 12_500_000);
        check("unadjusted trial balance is unchanged (P123,000.00)",
                reports.trialBalance(d(1, 1), d(12, 31), ReportService.TbMode.UNADJUSTED).totalDebit() == 12_300_000);
        check("adjusting entries are numbered AJE and go to the General Journal",
                reports.columnar(Journal.GENERAL, d(1, 1), d(12, 31)).rows().size() >= 4);

        System.out.println("10. Financial statements");
        AccountingPeriod fy = periods.list().get(0);
        Statement is = reports.incomeStatement(fy);
        check("net income is P9,500.00", amountOf(is, "NET INCOME") == 950_000);
        check("income statement has no warnings", is.warnings().isEmpty());
        Statement bs = reports.balanceSheet(d(12, 31));
        check("total assets P110,000.00", amountOf(bs, "Total Assets") == 11_000_000);
        check("assets = liabilities + equity (no warning)", bs.warnings().isEmpty()
                && amountOf(bs, "TOTAL LIABILITIES AND OWNER'S EQUITY") == 11_000_000);
        Statement eq = reports.equityStatement(fy);
        check("ending equity P105,500.00 and agrees with the balance sheet",
                amountOf(eq, "Owner's Capital, end of period") == 10_550_000 && eq.warnings().isEmpty());

        System.out.println("11. Reversals");
        JournalEntry rent = journal.search(null, null, null, "Paid rent", Status.POSTED, null).get(0).id() > 0
                ? journal.get(journal.search(null, null, null, "Paid rent", Status.POSTED, null).get(0).id()) : null;
        JournalEntry reversal = journal.reverse(rent.id(), d(12, 15), "Wrong account");
        check("reversal posted and linked to the original", reversal.status() == Status.POSTED
                && reversal.reversalOfId() == rent.id() && journal.get(rent.id()).status() == Status.REVERSED);
        check("reversal swaps debits and credits", reversal.lines().get(0).credit() == rent.lines().get(0).debit());
        check("trial balance still balanced after reversal",
                reports.trialBalance(d(1, 1), d(12, 31), ReportService.TbMode.ADJUSTED).balanced());
        expectError("reversing twice rejected", () -> journal.reverse(rent.id(), d(12, 16), ""));
        expectError("reversing a reversal rejected", () -> journal.reverse(reversal.id(), d(12, 16), ""));
        JournalEntry replacement = journal.replacementTemplate(rent.id());
        JournalEntry replaced = journal.saveAndPost(replacement);
        check("replacement entry posted and linked", replaced.replacesId() == rent.id());
        check("net income unchanged after reverse + replace (P9,500.00)", amountOf(reports.incomeStatement(fy), "NET INCOME") == 950_000);

        System.out.println("12. Atomic operations and rollback");
        int before = tableCount("journal_entries");
        expectError("saveAndPost into a date with no period fails", () -> journal.saveAndPost(entry(LocalDate.of(year + 5, 1, 1), "Future", Type.REGULAR, "5010", "1010", "10")));
        check("failed saveAndPost left NO draft behind (rolled back)", tableCount("journal_entries") == before);
        expectError("manual transaction that throws is rolled back", () -> Database.inTransaction(c -> {
            new JournalDao().insertEntry(c, entry(d(5, 5), "Temp", Type.REGULAR, "5010", "1010", "10"), "JE-TEMP-1", null);
            throw new AccountingException("simulated failure");
        }));
        check("rolled-back insert is not in the database", tableCount("journal_entries") == before);

        System.out.println("13. Closing the period");
        JournalEntry stray = journal.saveDraft(entry(d(12, 20), "Stray draft", Type.REGULAR, "5020", "1010", "50"));
        expectError("closing is blocked while a draft exists", () -> periods.closePeriod(fy.id()));
        journal.deleteDraft(stray.id());
        check("closing preview has 4 entries (revenue, expenses, net income, drawings)", periods.previewClosing(fy.id()).size() == 4);
        List<JournalEntry> closing = periods.closePeriod(fy.id());
        check("4 closing entries posted", closing.size() == 4);
        check("period is now closed", periods.get(fy.id()).closed());
        expectError("posting into the closed period rejected", () -> post(d(12, 28), "Late", Type.REGULAR, "5010", "1010", "10"));
        expectError("closing a closed period rejected", () -> periods.closePeriod(fy.id()));
        TrialBalance pc = reports.trialBalance(fy.start(), fy.end(), ReportService.TbMode.POST_CLOSING);
        check("post-closing trial balance balanced at P110,500.00", pc.balanced() && pc.totalDebit() == 11_050_000);
        boolean temporaryLeft = false;
        for (var r : pc.rows()) {
            if (r.type() == Account.Type.REVENUE || r.type() == Account.Type.EXPENSE) {
                temporaryLeft = true;
            }
        }
        check("no revenue/expense balances remain after closing", !temporaryLeft);
        check("Owner's Capital after closing is P105,500.00", reports.ledger(acct("3010"), null, null).closing() == 10_550_000);
        check("income statement still shows P9,500.00 for the closed period", amountOf(reports.incomeStatement(fy), "NET INCOME") == 950_000);
        check("balance sheet still balances after closing", reports.balanceSheet(fy.end()).warnings().isEmpty());

        System.out.println("14. Audit trail");
        check("audit trail has records", new AuditService().search("", 1000).size() > 20);
        expectError("audit trail cannot be edited", () -> Database.inTransaction(c -> {
            try (var st = c.createStatement()) {
                st.executeUpdate("UPDATE audit_logs SET action = 'X'");
            }
            return null;
        }));
        expectError("audit trail cannot be deleted", () -> Database.inTransaction(c -> {
            try (var st = c.createStatement()) {
                st.executeUpdate("DELETE FROM audit_logs");
            }
            return null;
        }));

        System.out.println("15. Persistence, backup and restore");
        int entriesNow = tableCount("journal_entries");
        Database.initialize();
        check("data is still there after re-initialising (as after a restart)", tableCount("journal_entries") == entriesNow);
        File backup = new File("data/test-backup.db");
        backup.delete();
        BackupService backups = new BackupService();
        backups.backup(backup);
        check("backup file created", backup.isFile() && backup.length() > 0);
        expectError("backup refuses to overwrite an existing file", () -> backups.backup(backup));
        accounts.create(new Account(0, "9999", "After Backup", Account.Type.EXPENSE, Account.Normal.DEBIT, "", true));
        backups.restore(backup);
        check("restore brought back the backed-up data", accounts.list("9999", true).isEmpty() && tableCount("journal_entries") == entriesNow);
        backup.delete();

        System.out.println("16. Sign up, login and roles");
        periods.create("FY " + (year + 1), LocalDate.of(year + 1, 1, 1), LocalDate.of(year + 1, 12, 31));
        LocalDate next = LocalDate.of(year + 1, 6, 1);
        AuthService auth = new AuthService();
        check("no users exist at first", !auth.hasUsers());
        User admin = auth.signUp("owner", "Maria Santos", "Secret123".toCharArray(), "Secret123".toCharArray());
        check("first sign-up becomes ADMIN", admin.role() == User.Role.ADMIN);
        User acct = auth.signUp("juan", "Juan Cruz", "Secret456".toCharArray(), "Secret456".toCharArray());
        check("later sign-up becomes ACCOUNTANT", acct.role() == User.Role.ACCOUNTANT);
        expectError("duplicate username rejected (any letter case)", () -> auth.signUp("OWNER", "X Y", "Secret123".toCharArray(), "Secret123".toCharArray()));
        expectError("short password rejected", () -> auth.signUp("pedro", "Pedro", "abc1".toCharArray(), "abc1".toCharArray()));
        expectError("password without a number rejected", () -> auth.signUp("pedro", "Pedro", "abcdefgh".toCharArray(), "abcdefgh".toCharArray()));
        expectError("mismatched confirmation rejected", () -> auth.signUp("pedro", "Pedro", "Secret123".toCharArray(), "Secret124".toCharArray()));
        expectError("invalid username rejected", () -> auth.signUp("a b", "Pedro", "Secret123".toCharArray(), "Secret123".toCharArray()));
        expectError("wrong password rejected", () -> auth.login("juan", "wrongpass1".toCharArray()));
        expectError("unknown user rejected with the same message", () -> auth.login("nobody", "wrongpass1".toCharArray()));
        check("passwords are not stored in plain text", Database.read(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT password_hash FROM users WHERE username='juan'")) {
                rs.next();
                return !rs.getString(1).contains("Secret456") && rs.getString(1).length() > 30;
            }
        }));
        auth.login("JUAN", "Secret456".toCharArray());
        check("login works (username not case sensitive) and starts a session", Session.active() && "ACCOUNTANT".equals(Session.role()));
        JournalEntry byJuan = post(next, "Posted by accountant", Type.REGULAR, "5010", "1010", "1");
        check("accountant can record entries; created_by is the signed-in user", Database.read(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT created_by FROM journal_entries WHERE entry_id = " + byJuan.id())) {
                rs.next();
                return rs.getString(1).equals("juan");
            }
        }));
        expectError("accountant cannot manage users", () -> auth.listUsers());
        expectError("accountant cannot change settings", () -> new service.SettingsService().save(java.util.Map.of("business_name", "X")));
        auth.logout();
        check("logout ends the session", !Session.active());
        User viewer = null;
        auth.login("owner", "Secret123".toCharArray());
        auth.setRole(acct.id(), User.Role.VIEWER);
        auth.logout();
        auth.login("juan", "Secret456".toCharArray());
        expectError("viewer cannot post entries", () -> post(next, "Viewer try", Type.REGULAR, "5010", "1010", "1"));
        expectError("viewer cannot create accounts", () -> accounts.create(new Account(0, "7777", "V", Account.Type.EXPENSE, Account.Normal.DEBIT, "", true)));
        check("viewer can still read reports", reports.trialBalance(d(1, 1), next, ReportService.TbMode.ADJUSTED).balanced());
        auth.logout();
        auth.login("owner", "Secret123".toCharArray());
        expectError("the last administrator cannot be demoted", () -> auth.setRole(admin.id(), User.Role.VIEWER));
        expectError("the last administrator cannot be deactivated", () -> auth.setActive(admin.id(), false));
        auth.setActive(acct.id(), false);
        auth.logout();
        expectError("deactivated user cannot sign in", () -> auth.login("juan", "Secret456".toCharArray()));
        auth.login("owner", "Secret123".toCharArray());
        auth.setActive(acct.id(), true);
        auth.resetPassword(acct.id(), "NewPass789".toCharArray());
        auth.logout();
        auth.login("juan", "NewPass789".toCharArray());
        check("password reset works", Session.active());
        auth.logout();
        check("audit trail records logins", new AuditService().search("LOGIN", 100).size() >= 5);

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
