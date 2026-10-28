import database.Database;
import ui.LoginFrame;
import ui.MainFrame;
import ui.Theme;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Program entry point. Steps:
 *  1. open (or create) data/accounting.db and make sure all tables exist;
 *  2. show the Sign In / Sign Up window;
 *  3. after a successful sign-in, show the main window.
 * The database file is never replaced when the program starts.
 */
public class Main {
    public static void main(String[] args) {
        try {
            Database.initialize();
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(null, "The database could not be opened:\n\n" + e.getMessage(),
                    "Startup problem", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        SwingUtilities.invokeLater(() -> {
            Theme.install();
            showLogin();
        });
    }

    static void showLogin() {
        new LoginFrame(user -> new MainFrame(Main::showLogin).setVisible(true)).setVisible(true);
    }
}
