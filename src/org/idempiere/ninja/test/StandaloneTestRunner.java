/******************************************************************************
 * Product: iDempiere Ninja Plugin Generator                                  *
 * Copyright (C) Contributors                                                 *
 *                                                                            *
 * Standalone Test Runner - runs without OSGi/IApplication                    *
 * For developers to test incrementally without launching iDempiere           *
 *****************************************************************************/
package org.idempiere.ninja.test;

import java.io.File;
import java.util.Properties;
import java.util.logging.Level;

import org.adempiere.util.ServerContext;
import org.compiere.Adempiere;
import org.compiere.model.MColumn;
import org.compiere.model.MTable;
import org.compiere.model.Query;
import org.compiere.util.CLogMgt;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Ini;
import org.compiere.util.Trx;

/**
 * Standalone Test Runner
 *
 * Initializes iDempiere environment without OSGi and runs tests.
 * Can be run directly from IDE or command line with proper classpath.
 *
 * Usage from IDE:
 *   Run as Java Application with iDempiere libraries in classpath
 *
 * Usage from command line:
 *   java -cp "path/to/idempiere/plugins/*" org.idempiere.ninja.test.StandaloneTestRunner
 *
 * @author red1 - red1org@gmail.com
 */
public class StandaloneTestRunner {

    private static boolean initialized = false;
    private static String lastError = null;

    /**
     * Initialize iDempiere environment silently
     * @param propertiesPath path to idempiere.properties (null for default)
     * @return true if successful
     */
    public static boolean initializeEnvironment(String propertiesPath) {
        if (initialized) {
            return true;
        }

        try {
            System.out.println("[INIT] Initializing iDempiere environment...");

            // Set up server context
            Properties serverContext = new Properties();
            ServerContext.setCurrentInstance(serverContext);

            // Find properties file
            String propFile = propertiesPath;
            if (propFile == null) {
                propFile = Ini.getFileName(false);
            }

            File prop = new File(propFile);
            if (!prop.exists()) {
                // Try common locations
                String[] tryPaths = {
                    "/home/red1/idempiere-dev-setup/idempiere/idempiere.properties",
                    "/opt/idempiere-server/idempiere.properties",
                    "./idempiere.properties",
                    System.getProperty("user.home") + "/idempiere.properties"
                };

                for (String path : tryPaths) {
                    prop = new File(path);
                    if (prop.exists()) {
                        propFile = path;
                        break;
                    }
                }
            }

            if (!prop.exists()) {
                lastError = "idempiere.properties not found. Tried: " + propFile;
                System.err.println("[INIT ERROR] " + lastError);
                return false;
            }

            System.out.println("[INIT] Using properties: " + propFile);

            // Set the property file location
            System.setProperty("PropertyFile", propFile);

            // Start Adempiere (server mode = false for client-like init)
            if (!Adempiere.isStarted()) {
                boolean started = Adempiere.startup(false);
                if (!started) {
                    lastError = "Adempiere.startup() failed";
                    System.err.println("[INIT ERROR] " + lastError);
                    return false;
                }
            }

            // Set minimal log level
            CLogMgt.setLevel(Level.WARNING);

            // Set context
            Env.setContext(Env.getCtx(), Env.AD_CLIENT_ID, 0);
            Env.setContext(Env.getCtx(), Env.AD_ORG_ID, 0);
            Env.setContext(Env.getCtx(), "#AD_User_ID", 0);

            // Test database connection
            String dbTest = DB.getSQLValueString(null, "SELECT 'OK'");
            if (!"OK".equals(dbTest)) {
                lastError = "Database connection test failed";
                System.err.println("[INIT ERROR] " + lastError);
                return false;
            }

            System.out.println("[INIT] Database connection OK");
            System.out.println("[INIT] iDempiere initialized successfully");

            initialized = true;
            return true;

        } catch (Exception e) {
            lastError = e.getMessage();
            System.err.println("[INIT ERROR] " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Quick database connectivity test
     */
    public static void testDatabaseConnection() {
        System.out.println("\n=== DATABASE CONNECTION TEST ===");

        if (!initializeEnvironment(null)) {
            System.err.println("Cannot initialize: " + lastError);
            return;
        }

        try {
            // Test basic query
            int tableCount = DB.getSQLValue(null, "SELECT COUNT(*) FROM AD_Table");
            System.out.println("[PASS] AD_Table count: " + tableCount);

            // Test MTable
            MTable testTable = MTable.get(Env.getCtx(), "AD_Table");
            if (testTable != null) {
                System.out.println("[PASS] MTable.get() works: " + testTable.getTableName());
            }

            // Test Query
            int colCount = new Query(Env.getCtx(), MColumn.Table_Name, null, null).count();
            System.out.println("[PASS] Query count AD_Column: " + colCount);

            // Test transaction
            String trxName = Trx.createTrxName("Test");
            Trx trx = Trx.get(trxName, true);
            System.out.println("[PASS] Transaction created: " + trxName);
            trx.rollback();
            trx.close();
            System.out.println("[PASS] Transaction rolled back and closed");

            System.out.println("\n=== ALL DATABASE TESTS PASSED ===");

        } catch (Exception e) {
            System.err.println("[FAIL] " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Test NinjaProcessor with a simple model
     */
    public static void testNinjaProcessorBasic() {
        System.out.println("\n=== NINJA PROCESSOR BASIC TEST ===");

        if (!initializeEnvironment(null)) {
            System.err.println("Cannot initialize: " + lastError);
            return;
        }

        String testFile = "/home/red1/Projects/org.idempiere.ninja/templates/Ninja_HRMIS.xlsx";
        File f = new File(testFile);
        if (!f.exists()) {
            System.err.println("[SKIP] Test file not found: " + testFile);
            return;
        }

        try {
            // Create test with rollback
            NinjaProcessorTest test = new NinjaProcessorTest()
                    .setClientId(11)  // GardenWorld
                    .setVerbose(true)
                    .setRunCrudTests(false)  // Start simple
                    .setRun2PackTests(false);

            System.out.println("[TEST] Running NinjaProcessor test...");
            System.out.println("[TEST] File: " + testFile);

            NinjaProcessorTest.TestResult result = test.runTestWithRollback(testFile);

            System.out.println("\n" + result);

        } catch (Exception e) {
            System.err.println("[FAIL] " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Main entry point for standalone testing
     */
    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║     Ninja Standalone Test Runner                          ║");
        System.out.println("║     Silent mode - no OSGi required                        ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();

        String testMode = args.length > 0 ? args[0] : "db";

        switch (testMode.toLowerCase()) {
            case "db":
            case "database":
                // Just test database connection
                testDatabaseConnection();
                break;

            case "ninja":
            case "processor":
                // Test NinjaProcessor
                testNinjaProcessorBasic();
                break;

            case "full":
                // Run full test suite
                testDatabaseConnection();
                testNinjaProcessorBasic();
                break;

            default:
                System.out.println("Usage: StandaloneTestRunner [db|ninja|full]");
                System.out.println("  db     - Test database connection only");
                System.out.println("  ninja  - Test NinjaProcessor with HRMIS sample");
                System.out.println("  full   - Run all tests");
                break;
        }
    }

    public static String getLastError() {
        return lastError;
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
