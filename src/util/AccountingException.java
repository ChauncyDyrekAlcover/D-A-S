package util;

/**
 * One exception type used for every "expected" problem (bad input, broken
 * accounting rule, database failure). The user interface catches it and shows
 * the message in a friendly dialog. It is a RuntimeException so that we do not
 * have to write "throws" on every method.
 */
public class AccountingException extends RuntimeException {
    public AccountingException(String message) {
        super(message);
    }

    public AccountingException(String message, Throwable cause) {
        super(message, cause);
    }
}
