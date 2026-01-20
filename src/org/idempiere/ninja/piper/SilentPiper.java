/******************************************************************************
 * SilentPiper - Silent 2Pack/PIPO handler with SQLite tracking
 *
 * Handles 2Pack import/export without iDempiere runtime.
 * Uses SQLite for local metadata, logs, and version tracking.
 *
 * Features:
 *   - Parse and validate 2Pack XML
 *   - Import to iDempiere DB via JDBC
 *   - Local SQLite for tracking:
 *     - Import/export history
 *     - Version control
 *     - Ninja operation logs
 *   - Dry-run mode for testing
 *   - Rollback support
 *
 * @author red1
 *****************************************************************************/
package org.idempiere.ninja.piper;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * SilentPiper - Silent PIPO handler
 */
public class SilentPiper {

    private static final Logger log = Logger.getLogger(SilentPiper.class.getName());

    // Target iDempiere DB
    private Connection ideConn;

    // Local SQLite DB for tracking
    private Connection sqliteConn;
    private String sqliteDbPath;

    private boolean verbose = false;
    private boolean dryRun = false;

    // Cache for lookups
    private Map<String, Integer> tableCache = new HashMap<>();
    private Map<String, Integer> windowCache = new HashMap<>();

    // Statistics
    private int tablesImported = 0;
    private int columnsImported = 0;
    private int windowsImported = 0;
    private int menusImported = 0;
    private int errors = 0;

    // Current operation ID (for SQLite tracking)
    private long operationId;

    /**
     * Create SilentPiper with SQLite tracking
     * @param sqliteDbPath Path to SQLite database (created if not exists)
     */
    public SilentPiper(String sqliteDbPath) throws SQLException {
        this.sqliteDbPath = sqliteDbPath;
        initSqlite();
    }

