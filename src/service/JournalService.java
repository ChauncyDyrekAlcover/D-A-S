package service;

import dao.AccountDao;
import dao.JournalDao;
import dao.PeriodDao;
import database.Database;
import model.Account;
import model.AccountingPeriod;
import model.EntrySummary;
import model.JournalEntry;
import model.JournalEntry.Journal;
import model.JournalEntry.Status;
import model.JournalEntry.Type;
import model.JournalLine;
import util.AccountingException;
import util.Session;
import util.Dates;
import util.Money;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The heart of the system: validating, saving, posting and reversing journal entries.
 * Every method that changes data runs inside ONE database transaction.
 */
public class JournalService {
    private final JournalDao dao = new JournalDao();
    private final AccountDao accountDao = new AccountDao();
    private final PeriodDao periodDao = new PeriodDao();
    private final AuditService audit = new AuditService();
    private final SettingsService settings = new SettingsService();

    // ------------------------------------------------------------------ reading

    public JournalEntry get(int id) {
        return Database.read(c -> {
            JournalEntry e = dao.findById(c, id);
            if (e == null) {
                throw new AccountingException("That journal entry no longer exists.");
            }
            return e;
        });
    }

    /** Number of entries per status (for the dashboard). */
    public java.util.Map<Status, Integer> countByStatus() {
        return Database.read(dao::countByStatus);
    }

    public List<EntrySummary> search(LocalDate from, LocalDate to, Integer accountId, String text,
                                     Status status, Type type) {
        return Database.read(c -> dao.search(c, from, to, accountId, text, status, type));
    }

    /** Text such as "Reversal of JE-2026-00001 | Reversed by RJE-2026-00001" for the entry window. */
    public String relationText(JournalEntry e) {
        return Database.read(c -> {
            List<String> parts = new ArrayList<>();
            if (e.reversalOfId() != null) {
                parts.add("Reverses " + dao.numberOf(c, e.reversalOfId()));
            }
            String by = dao.reversalNumberFor(c, e.id());
            if (by != null) {
                parts.add("Reversed by " + by);
            }
            if (e.replacesId() != null) {
                parts.add("Replaces " + dao.numberOf(c, e.replacesId()));
            }
            return String.join("   |   ", parts);
        });
    }

    // ------------------------------------------------------------------ classification

    /**
     * Suggests which book an entry belongs to. The accountant can always change it.
     * Rules (the same every time):
     *  - adjusting and closing entries always go to the General Journal;
     *  - cash only debited          -> Cash Receipts Journal;
     *  - cash only credited         -> Cash Disbursements Journal;
     *  - no cash, Accounts Receivable debited and a Revenue credited -> Sales Journal (if switched on);
     *  - no cash, Accounts Payable credited                          -> Purchases Journal (if switched on);
     *  - anything else              -> General Journal.
     */
    public Journal suggestJournal(Type type, List<JournalLine> lines) {
        if (type != Type.REGULAR) {
            return Journal.GENERAL;
        }
        return Database.read(c -> {
            Account cash = settings.accountFor(c, SettingsService.CASH);
            Account ar = settings.accountFor(c, SettingsService.AR);
            Account ap = settings.accountFor(c, SettingsService.AP);
            long cashDr = 0, cashCr = 0;
            boolean arDebited = false, apCredited = false, revenueCredited = false;
            for (JournalLine l : lines) {
                Account a = accountDao.findById(c, l.accountId());
                if (a == null) {
                    continue;
                }
                if (cash != null && a.id() == cash.id()) {
                    cashDr += l.debit();
                    cashCr += l.credit();
                }
                if (ar != null && a.id() == ar.id() && l.debit() > 0) {
                    arDebited = true;
                }
                if (ap != null && a.id() == ap.id() && l.credit() > 0) {
                    apCredited = true;
                }
                if (a.type() == Account.Type.REVENUE && l.credit() > 0) {
                    revenueCredited = true;
                }
            }
            if (cashDr > 0 && cashCr == 0) {
                return Journal.CASH_RECEIPTS;
            }
            if (cashCr > 0 && cashDr == 0) {
                return Journal.CASH_DISBURSEMENTS;
            }
            if (cashDr == 0 && cashCr == 0) {
                if (arDebited && revenueCredited && settings.isOn(c, SettingsService.SALES_ON)) {
                    return Journal.SALES;
                }
                if (apCredited && settings.isOn(c, SettingsService.PURCHASES_ON)) {
                    return Journal.PURCHASES;
                }
            }
            return Journal.GENERAL;
        });
    }

