/******************************************************************************
 * Minimal Database Test - No iDempiere dependencies
 * Tests raw JDBC connection to verify database is accessible
 *****************************************************************************/
package org.idempiere.ninja.test;

import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

/**
 * Simple JDBC test - no iDempiere framework needed
 * Run this first to verify database connectivity
 */
public class SimpleDBTest {

    public static void main(String[] args) {
        System.out.println("=== SIMPLE DATABASE TEST ===");
        System.out.println("Testing raw JDBC connection (no iDempiere)");
        System.out.println();

        // Try to load connection info from idempiere.properties
        String[] propPaths = {
            "/home/red1/idempiere-dev-setup/idempiere/idempiere.properties",
            "./idempiere.properties",
            System.getProperty("PropertyFile", "")
        };

        Properties props = new Properties();
        String propsFile = null;

        for (String path : propPaths) {
            try {
                if (path != null && !path.isEmpty()) {
                    props.load(new FileInputStream(path));
                    propsFile = path;
                    break;
                }
            } catch (Exception e) {
                // Try next
            }
        }

        // Default connection parameters
        String host = "localhost";
        String port = "5432";
        String db = "idempiere";
        String user = "adempiere";
        String pass = "adempiere";

        // Try to extract from properties
        if (propsFile != null) {
            System.out.println("Found properties: " + propsFile);
            String dbUrl = props.getProperty("Connection");
            if (dbUrl != null) {
                // Parse: jdbc:postgresql://localhost:5432/idempiere
                try {
                    if (dbUrl.contains("postgresql")) {
                        String[] parts = dbUrl.split("//")[1].split("/");
                        String[] hostPort = parts[0].split(":");
                        host = hostPort[0];
                        port = hostPort.length > 1 ? hostPort[1] : "5432";
                        db = parts[1];
                    }
                } catch (Exception e) {
                    System.out.println("Could not parse URL, using defaults");
                }
            }
            user = props.getProperty("db_Uid", user);
            pass = props.getProperty("db_Pwd", pass);
        } else {
            System.out.println("No properties file found, using defaults");
        }

        String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
        System.out.println("Connecting to: " + url);
        System.out.println("User: " + user);
        System.out.println();

        Connection conn = null;
        try {
            // Load PostgreSQL driver
            Class.forName("org.postgresql.Driver");

            // Connect
            conn = DriverManager.getConnection(url, user, pass);
            System.out.println("[PASS] Connected to database");

            // Test query
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM AD_Table");
            if (rs.next()) {
                System.out.println("[PASS] AD_Table count: " + rs.getInt(1));
            }
            rs.close();

            // Check for test tables
            rs = stmt.executeQuery("SELECT COUNT(*) FROM AD_Table WHERE TableName LIKE 'HO_%' OR TableName LIKE 'NT_%'");
            if (rs.next()) {
                int testTables = rs.getInt(1);
                if (testTables > 0) {
                    System.out.println("[INFO] Found " + testTables + " test tables (HO_* or NT_*)");
                } else {
                    System.out.println("[INFO] No test tables found (clean state)");
                }
            }
            rs.close();

            // Check GardenWorld client
            rs = stmt.executeQuery("SELECT Name FROM AD_Client WHERE AD_Client_ID = 11");
            if (rs.next()) {
                System.out.println("[PASS] GardenWorld client: " + rs.getString(1));
            } else {
                System.out.println("[WARN] GardenWorld (AD_Client_ID=11) not found");
            }
            rs.close();

            stmt.close();
            System.out.println();
            System.out.println("=== DATABASE TEST PASSED ===");

        } catch (ClassNotFoundException e) {
            System.err.println("[FAIL] PostgreSQL driver not found");
            System.err.println("Add postgresql-XX.jar to classpath");
        } catch (Exception e) {
            System.err.println("[FAIL] " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (Exception e) {}
            }
        }
    }
}
