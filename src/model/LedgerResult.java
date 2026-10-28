package model;

import java.util.List;

public record LedgerResult(Account account, long opening, List<LedgerRow> rows,
                           long totalDebit, long totalCredit, long closing) {
}