    // ------------------------------------------------------------------ validation

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Checks every rule that must hold before an entry is stored. Returns the period (or null if none covers the date). */
    private AccountingPeriod validate(Connection c, JournalEntry e, boolean enforceActive) throws SQLException {
        if (e.date() == null) {
            throw new AccountingException("Please enter a valid transaction date.");
        }
        if (blank(e.description())) {
            throw new AccountingException("Please enter a description for the transaction.");
        }
        if (e.description().trim().length() > 200) {
            throw new AccountingException("The description is too long (maximum 200 characters).");
        }
        List<JournalLine> lines = e.lines();
        if (lines == null || lines.size() < 2) {
            throw new AccountingException("A journal entry needs at least two lines (at least one debit and one credit).");
        }
        long debits = 0;
        long credits = 0;
        int n = 0;
        for (JournalLine l : lines) {
            n++;
            if (l.accountId() <= 0) {
                throw new AccountingException("Line " + n + ": please choose an account.");
            }
            Account a = accountDao.findById(c, l.accountId());
            if (a == null) {
                throw new AccountingException("Line " + n + ": the account does not exist.");
            }
            if (enforceActive && !a.active()) {
                throw new AccountingException("Line " + n + ": account " + a + " is inactive and cannot be used.");
            }
            if (l.debit() < 0 || l.credit() < 0) {
                throw new AccountingException("Line " + n + ": amounts cannot be negative.");
            }
            if (l.debit() > 0 && l.credit() > 0) {
                throw new AccountingException("Line " + n + ": enter either a debit or a credit, not both. Use two lines instead.");
            }
            if (l.debit() == 0 && l.credit() == 0) {
                throw new AccountingException("Line " + n + ": the amount must be greater than zero.");
            }
            debits = Math.addExact(debits, l.debit());
            credits = Math.addExact(credits, l.credit());
        }
        if (debits != credits) {
            throw new AccountingException("The entry is not balanced. Total debits " + Money.format(debits)
                    + " and total credits " + Money.format(credits) + " differ by " + Money.format(Math.abs(debits - credits)) + ".");
        }
        AccountingPeriod p = periodDao.findByDate(c, e.date());
        if (p != null && p.closed()) {
            throw new AccountingException("The accounting period \"" + p.name() + "\" is closed. Entries cannot be added to a closed period.");
        }
        return p;
    }

    private static String prefix(Type type, boolean reversal) {
        if (reversal) {
            return "RJE";
        }
        return switch (type) {
            case REGULAR -> "JE";
            case ADJUSTING -> "AJE";
            case CLOSING -> "CJE";
        };
    }

    private JournalEntry normalize(JournalEntry e) {
        return new JournalEntry(e.id(), e.number(), e.date(), e.description().trim(),
                blank(e.reference()) ? "" : e.reference().trim(), blank(e.category()) ? "" : e.category().trim(),
                blank(e.notes()) ? "" : e.notes().trim(), e.status(), e.type(),
                e.journal() == null ? Journal.GENERAL : e.journal(), e.periodId(), e.createdAt(), e.postedAt(),
                e.reversalOfId(), e.replacesId(), e.lines());
    }

    // ------------------------------------------------------------------ saving drafts

    public JournalEntry saveDraft(JournalEntry entry) {
        Session.require("record or change journal entries", "ADMIN", "ACCOUNTANT");
        return Database.inTransaction(c -> saveDraftTx(c, entry));
    }

