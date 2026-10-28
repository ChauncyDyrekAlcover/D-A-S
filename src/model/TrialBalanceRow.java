package model;

public record TrialBalanceRow(String number, String name, Account.Type type, long debit, long credit) {
}
