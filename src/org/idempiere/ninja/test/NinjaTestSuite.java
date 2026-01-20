/******************************************************************************
 * Ninja Test Suite - Step-by-step testing from simple to complex
 *
 * Test levels:
 *   1. Basic - File existence, JDBC connection
 *   2. Excel - Parse XLS structure (no iDempiere needed)
 *   3. Schema - Validate model against DB schema (silent, no iDempiere)
 *   4. Model - iDempiere model layer (MTable, Query) - requires OSGi
 *   5. Inject - Full injection with rollback - requires OSGi
 *   6. BlackBox - GardenWorld sample data validation - requires OSGi
 *****************************************************************************/
package org.idempiere.ninja.test;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ninja Test Suite
 *
 * Runs tests in order from simplest to most complex.
 * Stops at first failure to help identify root cause.
 *
 * Log Levels (iDempiere compatible):
 *   SEVERE  - Test failures
 *   WARNING - Potential issues
 *   INFO    - Test progress
 *   FINE    - Detailed steps
 *   FINER   - Very detailed debug
 *   FINEST  - All debug output
 *
 * Usage:
 *   java NinjaTestSuite <xls-file> [level] [loglevel]
 *
 *   level: 1-6 (default: all)
 *   loglevel: SEVERE|WARNING|INFO|FINE|FINER|FINEST (default: INFO)
 *
 * Examples:
 *   NinjaTestSuite MyModel.xls          # Run all tests
 *   NinjaTestSuite MyModel.xls 2        # Run up to Excel test
 *   NinjaTestSuite MyModel.xls 6 FINE   # Full test with debug
 */
public class NinjaTestSuite {

    private static final Logger log = Logger.getLogger(NinjaTestSuite.class.getName());

    // Test configuration
    private String xlsPath;
    private int maxLevel = 6;
    private Level logLevel = Level.INFO;

    // DB connection (from properties or defaults)
    private String dbHost = "localhost";
    private String dbPort = "5432";
    private String dbName = "idempiere";
    private String dbUser = "adempiere";
    private String dbPass = "adempiere";
    private String propsFile;

    // Test results
    private int passed = 0;
    private int failed = 0;
    private int skipped = 0;

    // Parsed model data (for Level 2+)
    private List<TableDef> parsedTables = new ArrayList<>();
    private Connection dbConn = null;

    // Inner class for table definitions
    static class TableDef {
        String tableName;
        String windowName;
        List<ColumnDef> columns = new ArrayList<>();
        String parentTable;  // for detail tables

        @Override
        public String toString() {
            return tableName + " (" + columns.size() + " columns)";
        }
    }

    static class ColumnDef {
        String columnName;
        String displayName;
        String dataType;
        boolean isMandatory;
        String reference;  // foreign key reference
        String defaultValue;
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            return;
        }

        NinjaTestSuite suite = new NinjaTestSuite();
        suite.xlsPath = args[0];

        if (args.length > 1) {
            try {
                suite.maxLevel = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                // Might be log level
                suite.setLogLevel(args[1]);
            }
        }

        if (args.length > 2) {
            suite.setLogLevel(args[2]);
        }

