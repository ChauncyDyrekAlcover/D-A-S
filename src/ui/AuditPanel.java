package ui;

import model.AuditRecord;
import service.AuditService;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;

/** Module K: read-only audit trail. */
public class AuditPanel extends Page {
    private final AuditService service = new AuditService();
    private final DataTable table = new DataTable();
    private final JTextField search = Theme.field(24);

    public AuditPanel() {
        super("Audit Trail", "Every important action is recorded here. The audit trail is read-only and cannot be edited or deleted.");
        JPanel bar = Theme.toolbar();
        bar.add(Theme.fieldLabel("SEARCH"));
        bar.add(search);
        JButton go = Theme.button("Search", Theme.Kind.PRIMARY);
        JButton export = Theme.button("Export CSV", Theme.Kind.SECONDARY);
        bar.add(go);
        bar.add(export);
        bar.add(Theme.label("Showing the latest 1,000 matching records.", 12, false, Theme.MUTED));
        body.add(bar, BorderLayout.NORTH);
        body.add(table, BorderLayout.CENTER);
        table.setSortable(false);
        table.setColumnWidths(150, 110, 170, 120, 140, 500);
        go.addActionListener(e -> refresh());
        search.addActionListener(e -> refresh());
        export.addActionListener(e -> TableTools.exportCsv(this, table.table(), "audit-trail"));
    }

    @Override
    public void refresh() {
        Dialogs.guard(this, () -> {
            List<Object[]> rows = new ArrayList<>();
            for (AuditRecord r : service.search(search.getText(), 1000)) {
                rows.add(new Object[]{r.time(), r.user(), r.action(), r.recordType(), r.recordRef(), r.details()});
            }
            table.setData(new String[]{"Time", "User", "Action", "Record Type", "Reference", "Details"}, rows);
        });
    }
}
