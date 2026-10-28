package ui;

import javax.swing.JFileChooser;
import javax.swing.JTable;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.MessageFormat;

/** Export a table to CSV (opens in Excel) and print it. */
public final class TableTools {
    private TableTools() {
    }

    private static String csv(String s) {
        String t = s == null ? "" : s;
        return "\"" + t.replace("\"", "\"\"") + "\"";
    }

    public static void exportCsv(Component parent, JTable table, String suggestedName) {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(suggestedName + ".csv"));
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".csv")) {
            f = new File(f.getPath() + ".csv");
        }
        if (f.exists() && !Dialogs.confirm(parent, "File exists", f.getName() + " already exists. Replace it?")) {
            return;
        }
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(f.toPath()), StandardCharsets.UTF_8)) {
            w.write('\uFEFF'); // lets Excel show the peso sign correctly
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < table.getColumnCount(); c++) {
                line.append(c == 0 ? "" : ",").append(csv(table.getColumnName(c)));
            }
            w.write(line + "\r\n");
            for (int r = 0; r < table.getRowCount(); r++) {
                line.setLength(0);
                for (int c = 0; c < table.getColumnCount(); c++) {
                    Object v = table.getValueAt(r, c);
                    line.append(c == 0 ? "" : ",").append(csv(v == null ? "" : v.toString()));
                }
                w.write(line + "\r\n");
            }
        } catch (IOException e) {
            Dialogs.error(parent, "Could not write the file: " + e.getMessage());
            return;
        }
        Dialogs.info(parent, "Exported to " + f.getAbsolutePath());
    }

    public static void print(Component parent, JTable table, String title) {
        try {
            table.print(JTable.PrintMode.FIT_WIDTH, new MessageFormat(title), new MessageFormat("Page {0}"));
        } catch (Exception e) {
            Dialogs.error(parent, "Printing failed: " + e.getMessage());
        }
    }
}