    private JournalEntry saveDraftTx(Connection c, JournalEntry input) throws SQLException {
        JournalEntry e = normalize(input);
        AccountingPeriod p = validate(c, e, true);
        Integer periodId = p == null ? null : p.id();
        if (e.type() == Type.ADJUSTING) {
            e = withJournal(e, Journal.GENERAL);
        }
        int id;
        if (e.id() == 0) {
            String number = dao.nextNumber(c, prefix(e.type(), e.isReversal()), e.date().getYear());
            id = dao.insertEntry(c, e, number, periodId);
            dao.insertLines(c, id, e.lines());
            audit.log(c, "CREATE ENTRY", "Journal Entry", number, "Draft created: " + e.description()
                    + " (" + Money.format(e.totalDebit()) + ")");
        } else {
            JournalEntry old = dao.findById(c, e.id());
            if (old == null) {
                throw new AccountingException("That entry no longer exists.");
            }
            if (old.status() != Status.DRAFT) {
                throw new AccountingException("Only draft entries can be edited. Posted entries must be corrected with a reversing entry.");
            }
            id = old.id();
            dao.updateHeader(c, withJournal(e, e.journal()), periodId);
            dao.deleteLines(c, id);
            dao.insertLines(c, id, e.lines());
            audit.log(c, "EDIT DRAFT", "Journal Entry", old.number(), "Draft edited: " + e.description()
                    + " (" + Money.format(e.totalDebit()) + ")");
        }
        return dao.findById(c, id);
    }

    private static JournalEntry withJournal(JournalEntry e, Journal j) {
        return new JournalEntry(e.id(), e.number(), e.date(), e.description(), e.reference(), e.category(), e.notes(),
                e.status(), e.type(), j, e.periodId(), e.createdAt(), e.postedAt(), e.reversalOfId(), e.replacesId(), e.lines());
    }

    public void deleteDraft(int id) {
        Session.require("record or change journal entries", "ADMIN", "ACCOUNTANT");
        Database.inTransaction(c -> {
            JournalEntry e = dao.findById(c, id);
            if (e == null) {
                throw new AccountingException("That entry no longer exists.");
            }
            if (e.status() != Status.DRAFT) {
                throw new AccountingException("Only draft entries can be deleted. Posted entries must be reversed.");
            }
            dao.deleteEntry(c, id);
            audit.log(c, "DELETE DRAFT", "Journal Entry", e.number(), "Draft deleted: " + e.description());
            return null;
        });
    }

    // ------------------------------------------------------------------ posting

    public JournalEntry post(int id) {
        Session.require("record or change journal entries", "ADMIN", "ACCOUNTANT");
        return Database.inTransaction(c -> postTx(c, id));
    }

    /** Saves and posts in one transaction: if posting fails, the draft is not saved either. */
    public JournalEntry saveAndPost(JournalEntry entry) {
        Session.require("record or change journal entries", "ADMIN", "ACCOUNTANT");
        return Database.inTransaction(c -> {
            JournalEntry saved = saveDraftTx(c, entry);
            return postTx(c, saved.id());
        });
    }

    private JournalEntry postTx(Connection c, int id) throws SQLException {
        JournalEntry e = dao.findById(c, id);
        if (e == null) {
            throw new AccountingException("That entry no longer exists.");
        }
        if (e.status() != Status.DRAFT) {
            throw new AccountingException("Entry " + e.number() + " is already " + e.status().name().toLowerCase()
                    + " and cannot be posted again.");
        }
        AccountingPeriod p = validate(c, e, e.type() != Type.CLOSING);
        if (p == null) {
            throw new AccountingException("No accounting period covers " + e.date()
                    + ". Create one under Accounting Periods first.");
        }
        if (!dao.markPosted(c, id, Dates.now(), p.id())) {
            throw new AccountingException("Entry " + e.number() + " could not be posted because it changed. Please reload.");
        }
        audit.log(c, "POST ENTRY", "Journal Entry", e.number(), "Posted " + Money.format(e.totalDebit()) + " into " + p.name());
        return dao.findById(c, id);
    }

