package ui;

import model.User;

import javax.swing.JFrame;
import javax.swing.WindowConstants;
import java.awt.Dimension;
import java.util.function.Consumer;

/** The window that holds the login screen. */
public class LoginFrame extends JFrame {
    public LoginFrame(Consumer<User> onSuccess) {
        super("Sign in - Digital Accounting Process Automation System");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setContentPane(new LoginPanel(user -> {
            dispose();
            onSuccess.accept(user);
        }));
        setSize(new Dimension(920, 600));
        setResizable(false);
        setLocationRelativeTo(null);
    }
}
