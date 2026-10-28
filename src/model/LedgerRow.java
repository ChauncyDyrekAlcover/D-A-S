package model;

import java.time.LocalDate;

/** One line of an account's ledger. balance = running balance in the account's normal-balance direction. */
public record LedgerRow(int entryId, LocalDate date, String entryNumber, String reference,
                        String description, long debit, long credit, long balance, String period) {
}
