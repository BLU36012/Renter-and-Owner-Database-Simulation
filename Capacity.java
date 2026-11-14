import java.sql.*;
import java.util.Scanner;

public class Capacity {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        System.out.print("enter Oracle user id: ");
        String user = in.nextLine().trim();
        System.out.print("enter Oracle password for " + user + ": ");
        String pass = "";
        if (System.console() != null) {
            pass = new String(System.console().readPassword());
        }
        try (Connection conn = DriverManager.getConnection("jdbc:oracle:thin:@//rocordb01.cse.lehigh.edu:1522/cse241pdb", user, pass)) {
            while (true) {
                System.out.println("Input data on the section whose classroom capacity you wish to check.");
                System.out.print("Year (yyyy) or 0 to exit: ");
                String yearStr = in.nextLine().trim();
                if (yearStr.equals("0")) 
                {
                    break;
                }
                System.out.print("Semester (string): ");
                String sem = in.nextLine().trim().toLowerCase();
                if (!sem.equals("fall") && !sem.equals("winter") && !sem.equals("spring") && !sem.equals("summer")) {
                    System.out.println("Please input one of fall, winter, spring, summer.");
                    continue;
                }

                System.out.print("Input course ID as 3 digit integer: ");
                String cId = in.nextLine().trim();
                System.out.print("Input section ID as integer: ");
                String sIdStr = in.nextLine().trim();

                try {
                    int year = Integer.parseInt(yearStr);
                    Integer.parseInt(cId); 
                    int sId = Integer.parseInt(sIdStr);

                    String sql = "SELECT c.capacity, COUNT(t.id) as enrollment FROM section s " +
                                 "JOIN classroom c ON s.building = c.building AND s.room_number = c.room_number " +
                                 "JOIN takes t ON s.course_id = t.course_id AND s.sec_id = t.sec_id AND s.semester = t.semester AND s.year = t.year " +
                                 "WHERE s.course_id = ? AND s.year = ? AND LOWER(s.semester) = ? AND s.sec_id = ? GROUP BY c.capacity";

                    try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                        pstmt.setString(1, cId);
                        pstmt.setInt(2, year);
                        pstmt.setString(3, sem);
                        pstmt.setInt(4, sId);
                        try (ResultSet rs = pstmt.executeQuery()) {
                            if (rs.next()) {
                                int cap = rs.getInt("capacity");
                                int enr = rs.getInt("enrollment");
                                System.out.printf("Capacity is %d. Enrollment is %d.%n", cap, enr);
                                int difference = cap - enr;
                                if (difference > 0) {
                                    System.out.printf("There are %d seats available.%n", difference);
                                } else if (difference < 0) {
                                    System.out.printf("The section is over capacity by %d students.%n", -difference);
                                } else {
                                    System.out.println("The section is at full capacity.");
                                }
                            } else {
                                System.out.println("Section does not exist or was not offered.");
                            }
                        }
                    }
                } catch (NumberFormatException e) {
                    System.out.println("Please input an integer." + e.getMessage());
                }
            }
        } catch (SQLException e) {
           System.out.print(e.getMessage());
        }
    }
}