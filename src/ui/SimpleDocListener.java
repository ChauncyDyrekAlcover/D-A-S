package ui;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** A DocumentListener with a single method, so a lambda can be used. */
@FunctionalInterface
public interface SimpleDocListener extends DocumentListener {
    void changed();

    @Override
    default void insertUpdate(DocumentEvent e) {
        changed();
    }

    @Override
    default void removeUpdate(DocumentEvent e) {
        changed();
    }

    @Override
    default void changedUpdate(DocumentEvent e) {
        changed();
    }
}