    /**
     * Initialize SQLite database for local tracking
     */
    private void initSqlite() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found", e);
        }

        sqliteConn = DriverManager.getConnection("jdbc:sqlite:" + sqliteDbPath);
        sqliteConn.setAutoCommit(false);

        // Create tracking tables
        try (Statement stmt = sqliteConn.createStatement()) {
            // Operations log
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS piper_operation (" +
                "  operation_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  operation_type TEXT NOT NULL," +  // IMPORT, EXPORT, VALIDATE
                "  file_path TEXT," +
                "  target_db TEXT," +
                "  status TEXT," +  // STARTED, SUCCESS, FAILED, ROLLED_BACK
                "  started_at TEXT DEFAULT CURRENT_TIMESTAMP," +
                "  completed_at TEXT," +
                "  tables_count INTEGER DEFAULT 0," +
                "  columns_count INTEGER DEFAULT 0," +
                "  windows_count INTEGER DEFAULT 0," +
                "  errors_count INTEGER DEFAULT 0," +
                "  notes TEXT" +
                ")"
            );

            // Detail log
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS piper_detail (" +
                "  detail_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  operation_id INTEGER," +
                "  record_type TEXT," +  // AD_Table, AD_Column, etc.
                "  record_name TEXT," +
                "  action TEXT," +  // CREATE, UPDATE, SKIP, ERROR
                "  message TEXT," +
                "  created_at TEXT DEFAULT CURRENT_TIMESTAMP," +
                "  FOREIGN KEY (operation_id) REFERENCES piper_operation(operation_id)" +
                ")"
            );

            // Version tracking (for VersionMaster)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS piper_version (" +
                "  version_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  bundle_name TEXT," +
                "  bundle_version TEXT," +
                "  pack_file TEXT," +
                "  installed_at TEXT DEFAULT CURRENT_TIMESTAMP," +
                "  target_db TEXT," +
                "  status TEXT" +
                ")"
            );

            // Ninja model tracking
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS ninja_model (" +
                "  model_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  excel_file TEXT," +
                "  model_name TEXT," +
                "  tables_created INTEGER," +
                "  windows_created INTEGER," +
                "  created_at TEXT DEFAULT CURRENT_TIMESTAMP," +
                "  target_db TEXT," +
                "  status TEXT" +
                ")"
            );

            // ========== STAGING TABLES (RO_ format) ==========
            // RO_ModelHeader - Model bundle header
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS RO_ModelHeader (" +
                "  RO_ModelHeader_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  RO_ModelHeader_UU TEXT UNIQUE," +
                "  Name TEXT NOT NULL," +
                "  Description TEXT," +
                "  Help TEXT," +
                "  Author TEXT," +
                "  Version TEXT," +
                "  operation_id INTEGER," +
                "  status TEXT DEFAULT 'STAGED'," +  // STAGED, APPLIED, ROLLED_BACK
                "  created_at TEXT DEFAULT CURRENT_TIMESTAMP" +
                ")"
            );

            // RO_ModelMaker - Table definitions
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS RO_ModelMaker (" +
                "  RO_ModelMaker_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  RO_ModelMaker_UU TEXT UNIQUE," +
                "  RO_ModelHeader_UU TEXT," +
                "  SeqNo INTEGER," +
                "  Name TEXT NOT NULL," +  // Table name
                "  Master TEXT," +  // Parent table
                "  ColumnSet TEXT," +  // Column definitions
                "  Description TEXT," +
                "  Help TEXT," +
                "  WorkflowStructure TEXT DEFAULT 'N'," +
                "  KanbanBoard TEXT DEFAULT 'N'," +
                "  Callout TEXT," +
                "  WorkflowModel TEXT," +
                "  operation_id INTEGER," +
                "  status TEXT DEFAULT 'STAGED'," +
                "  ad_table_id INTEGER," +  // ID in iDempiere after apply
                "  created_at TEXT DEFAULT CURRENT_TIMESTAMP," +
                "  FOREIGN KEY (RO_ModelHeader_UU) REFERENCES RO_ModelHeader(RO_ModelHeader_UU)" +
                ")"
            );

            // Staging index
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_modelmaker_header ON RO_ModelMaker(RO_ModelHeader_UU)");
        }
        sqliteConn.commit();

        log.info("SQLite initialized: " + sqliteDbPath);
    }

    /**
     * Connect to target iDempiere database
     */
    public void connectIde(String host, String port, String database, String user, String password) throws SQLException {
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("PostgreSQL driver not found", e);
        }

        ideConn = DriverManager.getConnection(url, user, password);
        ideConn.setAutoCommit(false);
        log.info("Connected to iDempiere: " + database);
    }

    /**
     * Connect using idempiere.properties
     */
    public void connectFromProperties(String propsPath) throws Exception {
        java.util.Properties props = new java.util.Properties();
        props.load(new FileInputStream(propsPath));

        String connStr = props.getProperty("Connection");
        // Parse CConnection format: DBhost=localhost,DBport=5432,DBname=idempiere
        String host = "localhost";
        String port = "5432";
        String database = "idempiere";
        String user = "adempiere";
        String password = "adempiere";

        if (connStr != null && connStr.contains("DBhost")) {
            // iDempiere CConnection format
            for (String part : connStr.split(",")) {
                if (part.contains("DBhost=")) host = part.split("=")[1].replace("]", "");
                if (part.contains("DBport=")) port = part.split("=")[1];
                if (part.contains("DBname=")) database = part.split("=")[1];
                if (part.contains("UID=")) user = part.split("=")[1].replace("]", "");
            }
        } else if (connStr != null && connStr.contains("postgresql://")) {
            // JDBC URL format
            String[] parts = connStr.split("//")[1].split("/");
            String[] hostPort = parts[0].split(":");
            host = hostPort[0];
            port = hostPort.length > 1 ? hostPort[1] : "5432";
            database = parts[1];
        }

        // Check for separate password property
        String pwd = props.getProperty("db_Pwd");
        if (pwd != null) password = pwd;
        String uid = props.getProperty("db_Uid");
        if (uid != null) user = uid;

        connectIde(host, port, database, user, password);
    }

    public void setVerbose(boolean verbose) { this.verbose = verbose; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

    /**
     * Import a 2Pack file
     */
    public boolean importPack(String filePath) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) {
            log.severe("File not found: " + filePath);
            return false;
        }

        // Start operation tracking
        operationId = startOperation("IMPORT", filePath);

        log.info("Importing 2Pack: " + filePath);
        boolean success = false;

        try {
            if (filePath.toLowerCase().endsWith(".zip")) {
                success = importZip(file);
            } else if (filePath.toLowerCase().endsWith(".xml")) {
                success = importXml(new FileInputStream(file));
            } else {
                log.severe("Unsupported file type");
                return false;
            }

            if (success && !dryRun) {
                ideConn.commit();
                completeOperation(operationId, "SUCCESS");
                log.info("Import committed");
            } else if (dryRun) {
                ideConn.rollback();
                completeOperation(operationId, "DRY_RUN");
                log.info("Dry run - rolled back");
            } else {
                ideConn.rollback();
                completeOperation(operationId, "FAILED");
                log.info("Import failed - rolled back");
            }
        } catch (Exception e) {
            ideConn.rollback();
            completeOperation(operationId, "FAILED");
            throw e;
        }

        logSummary();
        return success;
    }

    /**
     * Validate a 2Pack file (dry run)
     */
    public boolean validatePack(String filePath) throws Exception {
        setDryRun(true);
        return importPack(filePath);
    }

    // ========== SQLite Tracking ==========

    private long startOperation(String type, String filePath) throws SQLException {
        String sql = "INSERT INTO piper_operation (operation_type, file_path, target_db, status) VALUES (?, ?, ?, 'STARTED')";
        try (PreparedStatement ps = sqliteConn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, type);
            ps.setString(2, filePath);
            ps.setString(3, ideConn != null ? ideConn.getCatalog() : "N/A");
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0;
    }

    private void completeOperation(long opId, String status) throws SQLException {
        String sql = "UPDATE piper_operation SET status = ?, completed_at = CURRENT_TIMESTAMP, " +
                     "tables_count = ?, columns_count = ?, windows_count = ?, errors_count = ? " +
                     "WHERE operation_id = ?";
        try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setInt(2, tablesImported);
            ps.setInt(3, columnsImported);
            ps.setInt(4, windowsImported);
            ps.setInt(5, errors);
            ps.setLong(6, opId);
            ps.executeUpdate();
        }
        sqliteConn.commit();
    }

    private void logDetail(String recordType, String recordName, String action, String message) {
        try {
            String sql = "INSERT INTO piper_detail (operation_id, record_type, record_name, action, message) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
                ps.setLong(1, operationId);
                ps.setString(2, recordType);
                ps.setString(3, recordName);
                ps.setString(4, action);
                ps.setString(5, message);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            // Don't fail main operation for logging errors
            log.fine("Log detail failed: " + e.getMessage());
        }
    }

    /**
     * Record Ninja model creation
     */
    public void recordNinjaModel(String excelFile, String modelName, int tables, int windows, String status) throws SQLException {
        String sql = "INSERT INTO ninja_model (excel_file, model_name, tables_created, windows_created, target_db, status) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
            ps.setString(1, excelFile);
            ps.setString(2, modelName);
            ps.setInt(3, tables);
            ps.setInt(4, windows);
            ps.setString(5, ideConn != null ? ideConn.getCatalog() : "N/A");
            ps.setString(6, status);
            ps.executeUpdate();
        }
        sqliteConn.commit();
    }

    /**
     * Get operation history
     */
    public void printHistory(int limit) throws SQLException {
        String sql = "SELECT operation_id, operation_type, file_path, status, started_at, " +
                     "tables_count, columns_count, errors_count FROM piper_operation " +
                     "ORDER BY operation_id DESC LIMIT ?";
        try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                System.out.println("=== SilentPiper History ===");
                while (rs.next()) {
                    System.out.printf("%d | %s | %s | %s | T:%d C:%d E:%d%n",
                        rs.getLong(1), rs.getString(2), rs.getString(4),
                        rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getInt(8));
                }
            }
        }
    }

    // ========== Import Implementation ==========

    private boolean importZip(File zipFile) throws Exception {
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().toLowerCase().endsWith(".xml") &&
                    !entry.getName().contains("/")) {
                    log.info("Processing: " + entry.getName());
                    try (InputStream is = zip.getInputStream(entry)) {
                        return importXml(is);
                    }
                }
            }
        }
        log.warning("No XML found in ZIP");
        return false;
    }

    private boolean importXml(InputStream xmlStream) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        SAXParser parser = factory.newSAXParser();
        PackInHandler handler = new PackInHandler();
        parser.parse(xmlStream, handler);
        return errors == 0;
    }

    /**
     * SAX Handler for 2Pack XML
     */
    private class PackInHandler extends DefaultHandler {
        private String currentTable = null;
        private Map<String, String> currentRecord = new HashMap<>();
        private StringBuilder textContent = new StringBuilder();

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            textContent.setLength(0);
            // Support both standard AD_ and Ninja RO_ formats
            if (qName.startsWith("AD_") || qName.startsWith("ad_") ||
                qName.startsWith("RO_") || qName.equals("idempiere")) {
                currentTable = qName;
                currentRecord.clear();
                for (int i = 0; i < attrs.getLength(); i++) {
                    currentRecord.put(attrs.getQName(i), attrs.getValue(i));
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            textContent.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String text = textContent.toString().trim();
            if (currentTable != null && !text.isEmpty() && !qName.equals(currentTable)) {
                currentRecord.put(qName, text);
            }
            if (qName.equals(currentTable) && !currentRecord.isEmpty()) {
                processRecord(currentTable, currentRecord);
                currentTable = null;
            }
        }
    }

    private void processRecord(String tableName, Map<String, String> record) {
        try {
            String name = record.getOrDefault("Name", record.get("TableName"));
            switch (tableName.toUpperCase()) {
                case "AD_TABLE":
                    importTable(record);
                    logDetail("AD_Table", name, dryRun ? "VALIDATE" : "CREATE", "OK");
                    break;
                case "AD_COLUMN":
                    importColumn(record);
                    logDetail("AD_Column", record.get("ColumnName"), dryRun ? "VALIDATE" : "CREATE", "OK");
                    break;
                case "AD_WINDOW":
                    importWindow(record);
                    logDetail("AD_Window", name, dryRun ? "VALIDATE" : "CREATE", "OK");
                    break;
                case "AD_MENU":
                    importMenu(record);
                    logDetail("AD_Menu", name, dryRun ? "VALIDATE" : "CREATE", "OK");
                    break;
                default:
                    if (verbose) log.fine("Skipping: " + tableName);
            }
        } catch (Exception e) {
            logDetail(tableName, "", "ERROR", e.getMessage());
            log.warning("Error importing " + tableName + ": " + e.getMessage());
            errors++;
        }
    }

    // Simplified import methods - just counting for now
    private void importTable(Map<String, String> r) throws SQLException { tablesImported++; }
    private void importColumn(Map<String, String> r) throws SQLException { columnsImported++; }
    private void importWindow(Map<String, String> r) throws SQLException { windowsImported++; }
    private void importMenu(Map<String, String> r) throws SQLException { menusImported++; }

    private void logSummary() {
        log.info("========== SilentPiper Summary ==========");
        log.info("Tables:  " + tablesImported);
        log.info("Columns: " + columnsImported);
        log.info("Windows: " + windowsImported);
        log.info("Menus:   " + menusImported);
        log.info("Errors:  " + errors);
        log.info("=========================================");
    }

    // ========== STAGING COMMANDS (Excel → SQLite) ==========

    /**
     * Stage Excel file into SQLite (RO_ModelHeader, RO_ModelMaker)
     * @param excelPath Path to Excel file (HRMIS.xlsx format)
     * @return Header UUID for the staged model bundle
     */
    public String stageExcel(String excelPath) throws Exception {
        File file = new File(excelPath);
        if (!file.exists()) {
            throw new Exception("Excel file not found: " + excelPath);
        }

        log.info("Staging Excel: " + excelPath);
        operationId = startOperation("STAGE", excelPath);

        // Use reflection for POI (avoid compile-time dependency)
        Object workbook = null;
        try {
            Class<?> workbookClass = Class.forName("org.apache.poi.xssf.usermodel.XSSFWorkbook");
            workbook = workbookClass.getConstructor(InputStream.class).newInstance(new FileInputStream(file));

            // Find ModelMakerSource sheet (preferred) or Model sheet
            Method getNumberOfSheets = workbookClass.getMethod("getNumberOfSheets");
            Method getSheetName = workbookClass.getMethod("getSheetName", int.class);
            Method getSheetAt = workbookClass.getMethod("getSheetAt", int.class);

            int numSheets = (Integer) getNumberOfSheets.invoke(workbook);
            Object modelSheet = null;
            String modelFormat = null;
            String sheetName = null;

            for (int i = 0; i < numSheets; i++) {
                String name = (String) getSheetName.invoke(workbook, i);
                if ("ModelMakerSource".equalsIgnoreCase(name)) {
                    modelSheet = getSheetAt.invoke(workbook, i);
                    modelFormat = "columnar";
                    sheetName = name;
                    break;
                } else if ("Model".equalsIgnoreCase(name) && modelSheet == null) {
                    modelSheet = getSheetAt.invoke(workbook, i);
                    modelFormat = "standard";
                    sheetName = name;
                }
            }

            if (modelSheet == null) {
                throw new Exception("No ModelMakerSource or Model sheet found in Excel");
            }

            log.info("Found sheet: " + sheetName + " (format: " + modelFormat + ")");

            // Create RO_ModelHeader
            String headerUU = UUID.randomUUID().toString();
            String modelName = file.getName().replace(".xlsx", "").replace(".xls", "");

            String sql = "INSERT INTO RO_ModelHeader (RO_ModelHeader_UU, Name, Description, operation_id, status) VALUES (?, ?, ?, ?, 'STAGED')";
            try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
                ps.setString(1, headerUU);
                ps.setString(2, modelName);
                ps.setString(3, "Staged from " + excelPath);
                ps.setLong(4, operationId);
                ps.executeUpdate();
            }

            // Parse and stage model definitions
            int stagedCount = 0;
            if ("columnar".equals(modelFormat)) {
                stagedCount = stageColumnarFormat(modelSheet, headerUU);
            } else {
                stagedCount = stageStandardFormat(modelSheet, headerUU);
            }

            tablesImported = stagedCount;
            sqliteConn.commit();
            completeOperation(operationId, "SUCCESS");

            log.info("Staged " + stagedCount + " table definitions to SQLite");
            log.info("Header UUID: " + headerUU);

            return headerUU;

        } finally {
            if (workbook != null) {
                Method close = workbook.getClass().getMethod("close");
                close.invoke(workbook);
            }
        }
    }

    /**
     * Parse columnar format (ModelMakerSource sheet)
     * Ninja HRMIS format: TRANSPOSED
     *   - Row 1: Table names in each column (HR_HumanResourceManager, HR_JobDesignation, etc.)
     *   - Row 2+: Column definitions for each table (one per row)
     */
    private int stageColumnarFormat(Object sheet, String headerUU) throws Exception {
        Class<?> sheetClass = sheet.getClass();
        Method getRow = sheetClass.getMethod("getRow", int.class);
        Method getLastRowNum = sheetClass.getMethod("getLastRowNum");

        int lastRow = (Integer) getLastRowNum.invoke(sheet);
        int staged = 0;

        // Row 0 may have metadata/references, Row 1 has table names
        Object tableNameRow = getRow.invoke(sheet, 1);
        if (tableNameRow == null) {
            log.warning("No table name row (row 1) in ModelMakerSource");
            return 0;
        }

        Class<?> rowClass = tableNameRow.getClass();
        Method getLastCellNum = rowClass.getMethod("getLastCellNum");
        Method getCell = rowClass.getMethod("getCell", int.class);

        int lastCol = (Short) getLastCellNum.invoke(tableNameRow);
        log.info("Found " + lastCol + " columns, " + lastRow + " rows");

        // Each column is a table, process each column
        for (int col = 0; col < lastCol; col++) {
            Object tableNameCell = getCell.invoke(tableNameRow, col);
            String tableName = tableNameCell != null ? getCellValue(tableNameCell) : "";
            if (tableName.isEmpty() || tableName.startsWith("=")) continue;  // Skip empty or formula cells

            // Collect column definitions from rows 2+
            StringBuilder columnSet = new StringBuilder();
            for (int row = 2; row <= lastRow; row++) {
                Object dataRow = getRow.invoke(sheet, row);
                if (dataRow == null) continue;

                Object colCell = getCell.invoke(dataRow, col);
                String colVal = colCell != null ? getCellValue(colCell) : "";
                // Skip empty, formula references (starting with =), and cell references (like A2, B3)
                if (!colVal.isEmpty() && !colVal.startsWith("=") && !colVal.matches("[A-Z]+\\d+")) {
                    // Clean up the column name
                    colVal = colVal.replace("\"", "").trim();
                    if (!colVal.isEmpty()) {
                        if (columnSet.length() > 0) columnSet.append(",");
                        columnSet.append(colVal);
                    }
                }
            }

            // Insert into RO_ModelMaker
            String makerUU = UUID.randomUUID().toString();
            String sql = "INSERT INTO RO_ModelMaker (RO_ModelMaker_UU, RO_ModelHeader_UU, SeqNo, Name, Master, ColumnSet, WorkflowStructure, KanbanBoard, operation_id, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'STAGED')";
            try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
                ps.setString(1, makerUU);
                ps.setString(2, headerUU);
                ps.setInt(3, staged + 1);
                ps.setString(4, tableName);
                ps.setString(5, "");  // Master relationship will be determined later
                ps.setString(6, columnSet.toString());
                ps.setString(7, "N");  // WorkflowStructure
                ps.setString(8, "N");  // KanbanBoard
                ps.setLong(9, operationId);
                ps.executeUpdate();
            }

            logDetail("RO_ModelMaker", tableName, "STAGED", "Columns: " + columnSet.toString().split(",").length);
            staged++;
            log.fine("Staged: " + tableName + " (" + columnSet.toString().split(",").length + " columns)");
        }

        return staged;
    }

    /**
     * Parse standard format (Model sheet with structured layout)
     */
    private int stageStandardFormat(Object sheet, String headerUU) throws Exception {
        // Similar to columnar but expects different structure
        // For now, delegate to columnar with adjusted expectations
        log.info("Standard format parsing - treating as columnar");
        return stageColumnarFormat(sheet, headerUU);
    }

    /**
     * Get cell value as string (reflection-based)
     */
    private String getCellValue(Object cell) {
        if (cell == null) return "";
        try {
            Method toString = cell.getClass().getMethod("toString");
            String val = (String) toString.invoke(cell);
            return val != null ? val.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ========== SHOW COMMAND (Display staged models) ==========

    /**
     * Show staged models in SQLite
     */
    public void showStaged() throws SQLException {
        System.out.println("=== Staged Model Bundles ===");

        String sql = "SELECT h.RO_ModelHeader_UU, h.Name, h.status, h.created_at, " +
                     "(SELECT COUNT(*) FROM RO_ModelMaker m WHERE m.RO_ModelHeader_UU = h.RO_ModelHeader_UU) as table_count " +
                     "FROM RO_ModelHeader h ORDER BY h.created_at DESC";
        try (Statement stmt = sqliteConn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                System.out.printf("  [%s] %s - %s (%d tables) - %s%n",
                    rs.getString("status"),
                    rs.getString("Name"),
                    rs.getString("RO_ModelHeader_UU").substring(0, 8),
                    rs.getInt("table_count"),
                    rs.getString("created_at"));
            }
        }

        System.out.println();
        System.out.println("=== Staged Table Definitions ===");

        sql = "SELECT m.Name, m.Master, m.ColumnSet, m.status, h.Name as BundleName " +
              "FROM RO_ModelMaker m JOIN RO_ModelHeader h ON m.RO_ModelHeader_UU = h.RO_ModelHeader_UU " +
              "WHERE m.status = 'STAGED' ORDER BY h.Name, m.SeqNo";
        try (Statement stmt = sqliteConn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            String currentBundle = null;
            while (rs.next()) {
                String bundle = rs.getString("BundleName");
                if (!bundle.equals(currentBundle)) {
                    System.out.println("\n  Bundle: " + bundle);
                    currentBundle = bundle;
                }
                String cols = rs.getString("ColumnSet");
                int colCount = cols.isEmpty() ? 0 : cols.split(",").length;
                System.out.printf("    - %s%s (%d columns)%n",
                    rs.getString("Name"),
                    rs.getString("Master").isEmpty() ? "" : " → " + rs.getString("Master"),
                    colCount);
            }
        }
        System.out.println();
    }

    /**
     * Show details for a specific staged bundle
     */
    public void showBundle(String bundleName) throws SQLException {
        System.out.println("=== Bundle: " + bundleName + " ===");

        String sql = "SELECT h.*, " +
                     "(SELECT COUNT(*) FROM RO_ModelMaker m WHERE m.RO_ModelHeader_UU = h.RO_ModelHeader_UU) as table_count " +
                     "FROM RO_ModelHeader h WHERE h.Name = ?";
        try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
            ps.setString(1, bundleName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    System.out.println("Bundle not found: " + bundleName);
                    return;
                }
                System.out.println("UUID: " + rs.getString("RO_ModelHeader_UU"));
                System.out.println("Status: " + rs.getString("status"));
                System.out.println("Tables: " + rs.getInt("table_count"));
                System.out.println("Created: " + rs.getString("created_at"));
            }
        }

        System.out.println("\nTable Definitions:");
        sql = "SELECT * FROM RO_ModelMaker WHERE RO_ModelHeader_UU = " +
              "(SELECT RO_ModelHeader_UU FROM RO_ModelHeader WHERE Name = ?) ORDER BY SeqNo";
        try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
            ps.setString(1, bundleName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    System.out.printf("  %d. %s%n", rs.getInt("SeqNo"), rs.getString("Name"));
                    if (!rs.getString("Master").isEmpty()) {
                        System.out.println("     Master: " + rs.getString("Master"));
                    }
                    String cols = rs.getString("ColumnSet");
                    if (!cols.isEmpty()) {
                        System.out.println("     Columns: " + cols);
                    }
                    if ("Y".equals(rs.getString("WorkflowStructure"))) {
                        System.out.println("     [Workflow: YES]");
                    }
                    if ("Y".equals(rs.getString("KanbanBoard"))) {
                        System.out.println("     [Kanban: YES]");
                    }
                }
            }
        }
    }

    // ========== APPLY COMMAND (SQLite → PostgreSQL) ==========

    /**
     * Apply staged models to iDempiere (RO_ → AD_)
     * @param bundleName Name of the bundle to apply
     */
    public boolean applyBundle(String bundleName) throws SQLException {
        if (ideConn == null) {
            log.severe("Not connected to iDempiere database");
            return false;
        }

        log.info("Applying bundle: " + bundleName);
        operationId = startOperation("APPLY", bundleName);

        // Find bundle
        String headerUU = null;
        String sql = "SELECT RO_ModelHeader_UU FROM RO_ModelHeader WHERE Name = ? AND status = 'STAGED'";
        try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
            ps.setString(1, bundleName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    log.warning("No staged bundle found: " + bundleName);
                    completeOperation(operationId, "FAILED");
                    return false;
                }
                headerUU = rs.getString(1);
            }
        }

        try {
            // Get staged models
            sql = "SELECT * FROM RO_ModelMaker WHERE RO_ModelHeader_UU = ? AND status = 'STAGED' ORDER BY SeqNo";
            List<Map<String, String>> models = new ArrayList<>();
            try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
                ps.setString(1, headerUU);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> model = new HashMap<>();
                        model.put("uuid", rs.getString("RO_ModelMaker_UU"));
                        model.put("name", rs.getString("Name"));
                        model.put("master", rs.getString("Master"));
                        model.put("columns", rs.getString("ColumnSet"));
                        model.put("wf", rs.getString("WorkflowStructure"));
                        model.put("kb", rs.getString("KanbanBoard"));
                        models.add(model);
                    }
                }
            }

            int applied = 0;
            for (Map<String, String> model : models) {
                String tableName = model.get("name");
                log.info("Applying table: " + tableName);

                // Check if table already exists in iDempiere
                int existingId = checkTableExists(tableName);
                if (existingId > 0) {
                    log.warning("Table already exists: " + tableName + " (AD_Table_ID=" + existingId + ")");
                    logDetail("AD_Table", tableName, "SKIP", "Already exists");
                    continue;
                }

                // Create AD_Table (simplified - actual implementation needs proper ID allocation)
                int tableId = createADTable(tableName, model);
                if (tableId > 0) {
                    // Update SQLite with applied status and AD_Table_ID
                    String updateSql = "UPDATE RO_ModelMaker SET status = 'APPLIED', ad_table_id = ? WHERE RO_ModelMaker_UU = ?";
                    try (PreparedStatement ps = sqliteConn.prepareStatement(updateSql)) {
                        ps.setInt(1, tableId);
                        ps.setString(2, model.get("uuid"));
                        ps.executeUpdate();
                    }
                    applied++;
                    tablesImported++;
                    logDetail("AD_Table", tableName, "CREATE", "AD_Table_ID=" + tableId);
                } else {
                    errors++;
                    logDetail("AD_Table", tableName, "ERROR", "Failed to create");
                }
            }

            // Update header status
            if (applied > 0) {
                sql = "UPDATE RO_ModelHeader SET status = 'APPLIED' WHERE RO_ModelHeader_UU = ?";
                try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
                    ps.setString(1, headerUU);
                    ps.executeUpdate();
                }
            }

            if (!dryRun) {
                ideConn.commit();
                sqliteConn.commit();
                completeOperation(operationId, "SUCCESS");
                log.info("Applied " + applied + " tables to iDempiere");
            } else {
                ideConn.rollback();
                completeOperation(operationId, "DRY_RUN");
                log.info("Dry run - rolled back");
            }

            return errors == 0;

        } catch (Exception e) {
            ideConn.rollback();
            completeOperation(operationId, "FAILED");
            throw new SQLException("Apply failed: " + e.getMessage(), e);
        }
    }

    private int checkTableExists(String tableName) throws SQLException {
        String sql = "SELECT AD_Table_ID FROM AD_Table WHERE UPPER(TableName) = UPPER(?)";
        try (PreparedStatement ps = ideConn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 0;
    }

    private int createADTable(String tableName, Map<String, String> model) throws SQLException {
        // Get next AD_Table_ID
        int tableId = getNextId("AD_Table");

        String sql = "INSERT INTO AD_Table (AD_Table_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy, Updated, UpdatedBy, " +
                     "TableName, Name, Description, AccessLevel, EntityType, IsDeleteable, IsHighVolume, IsView, " +
                     "ReplicationType, LoadSeq, IsSecurityEnabled, IsChangeLog, AD_Table_UU) " +
                     "VALUES (?, 0, 0, 'Y', NOW(), 0, NOW(), 0, ?, ?, ?, '3', 'U', 'Y', 'N', 'N', 'L', 0, 'N', 'Y', ?)";
        try (PreparedStatement ps = ideConn.prepareStatement(sql)) {
            ps.setInt(1, tableId);
            ps.setString(2, tableName);
            ps.setString(3, tableName);  // Name = TableName for now
            ps.setString(4, "Created by Ninja SilentPiper");
            ps.setString(5, UUID.randomUUID().toString());
            ps.executeUpdate();
        }

        // Create columns from ColumnSet
        String columns = model.get("columns");
        if (columns != null && !columns.isEmpty()) {
            int seqNo = 10;
            for (String colDef : columns.split(",")) {
                createADColumn(tableId, colDef.trim(), seqNo);
                seqNo += 10;
                columnsImported++;
            }
        }

        log.info("Created AD_Table: " + tableName + " (ID=" + tableId + ")");
        return tableId;
    }

    private void createADColumn(int tableId, String colDef, int seqNo) throws SQLException {
        // Parse column definition: Name:Type or just Name
        String colName = colDef;
        int refId = 10;  // Default: String
        if (colDef.contains(":")) {
            String[] parts = colDef.split(":");
            colName = parts[0];
            refId = parseColumnType(parts[1]);
        }

        int colId = getNextId("AD_Column");
        String sql = "INSERT INTO AD_Column (AD_Column_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy, Updated, UpdatedBy, " +
                     "AD_Table_ID, ColumnName, Name, AD_Reference_ID, SeqNo, IsKey, IsMandatory, IsIdentifier, " +
                     "EntityType, AD_Column_UU) " +
                     "VALUES (?, 0, 0, 'Y', NOW(), 0, NOW(), 0, ?, ?, ?, ?, ?, 'N', 'N', 'N', 'U', ?)";
        try (PreparedStatement ps = ideConn.prepareStatement(sql)) {
            ps.setInt(1, colId);
            ps.setInt(2, tableId);
            ps.setString(3, colName);
            ps.setString(4, colName);  // Name = ColumnName
            ps.setInt(5, refId);
            ps.setInt(6, seqNo);
            ps.setString(7, UUID.randomUUID().toString());
            ps.executeUpdate();
        }
    }

    private int parseColumnType(String type) {
        // Map common types to AD_Reference_ID
        switch (type.toLowerCase()) {
            case "string": case "text": return 10;
            case "number": case "integer": case "int": return 11;
            case "amount": case "decimal": return 12;
            case "date": return 15;
            case "datetime": return 16;
            case "list": return 17;
            case "table": return 18;
            case "yes-no": case "yesno": case "boolean": return 20;
            default: return 10;  // String
        }
    }

    private int getNextId(String tableName) throws SQLException {
        String sql = "SELECT COALESCE(MAX(" + tableName + "_ID), 999999) + 1 FROM " + tableName;
        try (Statement stmt = ideConn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        }
        return 1000000;
    }

    // ========== ROLLBACK COMMAND (Deactivate applied records) ==========

    /**
     * Rollback applied bundle (deactivate records in iDempiere)
     * @param bundleName Name of the bundle to rollback
     */
    public boolean rollbackBundle(String bundleName) throws SQLException {
        if (ideConn == null) {
            log.severe("Not connected to iDempiere database");
            return false;
        }

        log.info("Rolling back bundle: " + bundleName);
        operationId = startOperation("ROLLBACK", bundleName);

        // Find applied bundle
        String headerUU = null;
        String sql = "SELECT RO_ModelHeader_UU FROM RO_ModelHeader WHERE Name = ? AND status = 'APPLIED'";
        try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
            ps.setString(1, bundleName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    log.warning("No applied bundle found: " + bundleName);
                    completeOperation(operationId, "FAILED");
                    return false;
                }
                headerUU = rs.getString(1);
            }
        }

        try {
            // Get applied models with AD_Table_IDs
            sql = "SELECT * FROM RO_ModelMaker WHERE RO_ModelHeader_UU = ? AND status = 'APPLIED' ORDER BY SeqNo DESC";
            List<Map<String, Object>> models = new ArrayList<>();
            try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
                ps.setString(1, headerUU);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> model = new HashMap<>();
                        model.put("uuid", rs.getString("RO_ModelMaker_UU"));
                        model.put("name", rs.getString("Name"));
                        model.put("ad_table_id", rs.getInt("ad_table_id"));
                        models.add(model);
                    }
                }
            }

            int rolledBack = 0;
            for (Map<String, Object> model : models) {
                String tableName = (String) model.get("name");
                int tableId = (Integer) model.get("ad_table_id");

                if (tableId <= 0) {
                    log.fine("No AD_Table_ID for: " + tableName);
                    continue;
                }

                log.info("Deactivating table: " + tableName + " (ID=" + tableId + ")");

                // Deactivate AD_Columns first
                String deactivateCols = "UPDATE AD_Column SET IsActive = 'N', Updated = NOW() WHERE AD_Table_ID = ?";
                try (PreparedStatement ps = ideConn.prepareStatement(deactivateCols)) {
                    ps.setInt(1, tableId);
                    int cols = ps.executeUpdate();
                    log.fine("Deactivated " + cols + " columns");
                }

                // Deactivate AD_Table
                String deactivateTable = "UPDATE AD_Table SET IsActive = 'N', Updated = NOW() WHERE AD_Table_ID = ?";
                try (PreparedStatement ps = ideConn.prepareStatement(deactivateTable)) {
                    ps.setInt(1, tableId);
                    ps.executeUpdate();
                }

                // Update SQLite status
                String updateSql = "UPDATE RO_ModelMaker SET status = 'ROLLED_BACK' WHERE RO_ModelMaker_UU = ?";
                try (PreparedStatement ps = sqliteConn.prepareStatement(updateSql)) {
                    ps.setString(1, (String) model.get("uuid"));
                    ps.executeUpdate();
                }

                logDetail("AD_Table", tableName, "ROLLBACK", "Deactivated ID=" + tableId);
                rolledBack++;
            }

            // Update header status
            sql = "UPDATE RO_ModelHeader SET status = 'ROLLED_BACK' WHERE RO_ModelHeader_UU = ?";
            try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
                ps.setString(1, headerUU);
                ps.executeUpdate();
            }

            ideConn.commit();
            sqliteConn.commit();
            completeOperation(operationId, "SUCCESS");
            log.info("Rolled back " + rolledBack + " tables");

            return true;

        } catch (Exception e) {
            ideConn.rollback();
            completeOperation(operationId, "FAILED");
            throw new SQLException("Rollback failed: " + e.getMessage(), e);
        }
    }

    public void close() throws SQLException {
        if (ideConn != null) ideConn.close();
        if (sqliteConn != null) sqliteConn.close();
    }

    // ========== Main ==========

    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            return;
        }

        try {
            String sqliteDb = args[0];
            String command = args[1];

            SilentPiper piper = new SilentPiper(sqliteDb);

            switch (command.toLowerCase()) {
                // ===== STAGING WORKFLOW =====
                case "stage":
                    // Stage Excel to SQLite (no iDempiere connection needed)
                    if (args.length < 3) {
                        System.out.println("Missing Excel file path");
                        System.out.println("Usage: SilentPiper <sqlite.db> stage <excel.xlsx>");
                        return;
                    }
                    String headerUU = piper.stageExcel(args[2]);
                    System.out.println("Staged successfully. Header UUID: " + headerUU);
                    break;

                case "show":
                    // Show staged models
                    if (args.length > 2) {
                        piper.showBundle(args[2]);
                    } else {
                        piper.showStaged();
                    }
                    break;

                case "apply":
                    // Apply staged model to iDempiere
                    if (args.length < 3) {
                        System.out.println("Missing bundle name");
                        System.out.println("Usage: SilentPiper <sqlite.db> apply <bundle-name> [dryrun]");
                        return;
                    }
                    connectToIde(piper, args);
                    for (int i = 3; i < args.length; i++) {
                        if ("dryrun".equalsIgnoreCase(args[i])) piper.setDryRun(true);
                        if ("verbose".equalsIgnoreCase(args[i])) piper.setVerbose(true);
                    }
                    boolean applied = piper.applyBundle(args[2]);
                    System.out.println(applied ? "Apply successful" : "Apply failed");
                    break;

                case "rollback":
                    // Rollback applied bundle
                    if (args.length < 3) {
                        System.out.println("Missing bundle name");
                        System.out.println("Usage: SilentPiper <sqlite.db> rollback <bundle-name>");
                        return;
                    }
                    connectToIde(piper, args);
                    boolean rolledBack = piper.rollbackBundle(args[2]);
                    System.out.println(rolledBack ? "Rollback successful" : "Rollback failed");
                    break;

                // ===== LEGACY 2PACK COMMANDS =====
                case "import":
                case "validate":
                    if (args.length < 3) {
                        System.out.println("Missing 2pack file path");
                        return;
                    }
                    connectToIde(piper, args);
                    for (int i = 3; i < args.length; i++) {
                        if ("verbose".equalsIgnoreCase(args[i])) piper.setVerbose(true);
                        if ("dryrun".equalsIgnoreCase(args[i])) piper.setDryRun(true);
                    }
                    if ("validate".equals(command)) {
                        piper.validatePack(args[2]);
                    } else {
                        piper.importPack(args[2]);
                    }
                    break;

                case "history":
                    int limit = args.length > 2 ? Integer.parseInt(args[2]) : 20;
                    piper.printHistory(limit);
                    break;

                default:
                    System.out.println("Unknown command: " + command);
                    printUsage();
            }

            piper.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void connectToIde(SilentPiper piper, String[] args) throws Exception {
        String propsPath = System.getProperty("PropertyFile",
            "/home/red1/idempiere-dev-setup/idempiere/idempiere.properties");
        piper.connectFromProperties(propsPath);
    }

    private static void printUsage() {
        System.out.println("SilentPiper - Silent 2Pack/PIPO Handler with SQLite Staging");
        System.out.println();
        System.out.println("STAGING WORKFLOW (Excel → SQLite → iDempiere):");
        System.out.println("  SilentPiper <sqlite.db> stage <excel.xlsx>     - Parse Excel to SQLite");
        System.out.println("  SilentPiper <sqlite.db> show [bundle]          - Show staged models");
        System.out.println("  SilentPiper <sqlite.db> apply <bundle> [dryrun]- Apply to iDempiere");
        System.out.println("  SilentPiper <sqlite.db> rollback <bundle>      - Rollback applied bundle");
        System.out.println();
        System.out.println("2PACK COMMANDS:");
        System.out.println("  SilentPiper <sqlite.db> import <2pack.zip> [verbose] [dryrun]");
        System.out.println("  SilentPiper <sqlite.db> validate <2pack.zip>");
        System.out.println();
        System.out.println("HISTORY:");
        System.out.println("  SilentPiper <sqlite.db> history [limit]        - Show operation history");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  SilentPiper ninja.db stage HRMIS.xlsx");
        System.out.println("  SilentPiper ninja.db show");
        System.out.println("  SilentPiper ninja.db show HRMIS");
        System.out.println("  SilentPiper ninja.db apply HRMIS dryrun");
        System.out.println("  SilentPiper ninja.db apply HRMIS");
        System.out.println("  SilentPiper ninja.db rollback HRMIS");
    }
}
