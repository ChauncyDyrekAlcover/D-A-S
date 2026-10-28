package ui;

import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * A styled, read-only table inside a scroll pane. Each row can carry a hidden id (for example the
 * entry id), so screens can find out which record the user selected.
 */
public class DataTable extends JScrollPane {
    private final JTable table = new JTable();
    private List<Integer> ids = new ArrayList<>();
    private Set<Integer> boldRows = new HashSet<>();
    private boolean sortable = true;
    private int[] widths = new int[0];
    private IntConsumer doubleClick;

    public DataTable() {
        table.setRowHeight(30);
        table.setShowGrid(false);
        table.setIntercellSpacing(new java.awt.Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setSelectionBackground(new Color(0xD6E4FF));
        table.setSelectionForeground(Theme.TEXT);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(false);
        table.setDefaultRenderer(Object.class, new CellRenderer());
        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setPreferredSize(new java.awt.Dimension(100, 34));
        header.setDefaultRenderer(new HeaderRenderer());
        setViewportView(table);
        setColumnHeaderView(table.getTableHeader());
        setBorder(javax.swing.BorderFactory.createLineBorder(Theme.BORDER));
        getViewport().setBackground(Color.WHITE);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && doubleClick != null) {
                    int id = selectedId();
                    if (id >= 0) {
                        doubleClick.accept(id);
                    }
                }
            }
        });
        setData(new String[]{" "}, new ArrayList<>());
    }

    public JTable table() {
        return table;
    }

    /** Gives any JTable the same header look as DataTable. */
    public static void styleHeader(JTable t) {
        t.getTableHeader().setDefaultRenderer(new HeaderRenderer());
        t.getTableHeader().setPreferredSize(new java.awt.Dimension(100, 34));
        t.getTableHeader().setReorderingAllowed(false);
    }

    public void setSortable(boolean sortable) {
        this.sortable = sortable;
    }

    public void setColumnWidths(int... widths) {
        this.widths = widths;
        applyWidths();
    }

    public void onDoubleClick(IntConsumer handler) {
        this.doubleClick = handler;
    }

    public void setData(String[] columns, List<Object[]> rows) {
        setData(columns, rows, new ArrayList<>(), new ArrayList<>());
    }

    public void setData(String[] columns, List<Object[]> rows, List<Integer> rowIds, Collection<Integer> bold) {
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        for (Object[] r : rows) {
            model.addRow(r);
        }
        this.ids = new ArrayList<>(rowIds);
        this.boldRows = new HashSet<>(bold);
        table.setModel(model);
        if (sortable) {
            TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
            for (int i = 0; i < columns.length; i++) {
                sorter.setComparator(i, SMART);
            }
            table.setRowSorter(sorter);
        } else {
            table.setRowSorter(null);
        }
        applyWidths();
    }

    private void applyWidths() {
        if (table.getColumnCount() == 0) {
            return;
        }
        boolean fixed = widths.length >= table.getColumnCount() || table.getColumnCount() > 8;
        table.setAutoResizeMode(table.getColumnCount() > 8 ? JTable.AUTO_RESIZE_OFF : JTable.AUTO_RESIZE_ALL_COLUMNS);
        for (int i = 0; i < table.getColumnCount(); i++) {
            TableColumn col = table.getColumnModel().getColumn(i);
            if (i < widths.length) {
                col.setPreferredWidth(widths[i]);
            } else if (fixed) {
                col.setPreferredWidth(150);
            }
        }
    }

    /** The hidden id of the selected row, or -1. */
    public int selectedId() {
        int view = table.getSelectedRow();
        if (view < 0) {
            return -1;
        }
        int model = table.convertRowIndexToModel(view);
        return model < ids.size() ? ids.get(model) : -1;
    }

    // ---- money-aware sorting -------------------------------------------------

    private static Long moneyValue(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString().trim();
        if (s.equals(util.Money.DASH) || s.isEmpty()) {
            return s.isEmpty() ? null : 0L;
        }
        boolean negative = s.startsWith("-") || s.startsWith("(");
        if (!s.contains(util.Money.PESO)) {
            return null;
        }
        String digits = s.replaceAll("[^0-9.]", "");
        if (digits.isEmpty()) {
            return null;
        }
        long v = util.Money.parse(digits);
        return negative ? -v : v;
    }

    private static final Comparator<Object> SMART = (a, b) -> {
        Long x = moneyValue(a);
        Long y = moneyValue(b);
        if (x != null && y != null) {
            return Long.compare(x, y);
        }
        return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
    };

    private static boolean isMoney(String s) {
        return s.startsWith(util.Money.PESO) || s.startsWith("-" + util.Money.PESO)
                || s.startsWith("(" + util.Money.PESO) || s.equals(util.Money.DASH);
    }

    // ---- renderers -----------------------------------------------------------

    private final class CellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                                                       int row, int col) {
            String text = value == null ? "" : value.toString();
            JLabel l = (JLabel) super.getTableCellRendererComponent(t, text, selected, false, row, col);
            l.setBorder(Theme.pad(0, 10, 0, 10));
            l.setHorizontalAlignment(isMoney(text) ? SwingConstants.RIGHT : SwingConstants.LEFT);
            int modelRow = t.convertRowIndexToModel(row);
            boolean bold = boldRows.contains(modelRow);
            l.setFont(Theme.font(bold ? Font.BOLD : Font.PLAIN, 13));
            l.setForeground(Theme.TEXT);
            l.setBackground(selected ? t.getSelectionBackground() : bold ? Theme.HEADER_BG
                    : (row % 2 == 0 ? Color.WHITE : Theme.ROW_ALT));
            switch (text) {
                case "DRAFT", "OPEN" -> badge(l, selected, Theme.AMBER, text.equals("OPEN") ? Theme.GREEN : Theme.AMBER);
                case "POSTED", "Active" -> badge(l, selected, Theme.GREEN, Theme.GREEN);
                case "REVERSED", "CLOSED", "Inactive" -> badge(l, selected, Theme.RED, Theme.RED);
                default -> {
                    if (text.startsWith("-" + util.Money.PESO)) {
                        l.setForeground(Theme.RED);
                    }
                }
            }
            return l;
        }

        private void badge(JLabel l, boolean selected, Color unused, Color fg) {
            if (!selected) {
                l.setForeground(fg);
            }
            l.setFont(Theme.font(Font.BOLD, 12));
        }
    }

    private static final class HeaderRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                                                       int row, int col) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(t, value, false, false, row, col);
            l.setOpaque(true);
            l.setBackground(Theme.HEADER_BG);
            l.setForeground(Theme.TEXT);
            l.setFont(Theme.font(Font.BOLD, 12));
            l.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                    javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), Theme.pad(0, 10, 0, 10)));
            l.setHorizontalAlignment(SwingConstants.LEFT);
            return l;
        }
    }
}
