package model;

import java.time.LocalDate;

/** One row of the journal list (no lines loaded). amount = total debits in centavos. */
public record EntrySummary(int id, String number, LocalDate date, String description,
                           String reference, JournalEntry.Status status, JournalEntry.Type type,
                           JournalEntry.Journal journal, boolean reversal, long amount) {
}
