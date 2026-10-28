package util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Money helper. All money in this program is stored as a whole number of
 * CENTAVOS (a long). 1 peso = 100 centavos. This avoids the rounding errors of
 * double/float. Rounding rule: typed amounts with more than two decimals are
 * rounded HALF_UP to the nearest centavo.
 */
public final class Money {
    public static final String PESO = "\u20B1";
    public static final String DASH = "\u2014";

    private Money() {
    }

    /** Converts text such as "5,000", "₱5000.50" into centavos. Blank text gives 0. */
    public static long parse(String text) {
        if (text == null) {
            return 0;
        }
        String t = text.replace(PESO, "").replace(",", "").replace(" ", "").trim();
        if (t.isEmpty()) {
            return 0;
        }
        try {
            return new BigDecimal(t).multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new AccountingException("\"" + text + "\" is not a valid amount.");
        }
    }

    /** 123456 -> "1,234.56" (no peso sign). */
    public static String plain(long centavos) {
        long abs = Math.abs(centavos);
        String s = String.format(Locale.US, "%,d.%02d", abs / 100, abs % 100);
        return centavos < 0 ? "-" + s : s;
    }

    /** 123456 -> "₱1,234.56" */
    public static String format(long centavos) {
        return (centavos < 0 ? "-" : "") + PESO + plain(Math.abs(centavos));
    }

    /** Like format(), but zero becomes an em dash. Used in debit/credit columns. */
    public static String formatOrDash(long centavos) {
        return centavos == 0 ? DASH : format(centavos);
    }

    /** Financial-statement style: negatives in parentheses. */
    public static String statement(long centavos) {
        return centavos < 0 ? "(" + PESO + plain(-centavos) + ")" : PESO + plain(centavos);
    }
}
