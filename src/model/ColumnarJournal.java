package model;

import java.util.List;
import java.util.Map;

/**
 * A pivoted journal. headers = column titles, rows = text cells (already formatted),
 * totalRows = row numbers that are subtotal/total rows, totals = grand total per account column.
 */
public record ColumnarJournal(List<String> headers, List<String[]> rows, List<Integer> totalRows,
                              Map<String, Long> totals, long totalDebits, long totalCredits) {
}
