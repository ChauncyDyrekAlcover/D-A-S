package util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Date helpers. Dates are typed and stored as yyyy-MM-dd (ISO), which also sorts correctly as text. */
public final class Dates {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private Dates() {
    }

    /** Blank text gives null; invalid text throws AccountingException. */
    public static LocalDate parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new AccountingException("\"" + text.trim() + "\" is not a valid date. Please use the format yyyy-MM-dd (example: 2026-10-20).");
        }
    }

    public static LocalDate require(String text, String fieldName) {
        LocalDate d = parse(text);
        if (d == null) {
            throw new AccountingException("Please enter the " + fieldName + " (format yyyy-MM-dd).");
        }
        return d;
    }

    public static String iso(LocalDate d) {
        return d == null ? "" : d.toString();
    }

    public static String now() {
        return LocalDateTime.now().format(TIMESTAMP);
    }

    public static String monthLabel(LocalDate d) {
        return MONTH.format(d);
    }
}
