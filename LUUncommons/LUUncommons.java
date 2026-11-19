import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LUUncommons extends Helpers {
    static final String DB_URL = "jdbc:oracle:thin:@//rocordb01.cse.lehigh.edu:1522/cse241pdb";
    static int propertyId;
    static int tenantId;

    public static void main(String[] args) {
        System.out.println("LU Uncommons rental management\n");
        // Ask for the Oracle login until it works. The password is typed in, never stored in the code.
        while (true) {
            String user = ask("Oracle user id (blank to quit)");
            if (user.isEmpty()) {
                return;
            }
            String pass = System.console() != null
                ? new String(System.console().readPassword("Oracle password: "))
                : ask("Oracle password");

            try (Connection conn = DriverManager.getConnection(DB_URL, user, pass)) {
                Helpers.conn = conn;
                // Also close it if the program is stopped early (Ctrl-C or end of input).
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        conn.close();
                    } catch (SQLException ignored) {
                        // exiting anyway
                    }
                }));
                while (true) {
                    int choice = menu("Choose an interface", "Exit", "Property manager", "Tenant", "Company manager", "Financial manager");
                    if (choice == 0) {
                        System.out.println("Goodbye.");
                        return;
                    }
                    if (choice == 1) propertyManagerMenu();
                    if (choice == 2) tenantMenu();
                    if (choice == 3) companyManagerMenu();
                    if (choice == 4) financialManagerMenu();
                }
            } catch (SQLException e) {
                System.out.println("  ! Could not connect: " + e.getMessage());
            }
        }
    }

    // Property manager
    static void propertyManagerMenu() {
        propertyId = 0;
        run(() -> {
            String[] property = pickRow(query("There are no properties.",
                "SELECT property_id, street, city, state FROM Properties ORDER BY property_id"),
                "Property you manage");
            if (property != null) {
                propertyId = Integer.parseInt(property[0]);
            }
        });
        while (propertyId != 0) {
            int choice = menu("Property manager - property " + propertyId, "Back",
                "Find apartments", "Record a visit", "Sign a lease", "Add a person to a lease",
                "Add a pet to a lease", "Set a move-out date", "Complete a move-out (refund deposit)",
                "View all tenants", "View all properties");
            if (choice == 0) return;
            if (choice == 1) run(LUUncommons::searchApartments);
            if (choice == 2) run(LUUncommons::recordVisit);
            if (choice == 3) run(LUUncommons::signLease);
            if (choice == 4) run(LUUncommons::addPersonToLease);
            if (choice == 5) run(LUUncommons::addPetToLease);
            if (choice == 6) run(LUUncommons::setMoveOut);
            if (choice == 7) run(LUUncommons::completeMoveOut);
            if (choice == 8) run(() -> query("There are no tenants.",
                "SELECT tenant_id, name, status, phone, email FROM Tenants ORDER BY tenant_id"));
            if (choice == 9) run(() -> query("There are no properties.", PROPERTY_LIST));
        }
    }

    static void searchApartments() throws SQLException {
        String place = ask("City, state or ZIP (blank for any)").toLowerCase();
        BigDecimal maxRent = askMoney("Maximum rent (0 for any)", 0, 99999.99);
        int beds = askInt("Minimum bedrooms (0 for any)", 0, 10);
        String sql = "SELECT p.property_id, p.city, p.state, a.apt_number, "
            + "a.bedroom_amount, a.bathroom_amount, a.rent, c.lease_id "
            + "FROM Apartments a "
            + "JOIN Properties p ON p.property_id = a.property_id "
            + "LEFT JOIN Current_Leases c ON c.property_id = a.property_id AND c.apt_number = a.apt_number "
            + "WHERE a.bedroom_amount >= ?";
        List<Object> params = new ArrayList<>();
        params.add(beds);
        if (!place.isEmpty()) {
            sql += " AND (LOWER(p.city) LIKE ? OR LOWER(p.state) = ? OR p.zip = ?)";
            Collections.addAll(params, "%" + place + "%", place, place);
        }
        if (maxRent.signum() > 0) {
            sql += " AND a.rent <= ?";
            params.add(maxRent);
        }
        query("No apartments match.", sql + " ORDER BY a.rent", params.toArray());
    }

    static void recordVisit() throws SQLException {
        Integer person = getPerson("the visitor", true);
        if (person == null) {
            return;
        }
        String[] apt = pickRow(query("This property has no apartments.",
            "SELECT apt_number, bedroom_amount, rent "
            + "FROM Apartments WHERE property_id = ? ORDER BY apt_number", propertyId), "Apartment visited");
        if (apt != null) {
            exec("INSERT INTO Visits (tenant_id, property_id, apt_number) VALUES (?, ?, ?)", person, propertyId, apt[0]);
            System.out.println("Visit recorded.");
        }
    }

    static void signLease() throws SQLException {
        Integer person = getPerson("the person signing", false);
        if (person == null) {
            return;
        }
        String[] apt = pickRow(query("This person has not visited an apartment here; record a visit first.",
            "SELECT DISTINCT a.apt_number, a.bedroom_amount, a.rent "
            + "FROM Visits v JOIN Apartments a ON a.property_id = v.property_id AND a.apt_number = v.apt_number "
            + "WHERE v.tenant_id = ? AND v.property_id = ? ORDER BY a.apt_number", person, propertyId), "Apartment to lease");
        if (apt == null) {
            return;
        }
        LocalDate today = LocalDate.now();
        LocalDate start = askDate("Start date", today.minusMonths(1), today.plusYears(1));
        while (start.getDayOfMonth() > 28) {
            System.out.println("  ! Leases start on day 1-28 so rent has a due date every month.");
            start = askDate("Start date", today.minusMonths(1), today.plusYears(1));
        }
        int months = askInt("Length in months (1-36)", 1, 36);
        BigDecimal rate = askMoney("Monthly rent (listed at $" + apt[2] + ")", 1, 99999.99);
        String confirm = askMatch("Sign a " + months + "-month lease for apartment " + apt[0] + " at $" + rate
            + " starting " + start + "? (y/n)", "(?i)y|yes|n|no", "Please answer y or n.");
        if (!confirm.toLowerCase().startsWith("y")) {
            return;
        }

        // Two leases overlap if one starts before the other ends. A tenant who already moved out does not count.
        LocalDate end = start.plusMonths(months).minusDays(1);
        List<String[]> overlapping = query(null, "SELECT lease_id FROM Lease_Details WHERE property_id = ? AND apt_number = ? "
            + "AND start_date <= ? AND end_date >= ? AND (leave_date IS NULL OR leave_date >= ?)",
            propertyId, apt[0], end, start, start);
        if (!overlapping.isEmpty()) {
            throw new SQLException("That apartment is already leased for part of that period.");
        }
        // The lease and the status change are saved together, or not at all.
        conn.setAutoCommit(false);
        // The trigger trg_lease_needs_visit rejects this insert if the person never visited the apartment.
        int leaseId = insert("INSERT INTO Leases (tenant_id, property_id, apt_number, start_date, number_months, rate) "
            + "VALUES (?, ?, ?, ?, ?, ?)", "lease_id", person, propertyId, apt[0], start, months, rate);
        exec("UPDATE Tenants SET status = 'Active' WHERE tenant_id = ?", person);
        conn.commit();
        conn.setAutoCommit(true);
        System.out.println("Lease #" + leaseId + " signed. First rent is due " + start + ".");
    }

    /** Current and upcoming leases at this property. Columns: lease, apt, tenant, status, ends, starts. */
    static final String OPEN_LEASES = "SELECT ld.lease_id, ld.apt_number, t.name, "
        + "ld.lease_status, ld.end_date, ld.start_date "
        + "FROM Lease_Details ld JOIN Tenants t ON t.tenant_id = ld.tenant_id "
        + "WHERE ld.property_id = ? AND ld.lease_status IN ('Current', 'Upcoming') ORDER BY ld.apt_number";

    static void addPersonToLease() throws SQLException {
        String[] lease = pickRow(query("No current leases here.", OPEN_LEASES, propertyId), "Lease number");
        if (lease == null) {
            return;
        }
        Integer person = getPerson("the person to add", true);
        if (person == null) {
            return;
        }
        if (!query(null, "SELECT 1 FROM Leases WHERE lease_id = ? AND tenant_id = ?", lease[0], person).isEmpty()) {
            System.out.println("  ! That person is already the main tenant on this lease.");
            return;
        }
        String[] relationships = {"Spouse", "Partner", "Roommate", "Family member"};
        int r = menu("Relationship to the main tenant", "Cancel", relationships);
        if (r == 0) {
            return;
        }
        conn.setAutoCommit(false);
        exec("INSERT INTO Lease_Occupants (lease_id, tenant_id, relationship) VALUES (?, ?, ?)", lease[0], person, relationships[r - 1]);
        exec("UPDATE Tenants SET status = 'Active' WHERE tenant_id = ?", person);
        conn.commit();
        conn.setAutoCommit(true);
        System.out.println("Person " + person + " added to lease #" + lease[0] + ".");
    }

    static void addPetToLease() throws SQLException {
        String[] lease = pickRow(query("No current leases here.", OPEN_LEASES, propertyId), "Lease number");
        if (lease == null) {
            return;
        }
        String name = askMatch("Pet's name", ".{1,50}", "Required, at most 50 characters.");
        String[] kinds = {"Dog", "Cat", "Rabbit", "Bird", "Fish", "Reptile", "Other"};
        int k = menu("Kind of pet", "Cancel", kinds);
        if (k == 0) {
            return;
        }
        BigDecimal fee = askMoney("Monthly pet fee", 0, 500);
        LocalDate added = LocalDate.now();
        LocalDate leaseStart = LocalDate.parse(lease[5]);
        if (leaseStart.isAfter(added)) {
            added = leaseStart;
        }
        exec("INSERT INTO Pets (lease_id, pet_name, species, monthly_fee, added_date) VALUES (?, ?, ?, ?, ?)",
            lease[0], name, kinds[k - 1], fee, added);
        System.out.println(name + " added to lease #" + lease[0] + ".");
    }

    static void setMoveOut() throws SQLException {
        String[] lease = pickRow(query("No current leases here.", OPEN_LEASES, propertyId), "Lease number");
        if (lease == null) {
            return;
        }
        LocalDate leave = askDate("Move-out date", LocalDate.parse(lease[5]), LocalDate.parse(lease[4]));
        // Change the date if a move-out was already set. If nothing was updated, there was none yet, so add one.
        int updated = exec("UPDATE Move_Outs SET leave_date = ? WHERE lease_id = ?", leave, lease[0]);
        if (updated == 0) {
            exec("INSERT INTO Move_Outs (lease_id, leave_date) VALUES (?, ?)", lease[0], leave);
        }
        System.out.println("Move-out set for " + leave + ".");
    }

    static void completeMoveOut() throws SQLException {
        // Leases that have ended (past their end date or their move-out date) whose deposit is not refunded yet.
        String[] lease = pickRow(query("No leases here are waiting for a move-out.",
            "SELECT ld.lease_id, ld.apt_number, t.name, "
            + "NVL(ld.leave_date, ld.end_date) AS moved_out, a.security_deposit "
            + "FROM Lease_Details ld "
            + "JOIN Tenants t ON t.tenant_id = ld.tenant_id "
            + "JOIN Apartments a ON a.property_id = ld.property_id AND a.apt_number = ld.apt_number "
            + "WHERE ld.property_id = ? AND ld.lease_status = 'Ended' ORDER BY moved_out", propertyId),
            "Lease number");
        if (lease == null) {
            return;
        }
        LocalDate leave = LocalDate.parse(lease[3]);
        BigDecimal refund = askMoney("Deposit refund (deposit was $" + lease[4] + ")", 0, Double.parseDouble(lease[4]));

        conn.setAutoCommit(false);
        // Update the move-out if one was set already, otherwise add it.
        int updated = exec("UPDATE Move_Outs SET refund_amount = ? WHERE lease_id = ?", refund, lease[0]);
        if (updated == 0) {
            exec("INSERT INTO Move_Outs (lease_id, leave_date, refund_amount) VALUES (?, ?, ?)", lease[0], leave, refund);
        }
        // Everyone on the lease becomes a former tenant, unless they are the main tenant
        // of another current or upcoming lease (for example, a renewal).
        exec("UPDATE Tenants SET status = 'Former' "
            + "WHERE tenant_id IN (SELECT tenant_id FROM Leases WHERE lease_id = ? "
            + "  UNION SELECT tenant_id FROM Lease_Occupants WHERE lease_id = ?) "
            + "AND tenant_id NOT IN (SELECT tenant_id FROM Lease_Details WHERE lease_status IN ('Current', 'Upcoming'))",
            lease[0], lease[0]);
        conn.commit();
        conn.setAutoCommit(true);
        System.out.println("Move-out complete. Refund: $" + refund + ".");
    }

    //Tenant
    static final String MY_LEASES =
        "(ld.tenant_id = ? OR ld.lease_id IN (SELECT lease_id FROM Lease_Occupants WHERE tenant_id = ?))";

    static void tenantMenu() {
        tenantId = 0;
        run(() -> {
            while (tenantId == 0) {
                int id = askInt("Your tenant ID (0 to go back)", 0, 999999999);
                if (id == 0) {
                    return;
                }
                List<String[]> found = query(null, "SELECT name FROM Tenants WHERE tenant_id = ?", id);
                if (found.isEmpty()) {
                    System.out.println("  ! There is no tenant with that ID.");
                } else {
                    tenantId = id;
                    System.out.println("Welcome, " + found.get(0)[0] + ".");
                }
            }
        });
        while (tenantId != 0) {
            int choice = menu("Tenant " + tenantId, "Back", "Check amount due", "Make a rent payment", "Update my contact information");
            if (choice == 0) return;
            if (choice == 1) run(LUUncommons::showAmountDue);
            if (choice == 2) run(LUUncommons::makePayment);
            if (choice == 3) run(LUUncommons::updateContact);
        }
    }

    static void showAmountDue() throws SQLException {
        query("You are not on any lease.",
            "SELECT ld.lease_id, p.street, ld.apt_number, ld.lease_status, "
            + "b.monthly_charge, b.total_paid, b.balance_due, b.next_due_date "
            + "FROM Lease_Details ld JOIN Lease_Balances b ON b.lease_id = ld.lease_id "
            + "JOIN Properties p ON p.property_id = ld.property_id "
            + "WHERE " + MY_LEASES + " ORDER BY ld.start_date DESC", tenantId, tenantId);
    }

    static void makePayment() throws SQLException {
        String[] lease = pickRow(query("Nothing is owed on your leases.",
            "SELECT ld.lease_id, ld.apt_number, b.balance_due, "
            + "b.total_owed - b.total_paid AS left_on_lease FROM Lease_Details ld JOIN Lease_Balances b ON b.lease_id = ld.lease_id "
            + "WHERE " + MY_LEASES + " AND b.total_owed > b.total_paid", tenantId, tenantId), "Lease to pay toward");
        if (lease == null) {
            return;
        }
        BigDecimal amount = askMoney("Payment amount", 0.01, Double.parseDouble(lease[3]));
        int method = menu("Payment method", "Cancel", "Credit card (3% fee)", "Debit card", "Bank transfer (ACH)", "Cryptocurrency", "Check");
        if (method == 0) {
            return;
        }
        String code;
        String detailSql;
        List<Object> details = new ArrayList<>();
        if (method == 1 || method == 2) {
            String[] brands = {"Visa", "Mastercard", "Amex", "Discover"};
            int b = menu("Card brand", "Cancel", brands);
            if (b == 0) {
                return;
            }
            String card = askMatch("Card number (digits only)", "\\d{13,19}", "A card number has 13 to 19 digits.");
            int expMonth = askInt("Expiration month (1-12)", 1, 12);
            int expYear = askInt("Expiration year", Year.now().getValue(), Year.now().getValue() + 20);
            BigDecimal fee = BigDecimal.ZERO;
            if (method == 1) {
                fee = amount.multiply(new BigDecimal("0.03")).setScale(2, RoundingMode.HALF_UP);
                System.out.println("A credit card processing fee of $" + fee + " will also be charged.");
            }
            code = "CARD";
            detailSql = "INSERT INTO Card_Payments (payment_id, card_type, card_brand, last_four, exp_month, exp_year, processing_fee) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
            // Only the last four digits are stored, never the full card number.
            Collections.addAll(details, method == 1 ? "CREDIT" : "DEBIT", brands[b - 1], card.substring(card.length() - 4),
                expMonth, expYear, fee);
        } else if (method == 3) {
            String bank = askMatch("Bank name", ".{1,100}", "Required, at most 100 characters.");
            String routing = askMatch("Routing number", "\\d{9}", "A routing number has 9 digits.");
            String account = askMatch("Account number", "\\d{4,17}", "An account number has 4 to 17 digits.");
            String type = askMatch("Is this a checking account? (y/n)", "(?i)y|yes|n|no", "Please answer y or n.").toLowerCase().startsWith("y") ? "CHECKING" : "SAVINGS";
            code = "ACH";
            detailSql = "INSERT INTO ACH_Payments (payment_id, bank_name, routing_number, account_last_four, account_type) "
                + "VALUES (?, ?, ?, ?, ?)";
            Collections.addAll(details, bank, routing, account.substring(account.length() - 4), type);
        } else if (method == 4) {
            String[] currency = pickRow(query("Crypto payments are unavailable.",
                "SELECT currency, usd_per_coin FROM Crypto_Rates ORDER BY 1"), "Currency");
            if (currency == null) {
                return;
            }
            BigDecimal rate = new BigDecimal(currency[1]);
            BigDecimal coins = amount.divide(rate, 8, RoundingMode.HALF_UP);
            System.out.println("Send " + coins.toPlainString() + " " + currency[0] + ".");
            String wallet = askMatch("Your wallet address", ".{1,100}", "Required, at most 100 characters.");
            String hash = askMatch("Transaction hash", ".{1,100}", "Required, at most 100 characters.");
            code = "CRYPTO";
            detailSql = "INSERT INTO Crypto_Payments (payment_id, currency, wallet_address, transaction_hash, crypto_amount, exchange_rate) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
            Collections.addAll(details, currency[0], wallet, hash, coins, rate);
        } else {
            int checkNumber = askInt("Check number", 1, 999999999);
            String bank = askMatch("Bank name", ".{1,100}", "Required, at most 100 characters.");
            code = "CHECK";
            detailSql = "INSERT INTO Check_Payments (payment_id, check_number, bank_name) VALUES (?, ?, ?)";
            Collections.addAll(details, checkNumber, bank);
        }

        // Check again what is left on the lease right before saving the payment.
        BigDecimal left = new BigDecimal(query(null, "SELECT total_owed - total_paid FROM Lease_Balances WHERE lease_id = ?", lease[0]).get(0)[0]);
        if (amount.compareTo(left) > 0) {
            throw new SQLException("That is more than the $" + left + " left on this lease.");
        }
        // The payment and its details row are saved together, or not at all.
        conn.setAutoCommit(false);
        int paymentId = insert("INSERT INTO Payments (lease_id, paid_by, amount, payment_method) VALUES (?, ?, ?, ?)",
            "payment_id", lease[0], tenantId, amount, code);
        details.add(0, paymentId);
        exec(detailSql, details.toArray());
        conn.commit();
        conn.setAutoCommit(true);

        String due = query(null, "SELECT balance_due FROM Lease_Balances WHERE lease_id = ?", lease[0]).get(0)[0];
        System.out.println("Payment #" + paymentId + " recorded. Amount now due on lease #" + lease[0] + ": $" + due);
    }

    static void updateContact() throws SQLException {
        String phone = askMatch("New phone number as 610-555-0123 (blank to keep)", "(\\d{3}-\\d{3}-\\d{4})?", "Use the form 610-555-0123.");
        String email = askMatch("New email address (blank to keep)", "(" + EMAIL + ")?", "That is not an email address.").toLowerCase();
        if (!phone.isEmpty()) {
            exec("UPDATE Tenants SET phone = ? WHERE tenant_id = ?", phone, tenantId);
        }
        if (!email.isEmpty()) {
            exec("UPDATE Tenants SET email = ? WHERE tenant_id = ?", email, tenantId);
        }
        System.out.println(phone.isEmpty() && email.isEmpty() ? "Nothing changed." : "Contact information updated.");
    }

    /** Every property and how many apartments it has. LEFT JOIN keeps a new property that has no apartments yet. */
    static final String PROPERTY_LIST = "SELECT p.property_id, p.street, p.city, p.state, "
        + "COUNT(a.apt_number) AS units "
        + "FROM Properties p LEFT JOIN Apartments a ON a.property_id = p.property_id "
        + "GROUP BY p.property_id, p.street, p.city, p.state ORDER BY p.property_id";

    static void companyManagerMenu() {
        while (true) {
            int choice = menu("Company manager", "Back", "View properties", "Add a new property", "Add apartments to a property");
            if (choice == 0) return;
            if (choice == 1) run(() -> query("There are no properties.", PROPERTY_LIST));
            if (choice == 2) run(LUUncommons::addProperty);
            if (choice == 3) run(() -> {
                String[] property = pickRow(query("There are no properties.", PROPERTY_LIST), "Property ID");
                if (property != null) {
                    addApartments(Integer.parseInt(property[0]));
                }
            });
        }
    }

    static void addProperty() throws SQLException {
        String street = askMatch("Street address", ".{1,120}", "Required, at most 120 characters.");
        String city = askMatch("City", ".{1,60}", "Required, at most 60 characters.");
        String state = askMatch("State (2 letters)", "[A-Za-z]{2}", "Use two letters, e.g. PA.").toUpperCase();
        String zip = askMatch("ZIP code", "\\d{5}", "A ZIP code has 5 digits.");
        BigDecimal cost = askMoney("Purchase cost", 0, 9999999999.99);
        List<Object[]> amenities = new ArrayList<>();
        String name = ask("Common amenity name (blank when done)");
        while (!name.isEmpty()) {
            boolean included = askMatch("Included in rent? (y/n)", "(?i)y|yes|n|no", "Please answer y or n.").toLowerCase().startsWith("y");
            BigDecimal fee = included ? BigDecimal.ZERO : askMoney("Monthly fee", 0.01, 9999.99);
            amenities.add(new Object[] {name, included ? "Y" : "N", fee});
            name = ask("Common amenity name (blank when done)");
        }

        // The property and its amenities are saved together, or not at all.
        conn.setAutoCommit(false);
        int id = insert("INSERT INTO Properties (street, city, state, zip, property_cost) VALUES (?, ?, ?, ?, ?)",
            "property_id", street, city, state, zip, cost);
        for (Object[] a : amenities) {
            exec("INSERT INTO Amenities (property_id, amenity_name, category, is_included, monthly_fee) "
                + "VALUES (?, ?, 'General', ?, ?)", id, a[0], a[1], a[2]);
        }
        conn.commit();
        conn.setAutoCommit(true);
        System.out.println("Property #" + id + " created. Now add its apartments.");
        addApartments(id);
    }

    /** Adds groups of identical apartments, numbered after the highest existing number (101, 102, ...). */
    static void addApartments(int property) throws SQLException {
        while (askMatch("Add a group of identical apartments? (y/n)", "(?i)y|yes|n|no", "Please answer y or n.").toLowerCase().startsWith("y")) {
            int count = askInt("How many (1-100)", 1, 100);
            int beds = askInt("Bedrooms (0 = studio)", 0, 10);
            BigDecimal baths = new BigDecimal(askMatch("Bathrooms, e.g. 1 or 1.5", "([1-9]|10)(\\.[05])?",
                "Use whole or half bathrooms from 1 to 10."));
            int sqft = askInt("Square feet", 150, 20000);
            BigDecimal rent = askMoney("Monthly rent", 1, 99999.99);
            BigDecimal deposit = askMoney("Security deposit", 0, 99999.99);
            String[] features = ask("Private amenities, comma separated (blank for none)").split(",");

            // Find the highest apartment number so far (100 if there are none), and start after it.
            int highest = 100;
            for (String[] row : query(null, "SELECT apt_number FROM Apartments WHERE property_id = ?", property)) {
                if (row[0].matches("\\d{1,9}")) {
                    highest = Math.max(highest, Integer.parseInt(row[0]));
                }
            }
            int next = highest + 1;

            // The whole group of apartments is saved together, or not at all.
            conn.setAutoCommit(false);
            for (int i = 0; i < count; i++) {
                String apt = String.valueOf(next + i);
                exec("INSERT INTO Apartments (property_id, apt_number, rent, security_deposit, square_feet, bedroom_amount, bathroom_amount) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)", property, apt, rent, deposit, sqft, beds, baths);
                for (String f : features) {
                    if (!f.isBlank()) {
                        exec("INSERT INTO Apartment_Amenities (property_id, apt_number, amenity_name) VALUES (?, ?, ?)", property, apt, f.trim());
                    }
                }
            }
            conn.commit();
            conn.setAutoCommit(true);
            System.out.println("Created apartments " + next + " to " + (next + count - 1) + ".");
        }
    }
    static void financialManagerMenu() {
        while (true) {
            int choice = menu("Financial reports", "Back",
                "Annual revenue", "Occupancy", "Outstanding balances", "Payments by method");
            if (choice == 0) return;
            run(() -> report(choice));
        }
    }

    /** Summary reports for the whole enterprise. Each one prints a table grouped by year/property/method, then the totals. */
    static void report(int which) throws SQLException {
        if (which == 1) {
            String from = "FROM Payments pay JOIN Leases l ON l.lease_id = pay.lease_id ";
            String year = "EXTRACT(YEAR FROM pay.payment_date)";
            System.out.println("\nRevenue by year and property:");
            query("Nothing to report.", "SELECT " + year + " AS pay_year, l.property_id, COUNT(*) AS payments, "
                + "SUM(pay.amount) AS revenue " + from + " GROUP BY " + year + ", l.property_id ORDER BY 1, 2");
            System.out.println("\nSubtotal for each year:");
            query("Nothing to report.", "SELECT " + year + " AS pay_year, COUNT(*) AS payments, SUM(pay.amount) AS revenue "
                + from + " GROUP BY " + year + " ORDER BY 1");
            System.out.println("\nTotal:");
            query("Nothing to report.", "SELECT COUNT(*) AS payments, SUM(pay.amount) AS revenue " + from);

        } else if (which == 2) {
            // LEFT JOIN keeps apartments with no current lease. COUNT(c.lease_id) counts only the leased ones.
            String from = "FROM Apartments a LEFT JOIN Current_Leases c "
                + "ON c.property_id = a.property_id AND c.apt_number = a.apt_number ";
            String columns = "COUNT(*) AS units, COUNT(c.lease_id) AS leased, "
                + "ROUND(100 * COUNT(c.lease_id) / COUNT(*), 1) AS percent_occupied, SUM(c.rate) AS monthly_rent ";
            System.out.println("\nOccupancy by property:");
            query("Nothing to report.", "SELECT a.property_id, " + columns + from
                + " GROUP BY a.property_id ORDER BY a.property_id");
            System.out.println("\nTotal:");
            query("Nothing to report.", "SELECT " + columns + from);

        } else if (which == 3) {
            query("Nothing to report.", "SELECT ld.lease_id, ld.property_id, ld.apt_number, "
                + "t.name, ld.lease_status, b.balance_due "
                + "FROM Lease_Details ld "
                + "JOIN Tenants t ON t.tenant_id = ld.tenant_id "
                + "JOIN Lease_Balances b ON b.lease_id = ld.lease_id "
                + "WHERE b.balance_due > 0 ORDER BY b.balance_due DESC");

        } else {
            String from = "FROM Payments pay ";
            System.out.println("\nPayments by method:");
            query("Nothing to report.", "SELECT pay.payment_method, COUNT(*) AS payments, SUM(pay.amount) AS total "
                + from + " GROUP BY pay.payment_method ORDER BY pay.payment_method");
            System.out.println("\nTotal:");
            query("Nothing to report.", "SELECT COUNT(*) AS payments, SUM(pay.amount) AS total " + from);
        }
    }
}