        boolean success = suite.runAllTests();
        System.exit(success ? 0 : 1);
    }

    private static void printUsage() {
        System.out.println("Ninja Test Suite");
        System.out.println("================");
        System.out.println();
        System.out.println("Usage: NinjaTestSuite <xls-file> [level] [loglevel]");
        System.out.println();
        System.out.println("Test Levels (Silent - no iDempiere startup):");
        System.out.println("  1  Basic   - File and JDBC connection");
        System.out.println("  2  Excel   - Parse XLS/XLSX model structure");
        System.out.println("  3  Schema  - Validate against DB (AD_Table, references)");
        System.out.println();
        System.out.println("Test Levels (Requires OSGi - run from Eclipse):");
        System.out.println("  4  Model   - iDempiere model layer (MTable, Query)");
        System.out.println("  5  Inject  - Full injection with rollback");
        System.out.println("  6  BlackBox- GardenWorld CRUD validation");
        System.out.println();
        System.out.println("Log Levels: SEVERE|WARNING|INFO|FINE|FINER|FINEST");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  NinjaTestSuite MyModel.xls       # Run all silent tests");
        System.out.println("  NinjaTestSuite MyModel.xls 3     # Up to schema validation");
        System.out.println("  NinjaTestSuite MyModel.xls 3 FINE  # With detailed logging");
    }

    private void setLogLevel(String level) {
        try {
            this.logLevel = Level.parse(level.toUpperCase());
        } catch (Exception e) {
            this.logLevel = Level.INFO;
        }
        log.setLevel(this.logLevel);
        // Also set handler level to show FINER/FINEST
        for (java.util.logging.Handler h : Logger.getLogger("").getHandlers()) {
            h.setLevel(this.logLevel);
        }
    }

    public boolean runAllTests() {
        printBanner();

        log.info("Excel File: " + xlsPath);
        log.info("Max Level: " + maxLevel);
        log.info("Log Level: " + logLevel);
        System.out.println();

        boolean ok = true;

        // Level 1: Basic
        if (maxLevel >= 1) {
            ok = runLevel1Basic();
            if (!ok) return finishTests();
        }

        // Level 2: Excel
        if (maxLevel >= 2) {
            ok = runLevel2Excel();
            if (!ok) return finishTests();
        }

        // Level 3: Schema validation (silent - direct JDBC)
        if (maxLevel >= 3) {
            ok = runLevel3Schema();
            if (!ok) return finishTests();
        }

        // Level 4+: Requires OSGi
        if (maxLevel >= 4) {
            log.info("");
            log.warning("Level 4+ requires iDempiere OSGi environment");
            log.warning("Run from Eclipse with 'iDempiere Test' launch config");
            skipped += 3;
        }

        return finishTests();
    }

    private void printBanner() {
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║     Ninja Test Suite                                      ║");
        System.out.println("║     Step-by-step validation                               ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private boolean finishTests() {
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("TEST RESULTS: " + passed + " passed, " + failed + " failed, " + skipped + " skipped");
        System.out.println("═══════════════════════════════════════════════════════════");
        return failed == 0;
    }

    // ========== LEVEL 1: BASIC ==========
    private boolean runLevel1Basic() {
        System.out.println("═══ LEVEL 1: BASIC ═══");

        // Test 1.1: File exists
        log.info("[1.1] Checking file exists...");
        File file = new File(xlsPath);
        if (!file.exists()) {
            log.severe("[FAIL] File not found: " + xlsPath);
            failed++;
            return false;
        }
        log.info("[PASS] File exists: " + file.length() + " bytes");
        passed++;

        // Test 1.2: Find properties
        log.info("[1.2] Finding idempiere.properties...");
        propsFile = findPropertiesFile();
        if (propsFile == null) {
            log.warning("[SKIP] Properties not found, using defaults");
            skipped++;
        } else {
            log.info("[PASS] Properties: " + propsFile);
            loadDBConfig(propsFile);
            passed++;
        }

        // Test 1.3: JDBC connection
        log.info("[1.3] Testing JDBC connection...");
        try {
            Class.forName("org.postgresql.Driver");
            String url = "jdbc:postgresql://" + dbHost + ":" + dbPort + "/" + dbName;
            log.fine("URL: " + url);
            log.fine("User: " + dbUser);

            Connection conn = DriverManager.getConnection(url, dbUser, dbPass);
            Statement stmt = conn.createStatement();

            // Quick test
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM AD_Table");
            if (rs.next()) {
                log.info("[PASS] Connected, AD_Table count: " + rs.getInt(1));
                passed++;
            }
            rs.close();
            stmt.close();
            conn.close();

        } catch (ClassNotFoundException e) {
            log.warning("[SKIP] PostgreSQL driver not in classpath");
            skipped++;
        } catch (Exception e) {
            log.severe("[FAIL] Database connection: " + e.getMessage());
            failed++;
            return false;
        }

        return true;
    }

    // ========== LEVEL 2: EXCEL ==========
    private boolean runLevel2Excel() {
        System.out.println();
        System.out.println("═══ LEVEL 2: EXCEL ═══");

        try {
            FileInputStream fis = new FileInputStream(xlsPath);
            Object workbook;
            Class<?> wbClass;

            // Test 2.1: Open workbook
            log.info("[2.1] Opening workbook...");
            if (xlsPath.toLowerCase().endsWith(".xlsx")) {
                log.fine("Using XSSFWorkbook for xlsx");
                wbClass = Class.forName("org.apache.poi.xssf.usermodel.XSSFWorkbook");
            } else {
                log.fine("Using HSSFWorkbook for xls");
                wbClass = Class.forName("org.apache.poi.hssf.usermodel.HSSFWorkbook");
            }
            workbook = wbClass.getConstructor(java.io.InputStream.class).newInstance(fis);

            int n = (Integer) wbClass.getMethod("getNumberOfSheets").invoke(workbook);
            log.info("[PASS] Workbook opened: " + n + " sheets");
            passed++;

            // Test 2.2: Find model sheet
            log.info("[2.2] Finding model definition sheet...");
            Object modelSheet = null;
            for (int i = 0; i < n; i++) {
                Object sheet = wbClass.getMethod("getSheetAt", int.class).invoke(workbook, i);
                String name = (String) sheet.getClass().getMethod("getSheetName").invoke(sheet);
                log.fine("  Sheet: " + name);

                // Priority: ModelMakerSource > Model (skip legacy RO format)
                if ("ModelMakerSource".equalsIgnoreCase(name)) {
                    modelSheet = sheet;
                    modelFormat = "columnar";
                } else if ("Model".equalsIgnoreCase(name) && modelSheet == null) {
                    modelSheet = sheet;
                    modelFormat = "standard";
                }
            }

            if (modelSheet == null) {
                log.severe("[FAIL] No Model sheet found (need ModelMakerSource or Model)");
                failed++;
                return false;
            }
            log.info("[PASS] Model sheet: " + modelFormat + " format");
            passed++;

            // Test 2.3: Parse model structure
            log.info("[2.3] Parsing model structure...");
            parsedTables = parseModelSheet(modelSheet);
            if (parsedTables.isEmpty()) {
                log.severe("[FAIL] No tables found in model sheet");
                failed++;
                return false;
            }
            log.info("[PASS] Found " + parsedTables.size() + " table(s)");
            for (TableDef t : parsedTables) {
                log.fine("  Table: " + t.tableName + " (" + t.columns.size() + " columns)");
                if (t.parentTable != null) log.fine("    Parent: " + t.parentTable);
            }
            passed++;

            // Test 2.4: Validate table structure
            log.info("[2.4] Validating table structure...");
            int issues = 0;
            for (TableDef t : parsedTables) {
                if (t.tableName == null || t.tableName.isEmpty()) {
                    log.warning("  Table missing name");
                    issues++;
                }
                if (t.columns.isEmpty()) {
                    log.warning("  Table " + t.tableName + " has no columns");
                    issues++;
                }
                // Check for key column
                boolean hasKey = false;
                for (ColumnDef c : t.columns) {
                    if (c.columnName != null && c.columnName.endsWith("_ID") &&
                        c.columnName.equalsIgnoreCase(t.tableName + "_ID")) {
                        hasKey = true;
                        break;
                    }
                }
                if (!hasKey && !t.tableName.isEmpty()) {
                    log.fine("  Note: " + t.tableName + " has no standard key column");
                }
            }
            if (issues > 0) {
                log.warning("[WARN] " + issues + " structure issue(s) found");
            } else {
                log.info("[PASS] Table structure valid");
            }
            passed++;

            wbClass.getMethod("close").invoke(workbook);
            fis.close();

        } catch (ClassNotFoundException e) {
            log.severe("[FAIL] POI library not found: " + e.getMessage());
            log.info("Add lib/poi.jar to classpath");
            failed++;
            return false;
        } catch (Exception e) {
            log.severe("[FAIL] Excel parsing: " + e.getMessage());
            if (e.getCause() != null) {
                log.severe("  Cause: " + e.getCause().getMessage());
            }
            if (logLevel.intValue() <= Level.FINE.intValue()) {
                e.printStackTrace();
            }
            failed++;
            return false;
        }

        return true;
    }

    // Model format type
    private String modelFormat = "unknown";

    /**
     * Parse model sheet to extract table definitions
     * Supports: ModelMakerSource (columnar) and Model (standard) formats
     */
    private List<TableDef> parseModelSheet(Object sheet) throws Exception {
        List<TableDef> tables = new ArrayList<>();
        String sheetName = (String) sheet.getClass().getMethod("getSheetName").invoke(sheet);

        if ("ModelMakerSource".equalsIgnoreCase(sheetName)) {
            // Columnar format: Row 0 = Table names, Row 1 = Column names
            log.fine("Parsing columnar format (ModelMakerSource)");
            parseColumnarFormat(sheet, tables);
        } else {
            // Standard format: Model sheet with TableName, ColumnName columns
            log.fine("Parsing standard format (Model sheet)");
            parseStandardFormat(sheet, tables);
        }

        return tables;
    }

    /**
     * Parse standard Model sheet format (rows with TableName, ColumnName columns)
     */
    private void parseStandardFormat(Object sheet, List<TableDef> tables) throws Exception {
        Method getRow = sheet.getClass().getMethod("getRow", int.class);
        Method getLastRowNum = sheet.getClass().getMethod("getLastRowNum");
        int lastRow = (Integer) getLastRowNum.invoke(sheet);

        TableDef currentTable = null;
        int tableNameCol = -1, columnNameCol = -1, dataTypeCol = -1;

        // Find header row first
        for (int r = 0; r <= Math.min(lastRow, 10); r++) {
            Object row = getRow.invoke(sheet, r);
            if (row == null) continue;

            for (int c = 0; c < 20; c++) {
                String val = getCellValue(row, c);
                if (val == null) continue;

                String valLower = val.toLowerCase().trim();
                if (valLower.equals("tablename") || valLower.equals("table name") || valLower.equals("table")) {
                    tableNameCol = c;
                } else if (valLower.equals("columnname") || valLower.equals("column name") || valLower.equals("column")) {
                    columnNameCol = c;
                } else if (valLower.equals("datatype") || valLower.equals("data type") || valLower.equals("type") || valLower.equals("reference")) {
                    dataTypeCol = c;
                }
            }
            if (tableNameCol >= 0) break;
        }

        log.fine("Header columns: table=" + tableNameCol + ", column=" + columnNameCol + ", type=" + dataTypeCol);

        // Parse data rows
        for (int r = 1; r <= lastRow; r++) {
            Object row = getRow.invoke(sheet, r);
            if (row == null) continue;

            String tableName = tableNameCol >= 0 ? getCellValue(row, tableNameCol) : null;
            String columnName = columnNameCol >= 0 ? getCellValue(row, columnNameCol) : null;
            String dataType = dataTypeCol >= 0 ? getCellValue(row, dataTypeCol) : null;

            // New table?
            if (tableName != null && !tableName.isEmpty() && !tableName.equalsIgnoreCase("tablename")) {
                currentTable = new TableDef();
                currentTable.tableName = tableName;
                tables.add(currentTable);
                log.finer("Row " + r + ": New table " + tableName);
            }

            // Add column to current table
            if (currentTable != null && columnName != null && !columnName.isEmpty() &&
                !columnName.equalsIgnoreCase("columnname") && !columnName.equalsIgnoreCase("column name")) {
                ColumnDef col = new ColumnDef();
                col.columnName = columnName;
                col.dataType = dataType;
                currentTable.columns.add(col);
                log.finer("Row " + r + ":   Column " + columnName + " (" + dataType + ")");
            }
        }
    }

    /**
     * Parse columnar format (ModelMakerSource)
     * Row 1 = Table names (grouped columns)
     * Row 2 = Column names
     */
    private void parseColumnarFormat(Object sheet, List<TableDef> tables) throws Exception {
        Method getRow = sheet.getClass().getMethod("getRow", int.class);

        Object row0 = getRow.invoke(sheet, 0);  // Table names
        Object row1 = getRow.invoke(sheet, 1);  // Column names (might have header like "ColumnName")

        if (row0 == null) {
            log.warning("Columnar format: Row 0 is null");
            return;
        }

        // Get last cell number to determine column count
        Method getLastCellNum = row0.getClass().getMethod("getLastCellNum");
        int lastCol = (Short) getLastCellNum.invoke(row0);

        Map<String, TableDef> tableMap = new HashMap<>();
        String lastTableName = null;

        // Scan row 0 for table names, row 1 for column names
        for (int c = 0; c <= lastCol; c++) {
            String tableName = getCellValue(row0, c);
            String columnName = row1 != null ? getCellValue(row1, c) : null;

            // Skip header labels
            if (columnName != null && (columnName.equalsIgnoreCase("ColumnName") ||
                columnName.equalsIgnoreCase("Column Name"))) {
                continue;
            }

            // Track table grouping
            if (tableName != null && !tableName.isEmpty()) {
                lastTableName = tableName;
            }

            // Add column to table
            if (lastTableName != null && columnName != null && !columnName.isEmpty()) {
                TableDef t = tableMap.get(lastTableName);
                if (t == null) {
                    t = new TableDef();
                    t.tableName = lastTableName;
                    tableMap.put(lastTableName, t);
                    tables.add(t);
                    log.finer("Found table: " + lastTableName);
                }

                ColumnDef col = new ColumnDef();
                col.columnName = columnName;
                t.columns.add(col);
                log.finer("  Column " + c + ": " + columnName + " -> " + lastTableName);
            }
        }
    }

    /**
     * Get cell value as string using reflection
     */
    private String getCellValue(Object row, int colIndex) {
        try {
            Method getCell = row.getClass().getMethod("getCell", int.class);
            Object cell = getCell.invoke(row, colIndex);
            if (cell == null) return null;

            // Try getStringCellValue first
            try {
                Method getStringValue = cell.getClass().getMethod("getStringCellValue");
                String val = (String) getStringValue.invoke(cell);
                return val != null && !val.isEmpty() ? val.trim() : null;
            } catch (Exception e) {
                // Try numeric
                try {
                    Method getNumericValue = cell.getClass().getMethod("getNumericCellValue");
                    double num = (Double) getNumericValue.invoke(cell);
                    return String.valueOf((int) num);
                } catch (Exception e2) {
                    return null;
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ========== LEVEL 3: SCHEMA VALIDATION ==========
    private boolean runLevel3Schema() {
        System.out.println();
        System.out.println("═══ LEVEL 3: SCHEMA ═══");

        if (parsedTables.isEmpty()) {
            log.warning("[SKIP] No tables parsed from Excel");
            skipped++;
            return true;
        }

        // Test 3.1: Get DB connection
        log.info("[3.1] Connecting to database...");
        try {
            Class.forName("org.postgresql.Driver");
            String url = "jdbc:postgresql://" + dbHost + ":" + dbPort + "/" + dbName;
            dbConn = DriverManager.getConnection(url, dbUser, dbPass);
            log.info("[PASS] Connected to " + dbName);
            passed++;
        } catch (ClassNotFoundException e) {
            log.warning("[SKIP] PostgreSQL driver not in classpath");
            skipped += 4;
            return true;
        } catch (Exception e) {
            log.severe("[FAIL] Database connection: " + e.getMessage());
            failed++;
            return false;
        }

        // Test 3.2: Check if tables exist in AD_Table
        log.info("[3.2] Checking tables in AD_Table...");
        int existingTables = 0, newTables = 0;
        try {
            PreparedStatement ps = dbConn.prepareStatement(
                "SELECT AD_Table_ID, TableName FROM AD_Table WHERE UPPER(TableName) = UPPER(?)");

            for (TableDef t : parsedTables) {
                ps.setString(1, t.tableName);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    existingTables++;
                    log.fine("  EXISTS: " + t.tableName + " (ID=" + rs.getInt(1) + ")");
                } else {
                    newTables++;
                    log.fine("  NEW: " + t.tableName);
                }
                rs.close();
            }
            ps.close();

            log.info("[PASS] Tables: " + existingTables + " existing, " + newTables + " new");
            passed++;
        } catch (Exception e) {
            log.severe("[FAIL] AD_Table query: " + e.getMessage());
            failed++;
            return false;
        }

        // Test 3.3: Validate references (foreign keys point to valid tables)
        log.info("[3.3] Validating column references...");
        int validRefs = 0, invalidRefs = 0;
        try {
            // Get all valid table names
            Statement stmt = dbConn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT UPPER(TableName) FROM AD_Table");
            Map<String, Boolean> validTables = new HashMap<>();
            while (rs.next()) {
                validTables.put(rs.getString(1), true);
            }
            rs.close();
            stmt.close();

            // Check column references
            for (TableDef t : parsedTables) {
                for (ColumnDef c : t.columns) {
                    // Columns ending in _ID typically reference other tables
                    if (c.columnName != null && c.columnName.endsWith("_ID") &&
                        !c.columnName.equalsIgnoreCase(t.tableName + "_ID")) {

                        // Derive referenced table (e.g., C_BPartner_ID -> C_BPartner)
                        String refTable = c.columnName.substring(0, c.columnName.length() - 3);
                        if (validTables.containsKey(refTable.toUpperCase())) {
                            validRefs++;
                            log.finer("  " + t.tableName + "." + c.columnName + " -> " + refTable + " [OK]");
                        } else {
                            // Check if it's a standard iDempiere column
                            if (refTable.equals("AD_Client") || refTable.equals("AD_Org") ||
                                refTable.equals("CreatedBy") || refTable.equals("UpdatedBy")) {
                                validRefs++;
                            } else {
                                invalidRefs++;
                                log.fine("  " + t.tableName + "." + c.columnName + " -> " + refTable + " [NOT FOUND]");
                            }
                        }
                    }
                }
            }

            if (invalidRefs > 0) {
                log.warning("[WARN] References: " + validRefs + " valid, " + invalidRefs + " unknown");
            } else {
                log.info("[PASS] All " + validRefs + " references valid");
            }
            passed++;
        } catch (Exception e) {
            log.severe("[FAIL] Reference validation: " + e.getMessage());
            failed++;
            return false;
        }

        // Test 3.4: Check AD_Window entries for parsed tables
        log.info("[3.4] Checking AD_Window entries...");
        int windowsFound = 0;
        try {
            PreparedStatement ps = dbConn.prepareStatement(
                "SELECT w.AD_Window_ID, w.Name FROM AD_Window w " +
                "JOIN AD_Tab t ON w.AD_Window_ID = t.AD_Window_ID " +
                "JOIN AD_Table tbl ON t.AD_Table_ID = tbl.AD_Table_ID " +
                "WHERE UPPER(tbl.TableName) = UPPER(?) AND t.SeqNo = 10");

            for (TableDef t : parsedTables) {
                ps.setString(1, t.tableName);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    windowsFound++;
                    t.windowName = rs.getString(2);
                    log.fine("  " + t.tableName + " -> Window: " + t.windowName);
                }
                rs.close();
            }
            ps.close();

            if (windowsFound > 0) {
                log.info("[PASS] Found " + windowsFound + " window(s) for existing tables");
            } else {
                log.fine("  No windows found (tables may be new)");
            }
            passed++;
        } catch (Exception e) {
            log.warning("[WARN] AD_Window check: " + e.getMessage());
        }

        // Test 3.5: Check AD_Menu entries
        log.info("[3.5] Checking AD_Menu entries...");
        int menusFound = 0;
        try {
            PreparedStatement ps = dbConn.prepareStatement(
                "SELECT m.AD_Menu_ID, m.Name FROM AD_Menu m " +
                "JOIN AD_Window w ON m.AD_Window_ID = w.AD_Window_ID " +
                "JOIN AD_Tab t ON w.AD_Window_ID = t.AD_Window_ID " +
                "JOIN AD_Table tbl ON t.AD_Table_ID = tbl.AD_Table_ID " +
                "WHERE UPPER(tbl.TableName) = UPPER(?) AND t.SeqNo = 10");

            for (TableDef t : parsedTables) {
                ps.setString(1, t.tableName);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    menusFound++;
                    log.fine("  " + t.tableName + " -> Menu: " + rs.getString(2));
                }
                rs.close();
            }
            ps.close();

            if (menusFound > 0) {
                log.info("[PASS] Found " + menusFound + " menu entry(ies)");
            } else {
                log.fine("  No menu entries (tables may be new or not exposed)");
            }
            passed++;
        } catch (Exception e) {
            log.warning("[WARN] AD_Menu check: " + e.getMessage());
        }

        // Test 3.6: Check for GardenWorld data (AD_Client_ID=11)
        log.info("[3.6] Checking GardenWorld availability...");
        try {
            Statement stmt = dbConn.createStatement();
            ResultSet rs = stmt.executeQuery(
                "SELECT Name FROM AD_Client WHERE AD_Client_ID = 11");
            if (rs.next()) {
                log.info("[PASS] GardenWorld client: " + rs.getString(1));
                passed++;
            } else {
                log.warning("[WARN] GardenWorld (AD_Client_ID=11) not found");
                log.warning("  Black box tests will be skipped");
            }
            rs.close();
            stmt.close();
        } catch (Exception e) {
            log.warning("[WARN] Could not check GardenWorld: " + e.getMessage());
        }

        // Close connection
        try {
            if (dbConn != null) dbConn.close();
        } catch (Exception e) { }

        return true;
    }

    // ========== HELPER METHODS ==========

    private String findPropertiesFile() {
        String[] paths = {
            System.getProperty("PropertyFile"),
            "/home/red1/idempiere-dev-setup/idempiere/idempiere.properties",
            "./idempiere.properties",
            System.getProperty("user.home") + "/idempiere.properties"
        };

        for (String path : paths) {
            if (path != null && new File(path).exists()) {
                return path;
            }
        }
        return null;
    }

    private void loadDBConfig(String propsPath) {
        try {
            Properties props = new Properties();
            props.load(new FileInputStream(propsPath));

            String url = props.getProperty("Connection");
            if (url != null && url.contains("postgresql")) {
                // Parse jdbc:postgresql://host:port/db
                String[] parts = url.split("//")[1].split("/");
                String[] hostPort = parts[0].split(":");
                dbHost = hostPort[0];
                dbPort = hostPort.length > 1 ? hostPort[1] : "5432";
                dbName = parts[1];
            }

            dbUser = props.getProperty("db_Uid", dbUser);
            dbPass = props.getProperty("db_Pwd", dbPass);

            log.fine("DB: " + dbHost + ":" + dbPort + "/" + dbName);
        } catch (Exception e) {
            log.warning("Could not load DB config: " + e.getMessage());
        }
    }
}
