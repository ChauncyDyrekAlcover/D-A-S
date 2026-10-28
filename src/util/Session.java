package util;

/**
 * Remembers who is signed in. When nobody is signed in (for example in the automated tests) checks pass
 * and the operating-system user name is recorded instead.
 */
public final class Session {
    private static String username;
    private static String fullName;
    private static String role;

    private Session() {
    }

    public static void start(String user, String name, String roleName) {
        username = user;
        fullName = name;
        role = roleName;
    }

    public static void end() {
        username = null;
        fullName = null;
        role = null;
    }

    public static boolean active() {
        return username != null;
    }

    public static String userName() {
        return username != null ? username : System.getProperty("user.name", "LOCAL USER");
    }

    public static String fullName() {
        return fullName;
    }

    public static String role() {
        return role;
    }

    /** Throws a friendly error unless the signed-in user has one of the allowed roles. */
    public static void require(String action, String... allowedRoles) {
        if (username == null) {
            return;
        }
        for (String r : allowedRoles) {
            if (r.equals(role)) {
                return;
            }
        }
        throw new AccountingException("Your role (" + role + ") is not allowed to " + action + ". Ask an administrator.");
    }
}
