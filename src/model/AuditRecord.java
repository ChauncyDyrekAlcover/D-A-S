package model;

public record AuditRecord(int id, String time, String user, String action,
                          String recordType, String recordRef, String details) {
}
