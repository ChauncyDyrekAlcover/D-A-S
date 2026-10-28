package model;

/** A person who can sign in. The password is never kept in this object. */
public record User(int id, String username, String fullName, Role role, boolean active, String createdAt) {

    /** ADMIN: everything. ACCOUNTANT: records, posts and reverses entries. VIEWER: read-only. */
    public enum Role { ADMIN, ACCOUNTANT, VIEWER }
}
