# Digital Accounting Process Automation System

A Java Swing desktop application with an SQLite database that automates the accounting cycle:
journal entries -> general ledger -> columnar journals -> trial balance -> adjusting entries ->
financial statements -> closing entries -> post-closing trial balance, with a full audit trail.

It is **not** a POS system. It has a **Sign In / Sign Up** screen with roles (see section 8).

---------------------------------------------------------------------
## 1. Setup (Phase 1)

### Java version
Install **JDK 17 or newer** (JDK 21 is fine). The code uses `record`, `switch` expressions and pattern
matching, which need 17+. Check with `java -version` and `javac -version`.

### SQLite JDBC driver (required, one-time)
Java talks to SQLite through a "driver" jar file.
1. Download **sqlite-jdbc-3.45.3.0.jar** (any recent 3.4x version works) from
   https://github.com/xerial/sqlite-jdbc/releases  (or Maven Central: `org.xerial:sqlite-jdbc`).
2. Put the jar inside the `lib/` folder of this project.
3. If you later see `NoClassDefFoundError: org/slf4j/...`, also download **slf4j-api-1.7.36.jar** into `lib/`.

### Folder structure
```
DigitalAccountingSystem/
  src/
    Main.java            entry point
    model/               plain data holders (Account, JournalEntry, ...)
    dao/                 SQL only: one class per table group (AccountDao, JournalDao, ...)
    service/             accounting rules: validation, posting, reversing, closing, reports
    ui/                  Swing screens and dialogs
    database/            Database.java (connection + transactions), Schema.java (tables, triggers)
    util/                Money, Dates, AccountingException
  lib/                   put sqlite-jdbc-*.jar here
  data/                  accounting.db is created here on first run (never overwritten)
  tests/AccountingTests.java
  compile.bat / run.bat / test.bat      (Windows)
  compile.sh  / run.sh  / test.sh       (Mac / Linux)
```

### Compile and run
Windows: double-click or run `compile.bat`, then `run.bat`.
Mac/Linux: `./compile.sh` then `./run.sh`.

Manual commands (from the project folder):
```
Windows : javac -encoding UTF-8 -cp "lib/*" -d out @sources.txt      (after:  dir /s /b src\*.java > sources.txt)
          java -cp "out;lib/*" Main
Linux   : javac -encoding UTF-8 -cp "lib/*" -d out $(find src -name "*.java")
          java -cp "out:lib/*" Main
```

### jGRASP
File > New > Project, add the `src` folder as the source path. Set **Project > Properties > Paths** classpath to
`lib/sqlite-jdbc-3.45.3.0.jar` (plus slf4j if needed), output directory `out`, and run class `Main`.
Add `-encoding UTF-8` to the compiler flags (Settings > Compiler Settings > Flags/Args) so the peso sign compiles.

### Test the database connection
Run the automated checklist (it uses its own file `data/test-accounting.db`, never your real data):
```
test.bat        (Windows)        ./test.sh   (Mac / Linux)
```
The first section prints `PASS tables created and starter chart of accounts seeded (19 accounts)`.
At the end you should see `RESULT: 95 passed, 0 failed`.

---------------------------------------------------------------------
## 2. How the pieces connect (beginner explanation)

* **model** = nouns: `Account`, `JournalEntry`, `JournalLine`. Just data, no SQL, no rules.
* **dao** (Data Access Object) = the only classes that contain SQL. `JournalDao.insertEntry(...)` turns a Java
  object into a row. Keeping SQL here means the screens never touch the database directly.
* **service** = the accounting brain. `JournalService.post(id)` checks the rules, then asks the DAO to save.
  Every change runs inside `Database.inTransaction(...)`: all steps succeed together or are all rolled back.
* **ui** = Swing windows. They call services and show results; they contain no SQL.
* **Money** is stored as whole **centavos** (`long`). 1 peso = 100 centavos. No `double` is used for money.
  Typed amounts with more than 2 decimals round HALF_UP.

