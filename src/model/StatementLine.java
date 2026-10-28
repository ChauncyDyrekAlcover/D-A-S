package model;

/** One line of a financial statement. amount == null means a heading with no number. */
public record StatementLine(String label, Long amount, int indent, boolean bold) {
}
