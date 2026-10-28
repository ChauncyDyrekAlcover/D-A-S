package ui;

import util.AccountingException;

import javax.swing.JOptionPane;
import java.awt.Component;

/** One place for message boxes, and a wrapper that turns exceptions into friendly messages. */
public final class Dialogs {
    private Dialogs() {
    }

    public static void error(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Cannot continue", JOptionPane.ERROR_MESSAGE);
    }

    public static void info(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Done", JOptionPane.INFORMATION_MESSAGE);
    }

    public static void warn(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Please note", JOptionPane.WARNING_MESSAGE);
    }

    public static boolean confirm(Component parent, String title, String message) {
        return JOptionPane.showConfirmDialog(parent, message, title, JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /** Runs an action; any AccountingException becomes an error dialog instead of a crash. */
    public static void guard(Component parent, Runnable action) {
        try {
            action.run();
        } catch (AccountingException e) {
            error(parent, e.getMessage());
        } catch (RuntimeException e) {
            e.printStackTrace();
            error(parent, "Unexpected problem: " + e);
        }
    }
}