    // ------------------------------------------------------------------ reversing / correcting

    /**
     * Corrects a posted entry the auditable way: a NEW entry with debits and credits swapped is
     * posted, the original is marked REVERSED, and the two stay linked.
     */
    public JournalEntry reverse(int id, LocalDate reversalDate, String reason) {
        Session.require("record or change journal entries", "ADMIN", "ACCOUNTANT");
        return Database.inTransaction(c -> {
            JournalEntry o = dao.findById(c, id);
            if (o == null) {
                throw new AccountingException("That entry no longer exists.");
            }
            if (o.status() == Status.REVERSED) {
                throw new AccountingException("Entry " + o.number() + " has already been reversed.");
            }
            if (o.status() != Status.POSTED) {
                throw new AccountingException("Only posted entries can be reversed. Draft entries can simply be edited or deleted.");
            }
            if (o.isReversal()) {
                throw new AccountingException("A reversing entry cannot itself be reversed. Enter a new correcting entry instead.");
            }
            if (o.type() == Type.CLOSING) {
                throw new AccountingException("Closing entries cannot be reversed individually.");
            }
            if (reversalDate == null) {
                throw new AccountingException("Please enter the reversal date.");
            }
            AccountingPeriod p = periodDao.findByDate(c, reversalDate);
            if (p == null) {
                throw new AccountingException("No accounting period covers " + reversalDate + ".");
            }
            if (p.closed()) {
                throw new AccountingException("The accounting period \"" + p.name() + "\" is closed. Choose a reversal date in an open period.");
            }
            List<JournalLine> swapped = new ArrayList<>();
            for (JournalLine l : o.lines()) {
                swapped.add(JournalLine.of(l.accountId(), l.memo(), l.credit(), l.debit()));
            }
            String desc = "Reversal of " + o.number() + (blank(reason) ? "" : ": " + reason.trim());
            if (desc.length() > 200) {
                desc = desc.substring(0, 200);
            }
            JournalEntry r = new JournalEntry(0, null, reversalDate, desc, o.reference(), o.category(),
                    "Reverses " + o.number(), Status.DRAFT, o.type(), o.journal(), p.id(), null, null, o.id(), null, swapped);
            String number = dao.nextNumber(c, "RJE", reversalDate.getYear());
            int rid = dao.insertEntry(c, r, number, p.id());
            dao.insertLines(c, rid, swapped);
            if (!dao.markPosted(c, rid, Dates.now(), p.id())) {
                throw new AccountingException("The reversing entry could not be posted.");
            }
            if (!dao.markReversed(c, o.id())) {
                throw new AccountingException("The original entry could not be marked as reversed.");
            }
            audit.log(c, "REVERSE ENTRY", "Journal Entry", o.number(), "Reversed by " + number
                    + (blank(reason) ? "" : " - " + reason.trim()));
            return dao.findById(c, rid);
        });
    }

    /** An unsaved copy of a reversed entry, ready to be corrected and saved as the replacement. */
    public JournalEntry replacementTemplate(int reversedEntryId) {
        JournalEntry o = get(reversedEntryId);
        if (o.status() != Status.REVERSED) {
            throw new AccountingException("Only a reversed entry can be replaced.");
        }
        List<JournalLine> copy = new ArrayList<>();
        for (JournalLine l : o.lines()) {
            copy.add(new JournalLine(0, l.accountId(), l.accountNumber(), l.accountName(), l.memo(), l.debit(), l.credit()));
        }
        return new JournalEntry(0, null, LocalDate.now(), o.description(), o.reference(), o.category(), o.notes(),
                Status.DRAFT, o.type(), o.journal(), null, null, null, null, o.id(), copy);
    }
}