### How a journal entry is saved and posted
1. You fill in the form. Live totals show debits, credits and the difference.
2. **Save Draft** -> `JournalService.saveDraft` validates (date, description, >= 2 lines, valid active accounts,
   positive amounts, debits = credits, period not closed) and inserts rows into `journal_entries` and `journal_lines`.
   A draft never touches the ledger or any report.
3. **Post** -> status changes DRAFT -> POSTED in one transaction. SQLite triggers re-check balance and period.
4. Posting does not copy anything: the **General Ledger is calculated** from posted `journal_lines`, so an entry
   appears exactly once and the ledger can never disagree with the journal.
5. Mistakes: use **Reverse**. A new entry with debits/credits swapped is posted, the original is marked REVERSED,
   and both stay linked. You are then offered a pre-filled replacement entry.

### How the columnar journals work
Each entry belongs to exactly ONE book (stored in `journal_entries.journal_type`), so nothing is counted twice.
The system *suggests* the book and you confirm it (drop-down on the entry form):

| Rule (in this order) | Book |
|---|---|
| Adjusting or closing entry | General Journal |
| Cash is debited only | Cash Receipts Journal |
| Cash is credited only | Cash Disbursements Journal |
| No cash, Accounts Receivable debited, revenue credited | Sales Journal (if switched on) |
| No cash, Accounts Payable credited | Purchases Journal (if switched on) |
| Anything else | General Journal |

Columns are built automatically: one column per account/side used (debit columns first, then credit columns),
with monthly totals and a period total. Which accounts count as Cash / A/R / A/P is configured in **Settings**.

---------------------------------------------------------------------
## 3. What is automated, and what needs the accountant

| Automated | Accountant decides / approves |
|---|---|
| Entry numbers (JE, AJE, RJE, CJE) | Which accounts to debit and credit, and the amounts |
| Totals and balance check | Confirming the suggested columnar journal |
| Ledger, running balances | Posting (confirmation dialog) |
| Trial balances, statements, columnar journals | All adjusting entry amounts (templates leave the amount **empty**) |
| Closing-entry generation | Reviewing and confirming the closing preview |
| Audit logging | Reversals and their reasons |

Rules enforced both in Java **and** inside SQLite (triggers): posted entries/lines cannot be edited or deleted,
posting needs >= 2 lines with debits = credits and an open period, the audit trail cannot be edited or deleted.

## 4. The accounting cycle in the app
1. **Chart of Accounts** - standard starter chart is created on first run; add or edit accounts.
2. **General Journal** - encode, review, post.
3. **General Ledger** - pick an account and a date range; double-click a line to open its entry.
4. **Columnar Journals** - Cash Receipts, Cash Disbursements, Sales, Purchases, General.
5. **Trial Balance** - choose *Unadjusted*.
6. **Adjusting Entries** - templates for supplies, depreciation, accruals, prepaid, unearned revenue; you enter amounts.
7. **Trial Balance** - choose *Adjusted*.
8. **Financial Statements** - Income Statement, Statement of Changes in Equity, Balance Sheet (warnings shown for drafts,
   missing adjustments, or an imbalance).
9. **Accounting Periods** - review unposted drafts, preview the closing entries, close. Closing posts real closing entries
   (revenue -> Income Summary, expenses -> Income Summary, net income -> Owner's Capital, drawings -> Owner's Capital).
10. **Trial Balance** - choose *Post-Closing*; only permanent accounts remain.
Audit Trail and Settings (account roles, backup/restore) are in the sidebar.

