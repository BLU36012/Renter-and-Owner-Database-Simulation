import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Shared helpers for LUUncommons: running tasks safely, JDBC queries,
 * and keyboard input that asks again until the value is valid.
 */
public class Helpers {
    static final Scanner IN = new Scanner(System.in);
    static Connection conn;
    /** A piece of work that may throw SQLException, so run() can handle errors in one place. */
    interface Task {
        void run() throws SQLException;
    }

    /**
     * Runs a task. If anything goes wrong, prints the reason and returns to the menu.
     * Any transaction the task started but did not commit is rolled back, so no locks are left behind.
     */
    static void run(Task task) {
        try {
            task.run();
        } catch (SQLException e) {
            System.out.println("  ! " + e.getMessage().split("\\R")[0].replaceFirst("^ORA-\\d+: ", ""));
        } catch (RuntimeException e) {
            System.out.println("  ! Something went wrong: " + e);
        } finally {
            try {
                if (!conn.getAutoCommit()) {
                    conn.rollback();
                    conn.setAutoCommit(true);
                }
            } catch (SQLException ignored) {

            }
        }
    }

    // JDBC helpers 
    // Every value is bound to a ? placeholder, so user input never becomes part of the SQL (no SQL injection).
    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p == null) {
                ps.setNull(i + 1, Types.VARCHAR);
            } else if (p instanceof LocalDate) {
                ps.setDate(i + 1, Date.valueOf((LocalDate) p));
            } else {
                ps.setObject(i + 1, p);
            }
        }
    }

    /**
     * Runs a query and returns its rows as strings. If emptyMessage is null nothing is printed;
     * otherwise each row is printed as it is read, or emptyMessage if there are none.
     */
    static List<String[]> query(String emptyMessage, String sql, Object... params) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int n = meta.getColumnCount();
                String format = "";
                String[] header = new String[n];
                String[] dashes = new String[n];
                for (int c = 0; c < n; c++) {
                    header[c] = meta.getColumnLabel(c + 1);
                    int width = Math.max(header[c].length(), Math.min(meta.getColumnDisplaySize(c + 1), 30));
                    format += "  %-" + width + "s";
                    dashes[c] = "-".repeat(width);
                }
                format += "%n";
                while (rs.next()) {
                    String[] row = new String[n];
                    for (int c = 0; c < n; c++) {
                        int type = meta.getColumnType(c + 1);
                        String value;
                        if (type == Types.DATE || type == Types.TIMESTAMP) {
                            // Show dates as YYYY-MM-DD, without a time.
                            Date date = rs.getDate(c + 1);
                            value = date == null ? null : date.toString();
                        } else {
                            value = rs.getString(c + 1);
                            // Show money with two decimals: 1234.5 becomes 1234.50.
                            if (value != null && value.matches("-?\\d*\\.\\d")) {
                                value += "0";
                            }
                        }
                        row[c] = value == null ? "" : value;
                    }
                    if (emptyMessage != null) {
                        if (rows.isEmpty()) {
                            System.out.printf(format, (Object[]) header);
                            System.out.printf(format, (Object[]) dashes);
                        }
                        System.out.printf(format, (Object[]) row);
                    }
                    rows.add(row);
                }
            } 
        }
        if (emptyMessage != null && rows.isEmpty()) {
            System.out.println(emptyMessage);
        }
        return rows;
    }

    /** Runs an INSERT, UPDATE or DELETE and returns how many rows it changed. */
    static int exec(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /** Runs an INSERT and returns the id Oracle generated for idColumn of the new row. */
    static int insert(String sql, String idColumn, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql, new String[] {idColumn})) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    /** Lets the user choose one of the printed rows by its first column. Returns null if cancelled or if there are no rows. */
    static String[] pickRow(List<String[]> rows, String label) {
        if (rows.isEmpty()) {
            return null;
        }
        while (true) {
            String s = ask(label + " (blank to cancel)");
            if (s.isEmpty()) {
                return null;
            }
            for (String[] row : rows) {
                if (row[0].equalsIgnoreCase(s)) {
                    return row;
                }
            }
            System.out.println("  ! That is not one of the choices listed.");
        }
    }

    /** Finds a person by ID, name or email, or (if allowNew) enters a new prospect. Returns their ID, or null if cancelled. */
    static Integer getPerson(String who, boolean allowNew) throws SQLException {
        if (allowNew) {
            int choice = menu("Is " + who + " already in the system?", "Cancel", "Yes, find them", "No, enter a new person");
            if (choice == 0) {
                return null;
            }
            if (choice == 2) {
                String name = askMatch("Full name", ".{1,100}", "Required, at most 100 characters.");
                String ssn = askMatch("SSN (123-45-6789)", "\\d{3}-\\d{2}-\\d{4}", "Use the form 123-45-6789.");
                String phone = askMatch("Phone (610-555-0123)", "\\d{3}-\\d{3}-\\d{4}", "Use the form 610-555-0123.");
                String email = askMatch("Email", EMAIL, "That is not an email address.").toLowerCase();
                BigDecimal salary = askMoney("Annual income", 0, 99999999.99);
                int age = askInt("Age", 18, 120);
                int id = insert("INSERT INTO Tenants (name, ssn, phone, email, salary, age) VALUES (?, ?, ?, ?, ?, ?)",
                    "tenant_id", name, ssn, phone, email, salary, age);
                System.out.println("New prospective tenant ID: " + id);
                return id;
            }
        }
        while (true) {
            String s = ask("Search for " + who + " by ID, name or email (blank to cancel)");
            if (s.isEmpty()) {
                return null;
            }
            String select = "SELECT tenant_id, name, email, status FROM Tenants ";
            List<String[]> people;
            if (s.matches("\\d{1,9}")) {
                // A number is searched as a tenant ID.
                people = query("  ! No one has that ID.", select + "WHERE tenant_id = ?", Integer.parseInt(s));
            } else {
                // Anything else is searched as part of a name or email.
                String like = "%" + s.toLowerCase() + "%";
                people = query("  ! No one matches.",
                    select + "WHERE LOWER(name) LIKE ? OR LOWER(email) LIKE ? ORDER BY name", like, like);
            }
            String[] person = pickRow(people, "ID of the person");
            if (person != null) {
                return Integer.parseInt(person[0]);
            }
        }
    }

    static final String EMAIL = "[\\w.%+-]+@[\\w-]+(\\.[\\w-]+)+";

    /** Prints "label: " and reads a line. Ends the program cleanly if input runs out. */
    static String ask(String label) {
        System.out.print(label + ": ");
        if (!IN.hasNextLine()) {
            System.out.println("\nInput ended. Goodbye.");
            System.exit(0);
        }
        return IN.nextLine().trim();
    }

    /** Reads a line that matches regex, showing hint after a bad entry. */
    static String askMatch(String label, String regex, String hint) {
        String s = ask(label);
        while (!s.matches(regex)) {
            System.out.println("  ! " + hint);
            s = ask(label);
        }
        return s;
    }

    static int askInt(String label, int min, int max) {
        while (true) {
            String s = ask(label);
            if (s.matches("\\d{1,9}") && Integer.parseInt(s) >= min && Integer.parseInt(s) <= max) {
                return Integer.parseInt(s);
            }
            System.out.println("  ! Enter a whole number from " + min + " to " + max + ".");
        }
    }

    static BigDecimal askMoney(String label, double min, double max) {
        while (true) {
            String s = ask(label).replace("$", "").replace(",", "");
            if (s.matches("\\d{1,10}(\\.\\d{1,2})?")) {
                BigDecimal amount = new BigDecimal(s);
                if (amount.doubleValue() >= min && amount.doubleValue() <= max) {
                    return amount;
                }
            }
            System.out.printf("  ! Enter an amount from %.2f to %.2f.%n", min, max);
        }
    }

    static LocalDate askDate(String label, LocalDate min, LocalDate max) {
        while (true) {
            try {
                LocalDate date = LocalDate.parse(ask(label + " (YYYY-MM-DD)"));
                if (!date.isBefore(min) && !date.isAfter(max)) {
                    return date;
                }
                System.out.println("  ! The date must be from " + min + " to " + max + ".");
            } catch (DateTimeParseException e) {
                System.out.println("  ! Enter a date like 2026-12-01.");
            }
        }
    }

    /** Prints a numbered menu and returns the choice. Option 0 is zeroLabel (Back, Cancel, Exit). */
    static int menu(String title, String zeroLabel, String... options) {
        System.out.println("\n=== " + title + " ===");
        for (int i = 0; i < options.length; i++) {
            System.out.println("  " + (i + 1) + ". " + options[i]);
        }
        System.out.println("  0. " + zeroLabel);
        return askInt("Choose", 0, options.length);
    }
}
