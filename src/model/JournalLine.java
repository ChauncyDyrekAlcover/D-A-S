package model;

/** One debit-or-credit line of a journal entry. Amounts are centavos. */
public record JournalLine(int id, int accountId, String accountNumber, String accountName,
                          String memo, long debit, long credit) {

    public static JournalLine of(int accountId, String memo, long debit, long credit) {
        return new JournalLine(0, accountId, "", "", memo, debit, credit);
    }
}
