package model;

import java.util.List;

public record TrialBalance(List<TrialBalanceRow> rows, long totalDebit, long totalCredit) {
    public boolean balanced() {
        return totalDebit == totalCredit;
    }
}