Not included: the optional Cash Flow Statement (it needs cash-flow classification of every account), and re-opening a
closed period (closed periods are permanent history). Sole-proprietorship equity (Owner's Capital/Drawings) is assumed.

---------------------------------------------------------------------
## 5. Sample transactions with expected results
Use any year; dates below are in the same year. Starter chart account numbers in brackets.

| # | Date | Transaction | Debit | Credit | Amount | Book |
|---|---|---|---|---|---|---|
| 1 | Jan 2 | Owner invests cash | Cash (1010) | Owner's Capital (3010) | 100,000 | Cash Receipts |
| 2 | Jan 5 | Paid rent | Rent Expense (5010) | Cash | 5,000 | Cash Disbursements |
| 3 | Jan 10 | Supplies on account | Supplies (1030) | Accounts Payable (2010) | 3,000 | Purchases |
| 4 | Jan 15 | Service revenue on account | Accounts Receivable (1020) | Service Revenue (4010) | 20,000 | Sales |
| 5 | Feb 1 | Collected from customers | Cash | Accounts Receivable | 12,000 | Cash Receipts |
| 6 | Feb 10 | Paid utilities | Utilities Expense (5020) | Cash | 2,500 | Cash Disbursements |
| 7 | Feb 20 | Owner drawings | Owner's Drawings (3020) | Cash | 4,000 | Cash Disbursements |
| 8 | Mar 1 | Bought equipment for cash | Equipment (1100) | Cash | 30,000 | Cash Disbursements |

**Expected after posting 1-8**
* Cash ledger running balance: 100,000 -> 95,000 -> 107,000 -> 104,500 -> 100,500 -> 70,500
* Trial Balance: Cash 70,500 Dr; A/R 8,000 Dr; Supplies 3,000 Dr; Equipment 30,000 Dr; Drawings 4,000 Dr;
  Rent 5,000 Dr; Utilities 2,500 Dr; A/P 3,000 Cr; Capital 100,000 Cr; Service Revenue 20,000 Cr.
  **Totals 123,000 = 123,000.**
* Cash Receipts Journal: Cash (Dr) 112,000. Cash Disbursements Journal: Cash (Cr) 41,500.
  Sales Journal 20,000. Purchases Journal 3,000.

**Adjusting entries (Dec 31)**: Supplies used 1,000 (Dr 5040 / Cr 1030); Depreciation 500 (Dr 5050 / Cr 1110);
Accrued salaries 1,500 (Dr 5030 / Cr 2020).
* Adjusted Trial Balance totals **125,000 = 125,000** (unadjusted stays 123,000).
* Net income = 20,000 - 10,500 = **9,500**. Total assets **110,000**. Liabilities 4,500.
  Owner's Capital end = 100,000 + 9,500 - 4,000 = **105,500**. Assets = 4,500 + 105,500 + ... = 110,000.
* After closing: Post-Closing Trial Balance totals **110,500 = 110,500**, no revenue/expense accounts remain.

## 6. Testing checklist
Automated: `AccountingTests` covers database initialisation, creating/retrieving accounts, saving a balanced entry,
rejecting unbalanced/zero/one-line entries, preventing duplicate posting, multi-account posting, running balances,
columnar totals, trial balance, adjusting entries, statements, reversals, closed-period rejection, restart
persistence, rollback after failure, audit-trail protection, backup and restore.

Manual (compare with section 5): enter the 8 transactions in the General Journal, then check each report.
Close the app and reopen it: all data is still there. Try to post an unbalanced entry: it is refused.
Try to post twice: refused. Post into a closed period: refused.

## 7. Backups
Settings > *Back Up Now* writes a consistent snapshot to a NEW file (existing files are never overwritten).
*Restore From Backup* first saves the current database to `data/backups/before-restore-*.db`.
You can also simply copy `data/accounting.db` while the program is closed.

---------------------------------------------------------------------
## 8. Sign up, sign in and roles
* On the very first start there are no accounts, so the window opens on **Sign Up**. The first account you create
  becomes the **Admin**. Everyone who signs up later becomes an **Accountant**; an Admin can change roles under **Users**.
* Passwords need 8+ characters with a letter and a number. They are never stored: only a salted PBKDF2-HMAC-SHA256 hash is
  saved in the `users` table. Wrong username and wrong password give the same message.
* Roles: **Admin** - everything (settings, users, closing periods, restore). **Accountant** - chart of accounts, entries,
  posting, reversing, adjusting, reports, backups. **Viewer** - read-only (reports, ledger, journals).
* The audit trail records SIGN UP, LOGIN, LOGIN FAILED, LOGOUT, role changes and password resets, and every entry shows
  who created it. There must always be at least one active Admin.
* Forgot the only Admin password? Copy `data/accounting.db` as a backup, then delete the rows of the `users` table with any
  SQLite tool, and sign up again (the first new account becomes Admin). Your accounting data is not affected.
