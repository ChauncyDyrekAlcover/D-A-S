package model;

import java.time.LocalDate;
import java.util.List;

/** A journal entry header plus its lines. */
public record JournalEntry(int id, String number, LocalDate date, String description,
                           String reference, String category, String notes,
                           Status status, Type type, Journal journal, Integer periodId,
                           String createdAt, String postedAt,
                           Integer reversalOfId, Integer replacesId,
                           List<JournalLine> lines) {

    public enum Status { DRAFT, POSTED, REVERSED }

    /** What the entry is for. A reversal keeps the type of the entry it reverses. */
    public enum Type {
        REGULAR("Regular"), ADJUSTING("Adjusting"), CLOSING("Closing");
        private final String label;
        Type(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    /** Which book (columnar journal) the entry belongs to. */
    public enum Journal {
        GENERAL("General Journal"),
        CASH_RECEIPTS("Cash Receipts Journal"),
        CASH_DISBURSEMENTS("Cash Disbursements Journal"),
        SALES("Sales Journal"),
        PURCHASES("Purchases Journal");
        private final String label;
        Journal(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public long totalDebit() {
        long t = 0;
        for (JournalLine l : lines) {
            t += l.debit();
        }
        return t;
    }

    public long totalCredit() {
        long t = 0;
        for (JournalLine l : lines) {
            t += l.credit();
        }
        return t;
    }

    public boolean isReversal() {
        return reversalOfId != null;
    }

    /** A brand-new, unsaved draft. */
    public static JournalEntry newDraft(LocalDate date, String description, String reference,
                                        String category, String notes, Type type, Journal journal,
                                        List<JournalLine> lines) {
        return new JournalEntry(0, null, date, description, reference, category, notes,
                Status.DRAFT, type, journal, null, null, null, null, null, lines);
    }
}
