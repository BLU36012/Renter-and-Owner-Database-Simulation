import java.io.Console;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

public class Capacity {

    private static final String DB_URL = "jdbc:oracle:thin:@//rocordb01.cse.lehigh.edu:1522/cse241pdb";
    private static final Scanner IN = new Scanner(System.in);
    private static Connection conn;

    public static void main(String[] args) {
        System.out.println("LU Uncommons rental management\n");
        try {
            conn = connect();
            if (conn == null) {
                return;
            }
            // Ensure the connection is closed when the program exits, even if Ctrl-C is pressed.
            Runtime.getRuntime().addShutdownHook(new Thread(Capacity::closeConnection));
            while (true) {
                int choice = menu("Choose an interface", "Exit",
                    "Property manager", "Tenant", "Company manager", "Financial manager");
                if (choice == 0) {
                    System.out.println("Goodbye.");
                    return;
                }
                if (choice == 1) propertyManagerMenu();
                if (choice == 2) tenantMenu();
                if (choice == 3) companyManagerMenu();
                if (choice == 4) financialManagerMenu();
            }
        } catch (EndOfInput e) {
            System.out.println("\nInput ended. Goodbye.");
        } catch (RuntimeException e) {
            System.out.println("\nThe program had to stop: " + e.getMessage());
        } finally {
            closeConnection();
        }
    }

    /** Prompts for the Oracle login (never stored in code); three tries. */
    private static Connection connect() {
        for (int attempt = 0; attempt < 3; attempt++) {
            String user = readText("Enter Oracle user id", 30);
            Console console = System.console();
            String password;
            if (console != null) {
                char[] pw = console.readPassword("Enter Oracle password for %s: ", user);
                if (pw == null) {
                    throw new EndOfInput();
                }
                password = new String(pw);
            } else {
                password = readLine("Enter Oracle password for " + user + ": ");
            }
            try {
                return DriverManager.getConnection(DB_URL, user, password);
            } catch (SQLException e) {
                if (String.valueOf(e.getMessage()).contains("No suitable driver")) {
                    System.out.println("ojdbc11.jar must be in the same folder as the program's jar.");
                    return null;
                }
                error(friendly(e));
            }
        }
        System.out.println("Too many failed attempts.");
        return null;
    }

    /** Always close the connection so no zombie sessions are left in Oracle. */
    private static synchronized void closeConnection() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException ignored) {
            // shutting down anyway
        }
    }
}