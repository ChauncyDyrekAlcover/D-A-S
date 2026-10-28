package service;

import dao.UserDao;
import database.Database;
import model.User;
import util.AccountingException;
import util.Session;
import util.Dates;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Sign-up, login and user management.
 * Passwords are NEVER stored. Only a salted PBKDF2-HMAC-SHA256 hash is saved, so even someone who
 * opens the database file cannot read the passwords.
 */
public class AuthService {
    private static final int ITERATIONS = 120_000;
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.]{3,30}");
    private final UserDao dao = new UserDao();
    private final AuditService audit = new AuditService();

    public boolean hasUsers() {
        return Database.read(c -> dao.count(c) > 0);
    }

    // ------------------------------------------------------------------ password hashing

    private static String hash(char[] password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
            byte[] bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            spec.clearPassword();
            return Base64.getEncoder().encodeToString(bytes);
        } catch (GeneralSecurityException e) {
            throw new AccountingException("Password hashing is not available on this computer: " + e.getMessage(), e);
        }
    }

    private static boolean verify(char[] password, String saltB64, String expectedB64) {
        byte[] actual = hash(password, Base64.getDecoder().decode(saltB64)).getBytes();
        return MessageDigest.isEqual(actual, expectedB64.getBytes()); // constant-time comparison
    }

    private static void wipe(char[] chars) {
        if (chars != null) {
            Arrays.fill(chars, ' ');
        }
    }

    private static void checkPassword(char[] password) {
        if (password == null || password.length < 8) {
            throw new AccountingException("The password must be at least 8 characters long.");
        }
        boolean letter = false;
        boolean digit = false;
        for (char ch : password) {
            letter |= Character.isLetter(ch);
            digit |= Character.isDigit(ch);
        }
        if (!letter || !digit) {
            throw new AccountingException("The password must contain at least one letter and one number.");
        }
    }

    // ------------------------------------------------------------------ sign up / login

    /** The very first account becomes the ADMIN; every later sign-up becomes an ACCOUNTANT. */
    public User signUp(String username, String fullName, char[] password, char[] confirm) {
        try {
            String u = username == null ? "" : username.trim();
            String name = fullName == null ? "" : fullName.trim();
            if (name.isEmpty()) {
                throw new AccountingException("Please enter your full name.");
            }
            if (!USERNAME.matcher(u).matches()) {
                throw new AccountingException("The username must be 3-30 characters: letters, numbers, dot or underscore.");
            }
            checkPassword(password);
            if (!Arrays.equals(password, confirm)) {
                throw new AccountingException("The two passwords do not match.");
            }
            return Database.inTransaction(c -> {
                if (dao.findByUsername(c, u) != null) {
                    throw new AccountingException("That username is already taken. Please choose another one.");
                }
                User.Role role = dao.count(c) == 0 ? User.Role.ADMIN : User.Role.ACCOUNTANT;
                byte[] salt = new byte[16];
                new SecureRandom().nextBytes(salt);
                int id = dao.insert(c, u, name, hash(password, salt), Base64.getEncoder().encodeToString(salt), role, Dates.now());
                audit.logAs(c, u, "SIGN UP", "User", u, "Account created with role " + role);
                return dao.findById(c, id).user();
            });
        } finally {
            wipe(password);
            wipe(confirm);
        }
    }

    /** Checks the password and starts the session. The error message never says which part was wrong. */
    public User login(String username, char[] password) {
        try {
            String u = username == null ? "" : username.trim();
            if (u.isEmpty() || password == null || password.length == 0) {
                throw new AccountingException("Please enter your username and password.");
            }
            User user = Database.inTransaction(c -> {
                UserDao.Row row = dao.findByUsername(c, u);
                boolean ok;
                if (row == null) {
                    verify(password, Base64.getEncoder().encodeToString(new byte[16]), "x"); // same time as a real check
                    ok = false;
                } else {
                    ok = verify(password, row.salt(), row.hash());
                }
                if (!ok) {
                    audit.logAs(c, u, "LOGIN FAILED", "User", u, "Wrong username or password");
                    return null;
                }
                if (!row.user().active()) {
                    audit.logAs(c, u, "LOGIN BLOCKED", "User", u, "Deactivated account tried to sign in");
                    return row.user();
                }
                audit.logAs(c, u, "LOGIN", "User", u, "Signed in");
                return row.user();
            });
            if (user == null) {
                throw new AccountingException("Incorrect username or password.");
            }
            if (!user.active()) {
                throw new AccountingException("This account has been deactivated. Please ask an administrator.");
            }
            Session.start(user.username(), user.fullName(), user.role().name());
            return user;
        } finally {
            wipe(password);
        }
    }

    public void logout() {
        if (Session.active()) {
            String who = Session.userName();
            Database.inTransaction(c -> {
                audit.logAs(c, who, "LOGOUT", "User", who, "Signed out");
                return null;
            });
        }
        Session.end();
    }

    // ------------------------------------------------------------------ administration

    public List<User> listUsers() {
        Session.require("view users", "ADMIN");
        return Database.read(dao::findAll);
    }

    public void setRole(int userId, User.Role role) {
        Session.require("change user roles", "ADMIN");
        Database.inTransaction(c -> {
            UserDao.Row r = dao.findById(c, userId);
            if (r == null) {
                throw new AccountingException("That user no longer exists.");
            }
            if (r.user().role() == User.Role.ADMIN && role != User.Role.ADMIN && r.user().active()
                    && dao.countActiveAdmins(c) <= 1) {
                throw new AccountingException("There must always be at least one active administrator.");
            }
            dao.updateRole(c, userId, role);
            audit.log(c, "CHANGE ROLE", "User", r.user().username(), r.user().role() + " -> " + role);
            return null;
        });
    }

    public void setActive(int userId, boolean active) {
        Session.require("activate or deactivate users", "ADMIN");
        Database.inTransaction(c -> {
            UserDao.Row r = dao.findById(c, userId);
            if (r == null) {
                throw new AccountingException("That user no longer exists.");
            }
            if (!active && r.user().role() == User.Role.ADMIN && r.user().active() && dao.countActiveAdmins(c) <= 1) {
                throw new AccountingException("There must always be at least one active administrator.");
            }
            dao.setActive(c, userId, active);
            audit.log(c, active ? "ACTIVATE USER" : "DEACTIVATE USER", "User", r.user().username(), "");
            return null;
        });
    }

    public void resetPassword(int userId, char[] newPassword) {
        Session.require("reset passwords", "ADMIN");
        try {
            checkPassword(newPassword);
            Database.inTransaction(c -> {
                UserDao.Row r = dao.findById(c, userId);
                if (r == null) {
                    throw new AccountingException("That user no longer exists.");
                }
                byte[] salt = new byte[16];
                new SecureRandom().nextBytes(salt);
                dao.updatePassword(c, userId, hash(newPassword, salt), Base64.getEncoder().encodeToString(salt));
                audit.log(c, "RESET PASSWORD", "User", r.user().username(), "");
                return null;
            });
        } finally {
            wipe(newPassword);
        }
    }
}
