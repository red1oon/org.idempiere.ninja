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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
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

            // ========== AD MODEL MIRROR (from iDempiere) ==========
            // Mirrored AD_Table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS AD_Table (" +
                "  AD_Table_ID INTEGER PRIMARY KEY," +
                "  AD_Table_UU TEXT UNIQUE," +
                "  TableName TEXT NOT NULL," +
                "  Name TEXT," +
                "  Description TEXT," +
                "  AccessLevel TEXT," +
                "  EntityType TEXT," +
                "  IsActive TEXT DEFAULT 'Y'," +
                "  synced_at TEXT" +
                ")"
            );
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_ad_table_name ON AD_Table(TableName)");

            // Mirrored AD_Column
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS AD_Column (" +
                "  AD_Column_ID INTEGER PRIMARY KEY," +
                "  AD_Column_UU TEXT UNIQUE," +
                "  AD_Table_ID INTEGER," +
                "  ColumnName TEXT NOT NULL," +
                "  Name TEXT," +
                "  Description TEXT," +
                "  AD_Reference_ID INTEGER," +
                "  AD_Reference_Value_ID INTEGER," +
                "  FieldLength INTEGER," +
                "  IsMandatory TEXT DEFAULT 'N'," +
                "  IsKey TEXT DEFAULT 'N'," +
                "  IsIdentifier TEXT DEFAULT 'N'," +
                "  SeqNo INTEGER," +
                "  DefaultValue TEXT," +
                "  IsActive TEXT DEFAULT 'Y'," +
                "  synced_at TEXT," +
                "  FOREIGN KEY (AD_Table_ID) REFERENCES AD_Table(AD_Table_ID)" +
                ")"
            );
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_ad_column_table ON AD_Column(AD_Table_ID)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_ad_column_name ON AD_Column(ColumnName)");

            // Mirrored AD_Reference (for validation)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS AD_Reference (" +
                "  AD_Reference_ID INTEGER PRIMARY KEY," +
                "  AD_Reference_UU TEXT UNIQUE," +
                "  Name TEXT NOT NULL," +
                "  ValidationType TEXT," +
                "  IsActive TEXT DEFAULT 'Y'," +
                "  synced_at TEXT" +
                ")"
            );

            // Mirrored AD_Ref_List (for list validation)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS AD_Ref_List (" +
                "  AD_Ref_List_ID INTEGER PRIMARY KEY," +
                "  AD_Ref_List_UU TEXT UNIQUE," +
                "  AD_Reference_ID INTEGER," +
                "  Value TEXT NOT NULL," +
                "  Name TEXT," +
                "  IsActive TEXT DEFAULT 'Y'," +
                "  synced_at TEXT," +
                "  FOREIGN KEY (AD_Reference_ID) REFERENCES AD_Reference(AD_Reference_ID)" +
                ")"
            );

            // Mirrored AD_Client (for test data)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS AD_Client (" +
                "  AD_Client_ID INTEGER PRIMARY KEY," +
                "  AD_Client_UU TEXT UNIQUE," +
                "  Value TEXT," +
                "  Name TEXT NOT NULL," +
                "  IsActive TEXT DEFAULT 'Y'," +
                "  synced_at TEXT" +
                ")"
            );

            // Mirrored AD_Org
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS AD_Org (" +
                "  AD_Org_ID INTEGER PRIMARY KEY," +
                "  AD_Org_UU TEXT UNIQUE," +
                "  AD_Client_ID INTEGER," +
                "  Value TEXT," +
                "  Name TEXT NOT NULL," +
                "  IsActive TEXT DEFAULT 'Y'," +
                "  synced_at TEXT," +
                "  FOREIGN KEY (AD_Client_ID) REFERENCES AD_Client(AD_Client_ID)" +
                ")"
            );

            // ========== SQL-TO-2PACK STAGING TABLES ==========
            // Pack header for SQL data bundles
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS pack_header (" +
                "  pack_header_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  pack_header_uu TEXT UNIQUE," +
                "  name TEXT NOT NULL," +
                "  version TEXT DEFAULT '1.0.0'," +
                "  source_file TEXT," +
                "  ad_client_id INTEGER DEFAULT 0," +
                "  ad_org_id INTEGER DEFAULT 0," +
                "  operation_id INTEGER," +
                "  status TEXT DEFAULT 'STAGED'," +
                "  created_at TEXT DEFAULT CURRENT_TIMESTAMP" +
                ")"
            );

            // Staged data records from SQL
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS pack_data (" +
                "  pack_data_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  pack_data_uu TEXT UNIQUE," +
                "  pack_header_uu TEXT," +
                "  table_name TEXT NOT NULL," +
                "  record_uu TEXT," +
                "  record_id INTEGER," +
                "  column_data TEXT," +  // JSON: {col1: val1, col2: val2, ...}
                "  seq_no INTEGER," +
                "  status TEXT DEFAULT 'STAGED'," +
                "  created_at TEXT DEFAULT CURRENT_TIMESTAMP," +
                "  FOREIGN KEY (pack_header_uu) REFERENCES pack_header(pack_header_uu)" +
                ")"
            );

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pack_data_header ON pack_data(pack_header_uu)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pack_data_table ON pack_data(table_name)");

            // ========== APPLIED RECORDS TRACKING (for cleanup) ==========
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS applied_records (" +
                "  applied_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  pack_header_uu TEXT," +
                "  table_name TEXT NOT NULL," +
                "  record_id INTEGER NOT NULL," +
                "  record_uu TEXT," +
                "  pk_column TEXT," +  // Primary key column name
                "  seq_no INTEGER," +   // Insert order (for reverse delete)
                "  applied_at TEXT DEFAULT CURRENT_TIMESTAMP," +
                "  status TEXT DEFAULT 'APPLIED'," +  // APPLIED, DELETED
                "  FOREIGN KEY (pack_header_uu) REFERENCES pack_header(pack_header_uu)" +
                ")"
            );
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_applied_pack ON applied_records(pack_header_uu)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_applied_table ON applied_records(table_name)");
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
            // First pass: look for PackOut.xml in standard PIPO structure (*/dict/PackOut.xml)
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase();
                if (name.endsWith("/dict/packout.xml") || name.equals("dict/packout.xml")) {
                    log.info("Processing: " + entry.getName());
                    try (InputStream is = zip.getInputStream(entry)) {
                        return importXml(is);
                    }
                }
            }
            // Second pass: look for any XML at root
            entries = zip.entries();
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

    // ========== SYNC-AD: Pull AD Model from PG to SQLite ==========

    /**
     * Sync AD model from iDempiere PostgreSQL to SQLite
     * This creates the local mirror for validation and browsing
     */
    public void syncAD() throws SQLException {
        if (ideConn == null) {
            throw new SQLException("Not connected to iDempiere database");
        }

        log.info("Syncing AD model from iDempiere to SQLite...");
        operationId = startOperation("SYNC_AD", "iDempiere → SQLite");
        String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());

        int tables = 0, columns = 0, refs = 0, clients = 0;

        try {
            // Sync AD_Table
            String sql = "SELECT AD_Table_ID, AD_Table_UU, TableName, Name, Description, AccessLevel, EntityType, IsActive FROM AD_Table WHERE IsActive='Y'";
            try (Statement stmt = ideConn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                String insert = "INSERT OR REPLACE INTO AD_Table (AD_Table_ID, AD_Table_UU, TableName, Name, Description, AccessLevel, EntityType, IsActive, synced_at) VALUES (?,?,?,?,?,?,?,?,?)";
                try (PreparedStatement ps = sqliteConn.prepareStatement(insert)) {
                    while (rs.next()) {
                        ps.setInt(1, rs.getInt("AD_Table_ID"));
                        ps.setString(2, rs.getString("AD_Table_UU"));
                        ps.setString(3, rs.getString("TableName"));
                        ps.setString(4, rs.getString("Name"));
                        ps.setString(5, rs.getString("Description"));
                        ps.setString(6, rs.getString("AccessLevel"));
                        ps.setString(7, rs.getString("EntityType"));
                        ps.setString(8, rs.getString("IsActive"));
                        ps.setString(9, timestamp);
                        ps.executeUpdate();
                        tables++;
                    }
                }
            }
            log.info("Synced " + tables + " tables");

            // Sync AD_Column
            sql = "SELECT AD_Column_ID, AD_Column_UU, AD_Table_ID, ColumnName, Name, Description, AD_Reference_ID, AD_Reference_Value_ID, FieldLength, IsMandatory, IsKey, IsIdentifier, SeqNo, DefaultValue, IsActive FROM AD_Column WHERE IsActive='Y'";
            try (Statement stmt = ideConn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                String insert = "INSERT OR REPLACE INTO AD_Column (AD_Column_ID, AD_Column_UU, AD_Table_ID, ColumnName, Name, Description, AD_Reference_ID, AD_Reference_Value_ID, FieldLength, IsMandatory, IsKey, IsIdentifier, SeqNo, DefaultValue, IsActive, synced_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
                try (PreparedStatement ps = sqliteConn.prepareStatement(insert)) {
                    while (rs.next()) {
                        ps.setInt(1, rs.getInt("AD_Column_ID"));
                        ps.setString(2, rs.getString("AD_Column_UU"));
                        ps.setInt(3, rs.getInt("AD_Table_ID"));
                        ps.setString(4, rs.getString("ColumnName"));
                        ps.setString(5, rs.getString("Name"));
                        ps.setString(6, rs.getString("Description"));
                        ps.setInt(7, rs.getInt("AD_Reference_ID"));
                        ps.setInt(8, rs.getInt("AD_Reference_Value_ID"));
                        ps.setInt(9, rs.getInt("FieldLength"));
                        ps.setString(10, rs.getString("IsMandatory"));
                        ps.setString(11, rs.getString("IsKey"));
                        ps.setString(12, rs.getString("IsIdentifier"));
                        ps.setInt(13, rs.getInt("SeqNo"));
                        ps.setString(14, rs.getString("DefaultValue"));
                        ps.setString(15, rs.getString("IsActive"));
                        ps.setString(16, timestamp);
                        ps.executeUpdate();
                        columns++;
                    }
                }
            }
            log.info("Synced " + columns + " columns");

            // Sync AD_Reference
            sql = "SELECT AD_Reference_ID, AD_Reference_UU, Name, ValidationType, IsActive FROM AD_Reference WHERE IsActive='Y'";
            try (Statement stmt = ideConn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                String insert = "INSERT OR REPLACE INTO AD_Reference (AD_Reference_ID, AD_Reference_UU, Name, ValidationType, IsActive, synced_at) VALUES (?,?,?,?,?,?)";
                try (PreparedStatement ps = sqliteConn.prepareStatement(insert)) {
                    while (rs.next()) {
                        ps.setInt(1, rs.getInt("AD_Reference_ID"));
                        ps.setString(2, rs.getString("AD_Reference_UU"));
                        ps.setString(3, rs.getString("Name"));
                        ps.setString(4, rs.getString("ValidationType"));
                        ps.setString(5, rs.getString("IsActive"));
                        ps.setString(6, timestamp);
                        ps.executeUpdate();
                        refs++;
                    }
                }
            }
            log.info("Synced " + refs + " references");

            // Sync AD_Client
            sql = "SELECT AD_Client_ID, AD_Client_UU, Value, Name, IsActive FROM AD_Client";
            try (Statement stmt = ideConn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                String insert = "INSERT OR REPLACE INTO AD_Client (AD_Client_ID, AD_Client_UU, Value, Name, IsActive, synced_at) VALUES (?,?,?,?,?,?)";
                try (PreparedStatement ps = sqliteConn.prepareStatement(insert)) {
                    while (rs.next()) {
                        ps.setInt(1, rs.getInt("AD_Client_ID"));
                        ps.setString(2, rs.getString("AD_Client_UU"));
                        ps.setString(3, rs.getString("Value"));
                        ps.setString(4, rs.getString("Name"));
                        ps.setString(5, rs.getString("IsActive"));
                        ps.setString(6, timestamp);
                        ps.executeUpdate();
                        clients++;
                    }
                }
            }
            log.info("Synced " + clients + " clients");

            // Sync AD_Org
            sql = "SELECT AD_Org_ID, AD_Org_UU, AD_Client_ID, Value, Name, IsActive FROM AD_Org";
            try (Statement stmt = ideConn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                String insert = "INSERT OR REPLACE INTO AD_Org (AD_Org_ID, AD_Org_UU, AD_Client_ID, Value, Name, IsActive, synced_at) VALUES (?,?,?,?,?,?,?)";
                try (PreparedStatement ps = sqliteConn.prepareStatement(insert)) {
                    while (rs.next()) {
                        ps.setInt(1, rs.getInt("AD_Org_ID"));
                        ps.setString(2, rs.getString("AD_Org_UU"));
                        ps.setInt(3, rs.getInt("AD_Client_ID"));
                        ps.setString(4, rs.getString("Value"));
                        ps.setString(5, rs.getString("Name"));
                        ps.setString(6, rs.getString("IsActive"));
                        ps.setString(7, timestamp);
                        ps.executeUpdate();
                    }
                }
            }

            sqliteConn.commit();
            tablesImported = tables;
            columnsImported = columns;
            completeOperation(operationId, "SUCCESS");

            log.info("=== Sync Complete ===");
            log.info("Tables: " + tables + ", Columns: " + columns + ", References: " + refs + ", Clients: " + clients);

        } catch (Exception e) {
            completeOperation(operationId, "FAILED");
            throw new SQLException("Sync failed: " + e.getMessage(), e);
        }
    }

    /**
     * Show AD model info from SQLite
     */
    public void showADInfo() throws SQLException {
        System.out.println("=== AD Model in SQLite ===");

        String sql = "SELECT COUNT(*) as cnt, MAX(synced_at) as last_sync FROM AD_Table";
        try (Statement stmt = sqliteConn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                System.out.println("Tables: " + rs.getInt("cnt") + " (last sync: " + rs.getString("last_sync") + ")");
            }
        }

        sql = "SELECT COUNT(*) as cnt FROM AD_Column";
        try (Statement stmt = sqliteConn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) System.out.println("Columns: " + rs.getInt("cnt"));
        }

        sql = "SELECT COUNT(*) as cnt FROM AD_Reference";
        try (Statement stmt = sqliteConn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) System.out.println("References: " + rs.getInt("cnt"));
        }

        sql = "SELECT AD_Client_ID, Name FROM AD_Client ORDER BY AD_Client_ID";
        try (Statement stmt = sqliteConn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            System.out.println("\nClients:");
            while (rs.next()) {
                System.out.println("  " + rs.getInt("AD_Client_ID") + ": " + rs.getString("Name"));
            }
        }

        // Show recent tables (MP_ for AssetMaintenance)
        sql = "SELECT TableName, Name FROM AD_Table WHERE TableName LIKE 'MP_%' OR TableName LIKE 'A_Asset%' ORDER BY TableName";
        try (Statement stmt = sqliteConn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            System.out.println("\nAssetMaintenance Tables:");
            while (rs.next()) {
                System.out.println("  " + rs.getString("TableName") + " - " + rs.getString("Name"));
            }
        }
    }

    // ========== SQL2PACK: Parse SQL and Stage Data ==========

    /**
     * Parse SQL file and stage INSERT statements in SQLite
     */
    public String sql2pack(String sqlPath) throws Exception {
        File file = new File(sqlPath);
        if (!file.exists()) {
            throw new Exception("SQL file not found: " + sqlPath);
        }

        log.info("Parsing SQL: " + sqlPath);
        operationId = startOperation("SQL2PACK", sqlPath);

        // Create pack header
        String packUU = UUID.randomUUID().toString();
        String packName = file.getName().replace(".sql", "");

        String sql = "INSERT INTO pack_header (pack_header_uu, name, source_file, operation_id, status) VALUES (?, ?, ?, ?, 'STAGED')";
        try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
            ps.setString(1, packUU);
            ps.setString(2, packName);
            ps.setString(3, sqlPath);
            ps.setLong(4, operationId);
            ps.executeUpdate();
        }

        // Read and parse SQL file
        String content = new String(java.nio.file.Files.readAllBytes(file.toPath()));
        int recordCount = parseSqlInserts(content, packUU);

        sqliteConn.commit();
        tablesImported = recordCount;
        completeOperation(operationId, "SUCCESS");

        log.info("Staged " + recordCount + " records from SQL");
        log.info("Pack UUID: " + packUU);

        return packUU;
    }

    /**
     * Parse INSERT statements from SQL content
     */
    private int parseSqlInserts(String content, String packUU) throws SQLException {
        int seqNo = 0;

        // Normalize content - remove comments and join lines
        content = content.replaceAll("--[^\n]*\n", "\n");  // Remove line comments
        content = content.replaceAll("/\\*.*?\\*/", "");   // Remove block comments
        content = content.replaceAll("\\s+", " ");         // Normalize whitespace

        // Regex to match INSERT INTO table (cols) VALUES (...), (...);
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "INSERT\\s+INTO\\s+(\\w+)\\s*\\(([^)]+)\\)\\s*VALUES\\s*([^;]+);",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );

        java.util.regex.Matcher matcher = pattern.matcher(content);
        String insertSql = "INSERT INTO pack_data (pack_data_uu, pack_header_uu, table_name, record_id, column_data, seq_no, status) VALUES (?, ?, ?, ?, ?, ?, 'STAGED')";

        try (PreparedStatement ps = sqliteConn.prepareStatement(insertSql)) {
            while (matcher.find()) {
                String tableName = matcher.group(1).toLowerCase();
                String columnsPart = matcher.group(2);
                String valuesPart = matcher.group(3).trim();

                // Skip DELETE statements captured accidentally
                if (tableName.equals("delete")) continue;

                // Parse columns
                String[] columns = columnsPart.split(",");
                for (int i = 0; i < columns.length; i++) {
                    columns[i] = columns[i].trim().toLowerCase();
                }

                // Parse multiple value sets: (val1, val2), (val1, val2)
                List<String> valueSets = splitValueSets(valuesPart);

                for (String valueSet : valueSets) {
                    String[] values = parseValues(valueSet);

                    // Build JSON column data
                    StringBuilder json = new StringBuilder("{");
                    int recordId = 0;
                    for (int i = 0; i < Math.min(columns.length, values.length); i++) {
                        if (i > 0) json.append(",");
                        String col = columns[i];
                        String val = values[i].trim();
                        // Clean value
                        val = val.replaceAll("^'|'$", "");  // Remove quotes
                        json.append("\"").append(col).append("\":\"").append(escapeJson(val)).append("\"");

                        // Extract record ID if present
                        if (col.endsWith("_id") && col.equals(tableName + "_id")) {
                            try {
                                recordId = Integer.parseInt(val);
                            } catch (NumberFormatException e) { /* ignore */ }
                        }
                    }
                    json.append("}");

                    seqNo++;
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, packUU);
                    ps.setString(3, tableName);
                    ps.setInt(4, recordId);
                    ps.setString(5, json.toString());
                    ps.setInt(6, seqNo);
                    ps.executeUpdate();

                    logDetail(tableName, String.valueOf(recordId), "STAGED", "From SQL");
                }
            }
        }

        return seqNo;
    }

    /**
     * Split VALUES part into individual value sets: (a,b), (c,d) → ["a,b", "c,d"]
     */
    private List<String> splitValueSets(String valuesPart) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = -1;

        for (int i = 0; i < valuesPart.length(); i++) {
            char c = valuesPart.charAt(i);
            if (c == '(') {
                if (depth == 0) start = i + 1;
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0 && start >= 0) {
                    result.add(valuesPart.substring(start, i));
                    start = -1;
                }
            }
        }

        return result;
    }

    /**
     * Parse SQL VALUES, handling quoted strings with commas
     */
    private String[] parseValues(String valuesPart) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        boolean inFunc = false;
        int parenDepth = 0;

        for (char c : valuesPart.toCharArray()) {
            if (c == '\'' && !inFunc) {
                inQuote = !inQuote;
                current.append(c);
            } else if (c == '(' && !inQuote) {
                parenDepth++;
                inFunc = true;
                current.append(c);
            } else if (c == ')' && !inQuote) {
                parenDepth--;
                if (parenDepth == 0) inFunc = false;
                current.append(c);
            } else if (c == ',' && !inQuote && !inFunc) {
                values.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            values.add(current.toString().trim());
        }

        return values.toArray(new String[0]);
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * Show staged pack data
     */
    public void showPacks() throws SQLException {
        System.out.println("=== Staged Data Packs ===");

        String sql = "SELECT p.pack_header_uu, p.name, p.source_file, p.status, p.created_at, " +
                     "(SELECT COUNT(*) FROM pack_data d WHERE d.pack_header_uu = p.pack_header_uu) as record_count " +
                     "FROM pack_header p ORDER BY p.created_at DESC";
        try (Statement stmt = sqliteConn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                System.out.printf("  [%s] %s - %d records - %s%n",
                    rs.getString("status"),
                    rs.getString("name"),
                    rs.getInt("record_count"),
                    rs.getString("created_at"));
            }
        }
    }

    /**
     * Show pack details
     */
    public void showPackDetail(String packName) throws SQLException {
        System.out.println("=== Pack: " + packName + " ===");

        // Find pack
        String sql = "SELECT * FROM pack_header WHERE name = ?";
        String packUU = null;
        try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
            ps.setString(1, packName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    System.out.println("Pack not found: " + packName);
                    return;
                }
                packUU = rs.getString("pack_header_uu");
                System.out.println("UUID: " + packUU);
                System.out.println("Source: " + rs.getString("source_file"));
                System.out.println("Status: " + rs.getString("status"));
            }
        }

        // Group by table
        sql = "SELECT table_name, COUNT(*) as cnt FROM pack_data WHERE pack_header_uu = ? GROUP BY table_name ORDER BY MIN(seq_no)";
        try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
            ps.setString(1, packUU);
            try (ResultSet rs = ps.executeQuery()) {
                System.out.println("\nRecords by table:");
                while (rs.next()) {
                    System.out.println("  " + rs.getString("table_name") + ": " + rs.getInt("cnt"));
                }
            }
        }
    }

    // ========== EXPORT-2PACK: Generate 2Pack.zip from SQLite ==========

    /**
     * Export staged pack data to 2Pack.zip
     */
    public File export2Pack(String packName, String outputDir) throws Exception {
        log.info("Exporting 2Pack: " + packName);

        // Find pack
        String sql = "SELECT * FROM pack_header WHERE name = ?";
        String packUU = null;
        try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
            ps.setString(1, packName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new Exception("Pack not found: " + packName);
                }
                packUU = rs.getString("pack_header_uu");
            }
        }

        // Generate XML
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<idempiere Name=\"").append(packName).append("\" Version=\"1.0.0\">\n");

        // Get data records grouped by table
        sql = "SELECT table_name, column_data FROM pack_data WHERE pack_header_uu = ? ORDER BY table_name, seq_no";
        try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
            ps.setString(1, packUU);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("table_name");
                    String columnData = rs.getString("column_data");

                    xml.append("  <").append(tableName).append(" action=\"insert\">\n");
                    // Parse JSON and output as XML elements
                    Map<String, String> cols = parseJsonColumns(columnData);
                    for (Map.Entry<String, String> entry : cols.entrySet()) {
                        xml.append("    <").append(entry.getKey()).append(">")
                           .append(escapeXml(entry.getValue()))
                           .append("</").append(entry.getKey()).append(">\n");
                    }
                    xml.append("  </").append(tableName).append(">\n");
                }
            }
        }

        xml.append("</idempiere>\n");

        // Create zip file
        File outDir = new File(outputDir != null ? outputDir : ".");
        File zipFile = new File(outDir, packName + "_2Pack.zip");

        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(zipFile))) {
            zos.putNextEntry(new java.util.zip.ZipEntry("PackOut.xml"));
            zos.write(xml.toString().getBytes("UTF-8"));
            zos.closeEntry();
        }

        log.info("Created: " + zipFile.getAbsolutePath());
        return zipFile;
    }

    // ========== PACKOUT: Export AD Model from PostgreSQL to 2Pack ==========

    /**
     * Export AD model definitions (tables, columns, windows, tabs, fields) to 2Pack.zip
     * @param tablePrefix Table name prefix (e.g., "MP_" for AssetMaintenance)
     * @param outputDir Output directory
     * @return Created zip file
     */
    public File packout(String tablePrefix, String outputDir) throws Exception {
        if (ideConn == null) {
            throw new SQLException("Not connected to iDempiere database");
        }

        log.info("PackOut: Exporting AD model for prefix: " + tablePrefix);

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<idempiere>\n");

        // Package header
        xml.append("  <adempierePackage>\n");
        xml.append("    <Name>").append(tablePrefix).append("Model</Name>\n");
        xml.append("    <Version>1.0.0</Version>\n");
        xml.append("    <Client>System</Client>\n");
        xml.append("  </adempierePackage>\n\n");

        // Export AD_Table
        String sql = "SELECT * FROM ad_table WHERE tablename LIKE ? ORDER BY tablename";
        try (PreparedStatement ps = ideConn.prepareStatement(sql)) {
            ps.setString(1, tablePrefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    exportTable(xml, rs);
                }
            }
        }

        // Export AD_Window containing these tables
        sql = "SELECT DISTINCT w.* FROM ad_window w " +
              "JOIN ad_tab t ON w.ad_window_id = t.ad_window_id " +
              "JOIN ad_table tbl ON t.ad_table_id = tbl.ad_table_id " +
              "WHERE tbl.tablename LIKE ? ORDER BY w.name";
        try (PreparedStatement ps = ideConn.prepareStatement(sql)) {
            ps.setString(1, tablePrefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    exportWindow(xml, rs);
                }
            }
        }

        // Export AD_Menu
        sql = "SELECT * FROM ad_menu WHERE name LIKE ? OR name LIKE ? ORDER BY name";
        try (PreparedStatement ps = ideConn.prepareStatement(sql)) {
            ps.setString(1, tablePrefix + "%");
            ps.setString(2, "%" + tablePrefix.replace("_", " ") + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    exportMenu(xml, rs);
                }
            }
        }

        xml.append("</idempiere>\n");

        // Create zip file
        File outDirFile = new File(outputDir != null ? outputDir : ".");
        if (!outDirFile.exists()) {
            outDirFile.mkdirs();
        }
        File zipFile = new File(outDirFile, tablePrefix + "Model_2Pack.zip");

        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(zipFile))) {
            zos.putNextEntry(new java.util.zip.ZipEntry("PackOut.xml"));
            zos.write(xml.toString().getBytes("UTF-8"));
            zos.closeEntry();
        }

        log.info("PackOut created: " + zipFile.getAbsolutePath());
        return zipFile;
    }

    private void exportTable(StringBuilder xml, ResultSet rs) throws SQLException {
        String tableName = rs.getString("tablename");
        xml.append("  <AD_Table>\n");
        xml.append("    <AD_Table_ID>").append(rs.getInt("ad_table_id")).append("</AD_Table_ID>\n");
        xml.append("    <AD_Table_UU>").append(rs.getString("ad_table_uu")).append("</AD_Table_UU>\n");
        xml.append("    <TableName>").append(tableName).append("</TableName>\n");
        xml.append("    <Name>").append(escapeXml(rs.getString("name"))).append("</Name>\n");
        xml.append("    <AccessLevel>").append(rs.getString("accesslevel")).append("</AccessLevel>\n");
        xml.append("    <EntityType>").append(rs.getString("entitytype")).append("</EntityType>\n");
        xml.append("    <IsActive>").append(rs.getString("isactive")).append("</IsActive>\n");

        // Export columns for this table
        String colSql = "SELECT * FROM ad_column WHERE ad_table_id = ? ORDER BY columnname";
        try (PreparedStatement ps = ideConn.prepareStatement(colSql)) {
            ps.setInt(1, rs.getInt("ad_table_id"));
            try (ResultSet colRs = ps.executeQuery()) {
                while (colRs.next()) {
                    exportColumn(xml, colRs);
                }
            }
        }

        xml.append("  </AD_Table>\n\n");
    }

    private void exportColumn(StringBuilder xml, ResultSet rs) throws SQLException {
        xml.append("    <AD_Column>\n");
        xml.append("      <AD_Column_ID>").append(rs.getInt("ad_column_id")).append("</AD_Column_ID>\n");
        xml.append("      <AD_Column_UU>").append(rs.getString("ad_column_uu")).append("</AD_Column_UU>\n");
        xml.append("      <ColumnName>").append(rs.getString("columnname")).append("</ColumnName>\n");
        xml.append("      <Name>").append(escapeXml(rs.getString("name"))).append("</Name>\n");
        xml.append("      <AD_Reference_ID>").append(rs.getInt("ad_reference_id")).append("</AD_Reference_ID>\n");
        xml.append("      <FieldLength>").append(rs.getInt("fieldlength")).append("</FieldLength>\n");
        xml.append("      <IsKey>").append(rs.getString("iskey")).append("</IsKey>\n");
        xml.append("      <IsParent>").append(rs.getString("isparent")).append("</IsParent>\n");
        xml.append("      <IsMandatory>").append(rs.getString("ismandatory")).append("</IsMandatory>\n");
        xml.append("      <IsActive>").append(rs.getString("isactive")).append("</IsActive>\n");
        xml.append("      <EntityType>").append(rs.getString("entitytype")).append("</EntityType>\n");
        xml.append("    </AD_Column>\n");
    }

    private void exportWindow(StringBuilder xml, ResultSet rs) throws SQLException {
        int windowId = rs.getInt("ad_window_id");
        xml.append("  <AD_Window>\n");
        xml.append("    <AD_Window_ID>").append(windowId).append("</AD_Window_ID>\n");
        xml.append("    <AD_Window_UU>").append(rs.getString("ad_window_uu")).append("</AD_Window_UU>\n");
        xml.append("    <Name>").append(escapeXml(rs.getString("name"))).append("</Name>\n");
        xml.append("    <WindowType>").append(rs.getString("windowtype")).append("</WindowType>\n");
        xml.append("    <IsActive>").append(rs.getString("isactive")).append("</IsActive>\n");
        xml.append("    <EntityType>").append(rs.getString("entitytype")).append("</EntityType>\n");

        // Export tabs for this window
        String tabSql = "SELECT * FROM ad_tab WHERE ad_window_id = ? ORDER BY seqno";
        try (PreparedStatement ps = ideConn.prepareStatement(tabSql)) {
            ps.setInt(1, windowId);
            try (ResultSet tabRs = ps.executeQuery()) {
                while (tabRs.next()) {
                    exportTab(xml, tabRs);
                }
            }
        }

        xml.append("  </AD_Window>\n\n");
    }

    private void exportTab(StringBuilder xml, ResultSet rs) throws SQLException {
        int tabId = rs.getInt("ad_tab_id");
        xml.append("    <AD_Tab>\n");
        xml.append("      <AD_Tab_ID>").append(tabId).append("</AD_Tab_ID>\n");
        xml.append("      <AD_Tab_UU>").append(rs.getString("ad_tab_uu")).append("</AD_Tab_UU>\n");
        xml.append("      <Name>").append(escapeXml(rs.getString("name"))).append("</Name>\n");
        xml.append("      <AD_Table_ID>").append(rs.getInt("ad_table_id")).append("</AD_Table_ID>\n");
        xml.append("      <TabLevel>").append(rs.getInt("tablevel")).append("</TabLevel>\n");
        xml.append("      <SeqNo>").append(rs.getInt("seqno")).append("</SeqNo>\n");

        // The important link column fix
        int adColumnId = rs.getInt("ad_column_id");
        if (adColumnId > 0) {
            xml.append("      <AD_Column_ID>").append(adColumnId).append("</AD_Column_ID>\n");
        }

        int parentColumnId = rs.getInt("parent_column_id");
        if (parentColumnId > 0) {
            xml.append("      <Parent_Column_ID>").append(parentColumnId).append("</Parent_Column_ID>\n");
        }

        xml.append("      <IsActive>").append(rs.getString("isactive")).append("</IsActive>\n");
        xml.append("      <EntityType>").append(rs.getString("entitytype")).append("</EntityType>\n");

        // Export fields for this tab
        String fieldSql = "SELECT * FROM ad_field WHERE ad_tab_id = ? ORDER BY seqno";
        try (PreparedStatement ps = ideConn.prepareStatement(fieldSql)) {
            ps.setInt(1, tabId);
            try (ResultSet fieldRs = ps.executeQuery()) {
                while (fieldRs.next()) {
                    exportField(xml, fieldRs);
                }
            }
        }

        xml.append("    </AD_Tab>\n");
    }

    private void exportField(StringBuilder xml, ResultSet rs) throws SQLException {
        xml.append("      <AD_Field>\n");
        xml.append("        <AD_Field_ID>").append(rs.getInt("ad_field_id")).append("</AD_Field_ID>\n");
        xml.append("        <AD_Field_UU>").append(rs.getString("ad_field_uu")).append("</AD_Field_UU>\n");
        xml.append("        <Name>").append(escapeXml(rs.getString("name"))).append("</Name>\n");
        xml.append("        <AD_Column_ID>").append(rs.getInt("ad_column_id")).append("</AD_Column_ID>\n");
        xml.append("        <SeqNo>").append(rs.getInt("seqno")).append("</SeqNo>\n");
        xml.append("        <IsDisplayed>").append(rs.getString("isdisplayed")).append("</IsDisplayed>\n");
        xml.append("        <IsActive>").append(rs.getString("isactive")).append("</IsActive>\n");
        xml.append("        <EntityType>").append(rs.getString("entitytype")).append("</EntityType>\n");
        xml.append("      </AD_Field>\n");
    }

    private void exportMenu(StringBuilder xml, ResultSet rs) throws SQLException {
        xml.append("  <AD_Menu>\n");
        xml.append("    <AD_Menu_ID>").append(rs.getInt("ad_menu_id")).append("</AD_Menu_ID>\n");
        xml.append("    <AD_Menu_UU>").append(rs.getString("ad_menu_uu")).append("</AD_Menu_UU>\n");
        xml.append("    <Name>").append(escapeXml(rs.getString("name"))).append("</Name>\n");
        xml.append("    <Action>").append(rs.getString("action") != null ? rs.getString("action") : "").append("</Action>\n");
        xml.append("    <AD_Window_ID>").append(rs.getInt("ad_window_id")).append("</AD_Window_ID>\n");
        xml.append("    <IsActive>").append(rs.getString("isactive")).append("</IsActive>\n");
        xml.append("    <EntityType>").append(rs.getString("entitytype")).append("</EntityType>\n");
        xml.append("  </AD_Menu>\n\n");
    }

    private Map<String, String> parseJsonColumns(String json) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        // Simple JSON parser for {"col":"val","col2":"val2"}
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length() - 1);

        String[] pairs = json.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
        for (String pair : pairs) {
            int colonPos = pair.indexOf(':');
            if (colonPos > 0) {
                String key = pair.substring(0, colonPos).trim().replaceAll("^\"|\"$", "");
                String value = pair.substring(colonPos + 1).trim().replaceAll("^\"|\"$", "");
                result.put(key, value);
            }
        }
        return result;
    }

    private String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ========== APPLY-DATA: Direct SQL Injection to PostgreSQL ==========

    /**
     * Apply staged data pack directly to iDempiere PostgreSQL
     * Tracks all inserted records for later cleanup
     */
    public int applyData(String packName) throws SQLException {
        if (ideConn == null) {
            throw new SQLException("Not connected to iDempiere database");
        }

        log.info("Applying data pack: " + packName);
        operationId = startOperation("APPLY_DATA", packName);

        // Find pack
        String packUU = null;
        String sql = "SELECT pack_header_uu FROM pack_header WHERE name = ?";
        try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
            ps.setString(1, packName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Pack not found: " + packName);
                }
                packUU = rs.getString(1);
            }
        }

        int inserted = 0;
        int seqNo = 0;
        String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());

        try {
            // Get staged data ordered by table (respect FK dependencies)
            // Order: mp_meter, a_asset, mp_jobstandar, mp_jobstandar_task, mp_assetmeter, mp_maintain, mp_assetmeter_log
            sql = "SELECT * FROM pack_data WHERE pack_header_uu = ? ORDER BY seq_no";
            try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
                ps.setString(1, packUU);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("table_name");
                        String columnData = rs.getString("column_data");
                        int recordId = rs.getInt("record_id");

                        // Parse column data
                        Map<String, String> cols = parseJsonColumns(columnData);

                        // Generate real UUID if needed
                        String recordUU = null;
                        for (String key : cols.keySet()) {
                            if (key.endsWith("_uu")) {
                                String val = cols.get(key);
                                if (val.contains("uuid_generate") || val.isEmpty()) {
                                    recordUU = UUID.randomUUID().toString();
                                    cols.put(key, recordUU);
                                } else {
                                    recordUU = val;
                                }
                            }
                            // Replace NOW() with actual timestamp
                            if (cols.get(key).equalsIgnoreCase("NOW()")) {
                                cols.put(key, timestamp);
                            }
                        }

                        // Find primary key column
                        String pkColumn = tableName + "_id";

                        // Build INSERT statement - skip NULL values
                        StringBuilder insertSql = new StringBuilder("INSERT INTO ");
                        insertSql.append(tableName).append(" (");
                        StringBuilder values = new StringBuilder(" VALUES (");

                        boolean first = true;
                        List<String> params = new ArrayList<>();
                        List<String> paramTypes = new ArrayList<>();  // Track type: "uuid", "timestamp", "numeric", "string"
                        for (Map.Entry<String, String> entry : cols.entrySet()) {
                            String colName = entry.getKey();
                            String colValue = entry.getValue();

                            // Skip NULL values entirely
                            if (colValue == null || colValue.equalsIgnoreCase("NULL")) {
                                continue;
                            }

                            if (!first) {
                                insertSql.append(", ");
                                values.append(", ");
                            }
                            insertSql.append(colName);

                            // Cast columns for PostgreSQL types
                            String colLower = colName.toLowerCase();
                            // Check if value looks like a date/timestamp (YYYY-MM-DD or YYYY-MM-DD HH:MM:SS)
                            boolean isDateValue = colValue.matches("\\d{4}-\\d{2}-\\d{2}.*");
                            // Date column detection - must END with "date", START with "date", or be exactly "created"/"updated"
                            // This avoids false positives like "updatedby", "createdby", etc.
                            boolean isDateColumn = colLower.endsWith("date") || colLower.startsWith("date") ||
                                                   colLower.equals("created") || colLower.equals("updated");

                            if (colLower.endsWith("_uu")) {
                                values.append("?::uuid");
                                paramTypes.add("uuid");
                            } else if (isDateValue || isDateColumn) {
                                values.append("?::timestamp");
                                paramTypes.add("timestamp");
                            } else if (colValue.matches("\\d+") && !colValue.startsWith("0")) {
                                values.append("?");
                                paramTypes.add("numeric");
                            } else if (colValue.matches("\\d+\\.\\d+")) {
                                values.append("?");
                                paramTypes.add("decimal");
                            } else {
                                values.append("?");
                                paramTypes.add("string");
                            }
                            params.add(colValue);
                            first = false;
                        }
                        insertSql.append(")");
                        values.append(")");

                        // Execute INSERT
                        try (PreparedStatement insertPs = ideConn.prepareStatement(insertSql.toString() + values.toString())) {
                            for (int i = 0; i < params.size(); i++) {
                                String val = params.get(i);
                                String ptype = paramTypes.get(i);
                                // Bind based on tracked type
                                switch (ptype) {
                                    case "numeric":
                                        try {
                                            insertPs.setInt(i + 1, Integer.parseInt(val));
                                        } catch (NumberFormatException e) {
                                            insertPs.setLong(i + 1, Long.parseLong(val));
                                        }
                                        break;
                                    case "decimal":
                                        insertPs.setBigDecimal(i + 1, new java.math.BigDecimal(val));
                                        break;
                                    default:
                                        // uuid, timestamp, string - all use setString
                                        insertPs.setString(i + 1, val);
                                        break;
                                }
                            }
                            insertPs.executeUpdate();
                        }

                        // Track inserted record in SQLite
                        seqNo++;
                        String trackSql = "INSERT INTO applied_records (pack_header_uu, table_name, record_id, record_uu, pk_column, seq_no, status) VALUES (?, ?, ?, ?, ?, ?, 'APPLIED')";
                        try (PreparedStatement trackPs = sqliteConn.prepareStatement(trackSql)) {
                            trackPs.setString(1, packUU);
                            trackPs.setString(2, tableName);
                            trackPs.setInt(3, recordId);
                            trackPs.setString(4, recordUU);
                            trackPs.setString(5, pkColumn);
                            trackPs.setInt(6, seqNo);
                            trackPs.executeUpdate();
                        }

                        inserted++;
                        logDetail(tableName, String.valueOf(recordId), "INSERT", "Applied to PG");
                        log.fine("Inserted: " + tableName + " ID=" + recordId);
                    }
                }
            }

            // Update pack status
            sql = "UPDATE pack_header SET status = 'APPLIED' WHERE pack_header_uu = ?";
            try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
                ps.setString(1, packUU);
                ps.executeUpdate();
            }

            ideConn.commit();
            sqliteConn.commit();
            tablesImported = inserted;
            completeOperation(operationId, "SUCCESS");

            log.info("Applied " + inserted + " records to iDempiere");
            return inserted;

        } catch (Exception e) {
            ideConn.rollback();
            completeOperation(operationId, "FAILED");
            throw new SQLException("Apply data failed: " + e.getMessage(), e);
        }
    }

    /**
     * Show applied records for a pack
     */
    public void showApplied(String packName) throws SQLException {
        System.out.println("=== Applied Records: " + packName + " ===");

        String sql = "SELECT table_name, COUNT(*) as cnt FROM applied_records " +
                     "WHERE pack_header_uu = (SELECT pack_header_uu FROM pack_header WHERE name = ?) " +
                     "AND status = 'APPLIED' GROUP BY table_name ORDER BY MIN(seq_no)";
        try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
            ps.setString(1, packName);
            try (ResultSet rs = ps.executeQuery()) {
                int total = 0;
                while (rs.next()) {
                    System.out.println("  " + rs.getString("table_name") + ": " + rs.getInt("cnt"));
                    total += rs.getInt("cnt");
                }
                System.out.println("  ---");
                System.out.println("  Total: " + total + " records");
            }
        }
    }

    // ========== CLEAN-DATA: Cascading Delete from PostgreSQL ==========

    /**
     * Delete applied data pack from iDempiere (cascading, reverse order)
     */
    public int cleanData(String packName) throws SQLException {
        if (ideConn == null) {
            throw new SQLException("Not connected to iDempiere database");
        }

        log.info("Cleaning data pack: " + packName);
        operationId = startOperation("CLEAN_DATA", packName);

        // Find pack
        String packUU = null;
        String sql = "SELECT pack_header_uu FROM pack_header WHERE name = ?";
        try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
            ps.setString(1, packName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Pack not found: " + packName);
                }
                packUU = rs.getString(1);
            }
        }

        int deleted = 0;

        try {
            // Get applied records in REVERSE order (to handle FK dependencies)
            sql = "SELECT * FROM applied_records WHERE pack_header_uu = ? AND status = 'APPLIED' ORDER BY seq_no DESC";
            try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
                ps.setString(1, packUU);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("table_name");
                        int recordId = rs.getInt("record_id");
                        String pkColumn = rs.getString("pk_column");
                        int appliedId = rs.getInt("applied_id");

                        // Build DELETE statement
                        String deleteSql = "DELETE FROM " + tableName + " WHERE " + pkColumn + " = ?";

                        try (PreparedStatement deletePs = ideConn.prepareStatement(deleteSql)) {
                            deletePs.setInt(1, recordId);
                            int affected = deletePs.executeUpdate();
                            if (affected > 0) {
                                deleted++;
                                log.fine("Deleted: " + tableName + " ID=" + recordId);
                            }
                        }

                        // Mark as deleted in SQLite
                        String updateSql = "UPDATE applied_records SET status = 'DELETED' WHERE applied_id = ?";
                        try (PreparedStatement updatePs = sqliteConn.prepareStatement(updateSql)) {
                            updatePs.setInt(1, appliedId);
                            updatePs.executeUpdate();
                        }

                        logDetail(tableName, String.valueOf(recordId), "DELETE", "Removed from PG");
                    }
                }
            }

            // Update pack status
            sql = "UPDATE pack_header SET status = 'CLEANED' WHERE pack_header_uu = ?";
            try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
                ps.setString(1, packUU);
                ps.executeUpdate();
            }

            ideConn.commit();
            sqliteConn.commit();
            completeOperation(operationId, "SUCCESS");

            log.info("Deleted " + deleted + " records from iDempiere");
            return deleted;

        } catch (Exception e) {
            ideConn.rollback();
            completeOperation(operationId, "FAILED");
            throw new SQLException("Clean data failed: " + e.getMessage(), e);
        }
    }

    // ========== PACKOUT: Generate PIPO-compatible 2Pack ==========

    /**
     * Export staged data to PIPO-compatible PackOut.xml
     * Supports both AD (dictionary) and data (GW) models
     *
     * @param packName Name of the staged pack in SQLite
     * @param outputDir Output directory (default: current dir)
     * @param clientId AD_Client_ID (0=System, 11=GardenWorld, etc.)
     * @return Path to generated zip file
     */
    public String packout(String packName, String outputDir, int clientId) throws Exception {
        log.info("Generating 2Pack: " + packName + " (Client=" + clientId + ")");
        operationId = startOperation("PACKOUT", packName);

        // Find pack header
        String packUU = null;
        String version = "1.0.0";
        String description = "";
        String sql = "SELECT pack_header_uu, version, name FROM pack_header WHERE name = ?";
        try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
            ps.setString(1, packName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new Exception("Pack not found: " + packName);
                }
                packUU = rs.getString("pack_header_uu");
                version = rs.getString("version");
                if (version == null) version = "1.0.0";
            }
        }

        // Determine client info
        String clientValue, clientName;
        if (clientId == 0) {
            clientValue = "SYSTEM";
            clientName = "System";
        } else {
            clientValue = "Client" + clientId;
            clientName = "Client " + clientId;
            // Try to get from synced AD_Client
            sql = "SELECT Value, Name FROM AD_Client WHERE AD_Client_ID = ?";
            try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
                ps.setInt(1, clientId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        clientValue = rs.getString("Value");
                        clientName = rs.getString("Name");
                    }
                }
            }
        }

        // Create output structure
        if (outputDir == null || outputDir.isEmpty()) {
            outputDir = ".";
        }
        File outDir = new File(outputDir);
        if (!outDir.exists()) outDir.mkdirs();

        File packDir = new File(outDir, packName);
        File dictDir = new File(packDir, "dict");
        dictDir.mkdirs();

        File xmlFile = new File(dictDir, "PackOut.xml");
        int recordCount = 0;

        // Generate PackOut.xml
        try (java.io.PrintWriter pw = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(xmlFile), "UTF-8"))) {

            // XML Header
            pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            pw.printf("<idempiere Name=\"%s\" Version=\"%s\" CompVer=\"\" DataBase=\"\" " +
                      "Description=\"%s\" Creator=\"Ninja SilentPiper\" CreatorContact=\"\" " +
                      "Client=\"%d-%s-%s\">%n",
                      escapeXml(packName), escapeXml(version), escapeXml(description),
                      clientId, escapeXml(clientValue), escapeXml(clientName));

            // Get staged records grouped by table
            sql = "SELECT DISTINCT table_name FROM pack_data WHERE pack_header_uu = ? ORDER BY MIN(seq_no)";
            List<String> tables = new ArrayList<>();
            try (PreparedStatement ps = sqliteConn.prepareStatement(
                    "SELECT DISTINCT table_name FROM pack_data WHERE pack_header_uu = ? " +
                    "GROUP BY table_name ORDER BY MIN(seq_no)")) {
                ps.setString(1, packUU);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        tables.add(rs.getString("table_name"));
                    }
                }
            }

            // Export each table's records
            for (String tableName : tables) {
                sql = "SELECT column_data FROM pack_data WHERE pack_header_uu = ? AND table_name = ? ORDER BY seq_no";
                try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
                    ps.setString(1, packUU);
                    ps.setString(2, tableName);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String jsonData = rs.getString("column_data");
                            writeElement(pw, tableName, jsonData, clientId);
                            recordCount++;
                        }
                    }
                }
            }

            // Close root element
            pw.println("</idempiere>");
        }

        // Create ZIP
        File zipFile = new File(outDir, packName + "_" + version.replace(".", "_") + ".zip");
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new java.io.FileOutputStream(zipFile))) {
            zipDirectory(packDir, packDir.getName(), zos);
        }

        // Cleanup temp directory
        deleteDirectory(packDir);

        completeOperation(operationId, "SUCCESS");
        log.info("Generated: " + zipFile.getAbsolutePath() + " (" + recordCount + " records)");

        return zipFile.getAbsolutePath();
    }

    /**
     * Export AD model tables to 2Pack (from iDempiere PostgreSQL)
     *
     * @param tablePrefix Prefix to filter tables (e.g., "MP_", "HR_")
     * @param outputDir Output directory
     * @param includeData Include table data (not just structure)
     */
    public String packoutAD(String tablePrefix, String outputDir, boolean includeData) throws Exception {
        if (ideConn == null) {
            throw new Exception("Not connected to iDempiere database");
        }

        String packName = tablePrefix.replace("_", "") + "_Model";
        log.info("Exporting AD model: " + tablePrefix + "* to " + packName);
        operationId = startOperation("PACKOUT_AD", packName);

        if (outputDir == null || outputDir.isEmpty()) {
            outputDir = ".";
        }
        File outDir = new File(outputDir);
        if (!outDir.exists()) outDir.mkdirs();

        File packDir = new File(outDir, packName);
        File dictDir = new File(packDir, "dict");
        dictDir.mkdirs();

        File xmlFile = new File(dictDir, "PackOut.xml");

        try (java.io.PrintWriter pw = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(xmlFile), "UTF-8"))) {

            // XML Header (System level)
            pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            pw.printf("<idempiere Name=\"%s\" Version=\"1.0.0\" CompVer=\"\" DataBase=\"\" " +
                      "Description=\"AD Model for %s tables\" Creator=\"Ninja SilentPiper\" " +
                      "CreatorContact=\"\" Client=\"0-SYSTEM-System\">%n",
                      escapeXml(packName), escapeXml(tablePrefix));

            int elementCount = 0;

            // Export AD_Table
            elementCount += exportADTable(pw, tablePrefix);

            // Export AD_Column for those tables
            elementCount += exportADColumn(pw, tablePrefix);

            // Export AD_Element for those columns
            elementCount += exportADElement(pw, tablePrefix);

            // Export AD_Window, AD_Tab, AD_Field if they reference these tables
            elementCount += exportADWindow(pw, tablePrefix);

            // Export AD_Menu entries
            elementCount += exportADMenu(pw, tablePrefix);

            // Export AD_Process if referenced
            elementCount += exportADProcess(pw, tablePrefix);

            pw.println("</idempiere>");

            completeOperation(operationId, "SUCCESS");
            log.info("Exported " + elementCount + " AD elements");
        }

        // Create ZIP
        File zipFile = new File(outDir, packName + ".zip");
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new java.io.FileOutputStream(zipFile))) {
            zipDirectory(packDir, packDir.getName(), zos);
        }

        deleteDirectory(packDir);
        return zipFile.getAbsolutePath();
    }

    /**
     * Write a single element to PackOut.xml from JSON data
     */
    private void writeElement(java.io.PrintWriter pw, String tableName, String jsonData, int clientId) {
        pw.printf("  <%s action=\"insert\">%n", tableName.toLowerCase());

        // Parse JSON and write columns
        // Simple JSON parser for {col:val, col:val} format
        jsonData = jsonData.trim();
        if (jsonData.startsWith("{")) jsonData = jsonData.substring(1);
        if (jsonData.endsWith("}")) jsonData = jsonData.substring(0, jsonData.length() - 1);

        // Split by comma, handling quoted values
        Map<String, String> columns = parseJsonColumns(jsonData);

        for (Map.Entry<String, String> entry : columns.entrySet()) {
            String col = entry.getKey().toLowerCase();
            String val = entry.getValue();

            // Format value based on type
            val = formatValueForPIPO(col, val);

            if (val == null || val.isEmpty()) {
                pw.printf("    <%s/>%n", col);
            } else {
                pw.printf("    <%s>%s</%s>%n", col, escapeXml(val), col);
            }
        }

        pw.printf("  </%s>%n", tableName.toLowerCase());
    }

    /**
     * Format value for PIPO compatibility
     */
    private String formatValueForPIPO(String columnName, String value) {
        if (value == null) return null;
        value = value.trim();
        if (value.isEmpty()) return "";

        // Handle SQL functions
        if (value.equalsIgnoreCase("NOW()") || value.equalsIgnoreCase("CURRENT_TIMESTAMP")) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
        }
        if (value.equalsIgnoreCase("uuid_generate_v4()")) {
            return UUID.randomUUID().toString();
        }

        // Date-only values need time component
        if (value.matches("\\d{4}-\\d{2}-\\d{2}") && !value.contains(" ")) {
            return value + " 00:00:00";
        }

        // Boolean normalization
        if (value.equalsIgnoreCase("true") || value.equals("1")) return "Y";
        if (value.equalsIgnoreCase("false") || value.equals("0")) return "N";

        return value;
    }

    // ========== AD Export Helpers ==========

    private int exportADTable(java.io.PrintWriter pw, String prefix) throws SQLException {
        int count = 0;
        String sql = "SELECT * FROM AD_Table WHERE TableName LIKE ? AND EntityType = 'U' ORDER BY TableName";
        try (PreparedStatement ps = ideConn.prepareStatement(sql)) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    writeResultSetElement(pw, "AD_Table", rs, meta);
                    count++;
                }
            }
        }
        log.info("Exported " + count + " AD_Table records");
        return count;
    }

    private int exportADColumn(java.io.PrintWriter pw, String prefix) throws SQLException {
        int count = 0;
        String sql = "SELECT c.* FROM AD_Column c " +
                     "JOIN AD_Table t ON c.AD_Table_ID = t.AD_Table_ID " +
                     "WHERE t.TableName LIKE ? AND c.EntityType = 'U' " +
                     "ORDER BY t.TableName, c.SeqNo";
        try (PreparedStatement ps = ideConn.prepareStatement(sql)) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    writeResultSetElement(pw, "AD_Column", rs, meta);
                    count++;
                }
            }
        }
        log.info("Exported " + count + " AD_Column records");
        return count;
    }

    private int exportADElement(java.io.PrintWriter pw, String prefix) throws SQLException {
        int count = 0;
        String sql = "SELECT DISTINCT e.* FROM AD_Element e " +
                     "JOIN AD_Column c ON c.AD_Element_ID = e.AD_Element_ID " +
                     "JOIN AD_Table t ON c.AD_Table_ID = t.AD_Table_ID " +
                     "WHERE t.TableName LIKE ? AND e.EntityType = 'U' " +
                     "ORDER BY e.ColumnName";
        try (PreparedStatement ps = ideConn.prepareStatement(sql)) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    writeResultSetElement(pw, "AD_Element", rs, meta);
                    count++;
                }
            }
        }
        log.info("Exported " + count + " AD_Element records");
        return count;
    }

    private int exportADWindow(java.io.PrintWriter pw, String prefix) throws SQLException {
        int count = 0;
        // Get windows that have tabs referencing our tables
        String sql = "SELECT DISTINCT w.* FROM AD_Window w " +
                     "JOIN AD_Tab tab ON tab.AD_Window_ID = w.AD_Window_ID " +
                     "JOIN AD_Table t ON tab.AD_Table_ID = t.AD_Table_ID " +
                     "WHERE t.TableName LIKE ? AND w.EntityType = 'U' " +
                     "ORDER BY w.Name";
        try (PreparedStatement ps = ideConn.prepareStatement(sql)) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    int windowId = rs.getInt("AD_Window_ID");
                    writeResultSetElement(pw, "AD_Window", rs, meta);
                    count++;
                    // Export tabs and fields for this window
                    count += exportADTab(pw, windowId);
                }
            }
        }
        log.info("Exported " + count + " AD_Window/Tab/Field records");
        return count;
    }

    private int exportADTab(java.io.PrintWriter pw, int windowId) throws SQLException {
        int count = 0;
        String sql = "SELECT * FROM AD_Tab WHERE AD_Window_ID = ? AND EntityType = 'U' ORDER BY SeqNo";
        try (PreparedStatement ps = ideConn.prepareStatement(sql)) {
            ps.setInt(1, windowId);
            try (ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    int tabId = rs.getInt("AD_Tab_ID");
                    writeResultSetElement(pw, "AD_Tab", rs, meta);
                    count++;
                    count += exportADField(pw, tabId);
                }
            }
        }
        return count;
    }

    private int exportADField(java.io.PrintWriter pw, int tabId) throws SQLException {
        int count = 0;
        String sql = "SELECT * FROM AD_Field WHERE AD_Tab_ID = ? AND EntityType = 'U' ORDER BY SeqNo";
        try (PreparedStatement ps = ideConn.prepareStatement(sql)) {
            ps.setInt(1, tabId);
            try (ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    writeResultSetElement(pw, "AD_Field", rs, meta);
                    count++;
                }
            }
        }
        return count;
    }

    private int exportADMenu(java.io.PrintWriter pw, String prefix) throws SQLException {
        int count = 0;
        String sql = "SELECT m.* FROM AD_Menu m " +
                     "WHERE m.EntityType = 'U' AND (" +
                     "  m.AD_Window_ID IN (SELECT DISTINCT w.AD_Window_ID FROM AD_Window w " +
                     "    JOIN AD_Tab t ON t.AD_Window_ID = w.AD_Window_ID " +
                     "    JOIN AD_Table tbl ON t.AD_Table_ID = tbl.AD_Table_ID " +
                     "    WHERE tbl.TableName LIKE ?) " +
                     "  OR m.AD_Process_ID IN (SELECT DISTINCT AD_Process_ID FROM AD_Process WHERE EntityType = 'U' AND Name LIKE ?)" +
                     ") ORDER BY m.Name";
        try (PreparedStatement ps = ideConn.prepareStatement(sql)) {
            ps.setString(1, prefix + "%");
            ps.setString(2, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    writeResultSetElement(pw, "AD_Menu", rs, meta);
                    count++;
                }
            }
        }
        log.info("Exported " + count + " AD_Menu records");
        return count;
    }

    private int exportADProcess(java.io.PrintWriter pw, String prefix) throws SQLException {
        int count = 0;
        String sql = "SELECT * FROM AD_Process WHERE EntityType = 'U' AND " +
                     "(Name LIKE ? OR Classname LIKE ?) ORDER BY Name";
        try (PreparedStatement ps = ideConn.prepareStatement(sql)) {
            ps.setString(1, prefix + "%");
            ps.setString(2, "%" + prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    writeResultSetElement(pw, "AD_Process", rs, meta);
                    count++;
                }
            }
        }
        log.info("Exported " + count + " AD_Process records");
        return count;
    }

    /**
     * Write ResultSet row as XML element
     */
    private void writeResultSetElement(java.io.PrintWriter pw, String elementName,
            ResultSet rs, java.sql.ResultSetMetaData meta) throws SQLException {

        pw.printf("  <%s type=\"table\">%n", elementName);

        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String colName = meta.getColumnName(i);
            String value = rs.getString(i);

            // Skip audit columns
            if (colName.equalsIgnoreCase("Created") || colName.equalsIgnoreCase("CreatedBy") ||
                colName.equalsIgnoreCase("Updated") || colName.equalsIgnoreCase("UpdatedBy")) {
                continue;
            }

            if (value == null || value.isEmpty()) {
                pw.printf("    <%s/>%n", colName);
            } else {
                // Format timestamps
                if (meta.getColumnType(i) == java.sql.Types.TIMESTAMP) {
                    value = value.substring(0, Math.min(19, value.length()));  // yyyy-MM-dd HH:mm:ss
                }
                // Format booleans
                if (value.equalsIgnoreCase("t")) value = "Y";
                if (value.equalsIgnoreCase("f")) value = "N";

                pw.printf("    <%s>%s</%s>%n", colName, escapeXml(value), colName);
            }
        }

        pw.printf("  </%s>%n", elementName);
    }

    // ========== Utility Methods ==========

    private void zipDirectory(File folder, String parentFolder, java.util.zip.ZipOutputStream zos) throws Exception {
        for (File file : folder.listFiles()) {
            if (file.isDirectory()) {
                zipDirectory(file, parentFolder + "/" + file.getName(), zos);
            } else {
                zos.putNextEntry(new java.util.zip.ZipEntry(parentFolder + "/" + file.getName()));
                java.nio.file.Files.copy(file.toPath(), zos);
                zos.closeEntry();
            }
        }
    }

    private void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            for (File file : dir.listFiles()) {
                deleteDirectory(file);
            }
        }
        dir.delete();
    }

    public void close() throws SQLException {
        if (ideConn != null) ideConn.close();
        if (sqliteConn != null) sqliteConn.close();
    }

    // ========== PACKOUT-MODEL: SQLite Staged Model → AD 2Pack (NO PostgreSQL) ==========

    /**
     * Generate AD model 2Pack from SQLite staged model (RO_ModelHeader, RO_ModelMaker).
     * This works COMPLETELY OFFLINE - no iDempiere or PostgreSQL connection needed.
     *
     * Takes the Excel intent model staged in SQLite and generates a PIPO-compatible
     * 2Pack that can be deployed to any iDempiere instance.
     *
     * @param bundleName Name of the staged bundle (from RO_ModelHeader)
     * @param outputDir Output directory for 2Pack.zip
     * @return Path to generated 2Pack.zip file
     */
    public String packoutModel(String bundleName, String outputDir) throws Exception {
        log.info("Generating AD Model 2Pack from staged model: " + bundleName);
        operationId = startOperation("PACKOUT_MODEL", bundleName);

        // Find bundle header
        String headerUU = null;
        String version = "1.0.0";
        String description = "";
        String author = "";
        String sql = "SELECT * FROM RO_ModelHeader WHERE Name = ?";
        try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
            ps.setString(1, bundleName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new Exception("Bundle not found: " + bundleName);
                }
                headerUU = rs.getString("RO_ModelHeader_UU");
                version = rs.getString("Version") != null ? rs.getString("Version") : "1.0.0";
                description = rs.getString("Description") != null ? rs.getString("Description") : "";
                author = rs.getString("Author") != null ? rs.getString("Author") : "Ninja";
            }
        }

        // Get staged models
        List<Map<String, String>> models = new ArrayList<>();
        sql = "SELECT * FROM RO_ModelMaker WHERE RO_ModelHeader_UU = ? ORDER BY SeqNo";
        try (PreparedStatement ps = sqliteConn.prepareStatement(sql)) {
            ps.setString(1, headerUU);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> model = new HashMap<>();
                    model.put("uuid", rs.getString("RO_ModelMaker_UU"));
                    model.put("name", rs.getString("Name"));
                    model.put("master", rs.getString("Master") != null ? rs.getString("Master") : "");
                    model.put("columns", rs.getString("ColumnSet") != null ? rs.getString("ColumnSet") : "");
                    model.put("description", rs.getString("Description") != null ? rs.getString("Description") : "");
                    model.put("help", rs.getString("Help") != null ? rs.getString("Help") : "");
                    model.put("workflow", rs.getString("WorkflowStructure"));
                    model.put("kanban", rs.getString("KanbanBoard"));
                    models.add(model);
                }
            }
        }

        if (models.isEmpty()) {
            throw new Exception("No staged models found for bundle: " + bundleName);
        }

        // Create output structure
        if (outputDir == null || outputDir.isEmpty()) {
            outputDir = ".";
        }
        File outDir = new File(outputDir);
        if (!outDir.exists()) outDir.mkdirs();

        String packName = bundleName + "_Model";
        File packDir = new File(outDir, packName);
        File dictDir = new File(packDir, "dict");
        dictDir.mkdirs();

        File xmlFile = new File(dictDir, "PackOut.xml");
        int elementCount = 0;
        int tableIdBase = 1000000;  // Start ID for generated tables
        int columnIdBase = 2000000;
        int windowIdBase = 3000000;
        int tabIdBase = 4000000;
        int fieldIdBase = 5000000;
        int menuIdBase = 6000000;
        int elementIdBase = 7000000;

        // Track generated IDs for cross-references
        Map<String, Integer> tableIds = new HashMap<>();
        Map<String, Integer> windowIds = new HashMap<>();
        Map<String, Integer> tabIds = new HashMap<>();
        Map<String, Integer> columnIds = new HashMap<>();  // tableName.columnName -> id
        Map<String, Integer> elementIds = new HashMap<>(); // columnName -> element_id

        try (java.io.PrintWriter pw = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(xmlFile), "UTF-8"))) {

            // XML Header (System level - AD_Client_ID=0)
            pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            pw.printf("<idempiere Name=\"%s\" Version=\"%s\" CompVer=\"\" DataBase=\"\" " +
                      "Description=\"%s\" Creator=\"%s\" CreatorContact=\"\" " +
                      "Client=\"0-SYSTEM-System\">%n",
                      escapeXml(packName), escapeXml(version), escapeXml(description), escapeXml(author));

            // Phase 1: Generate AD_Element for all unique columns
            log.info("Phase 1: Generating AD_Element records...");
            java.util.Set<String> processedColumns = new java.util.HashSet<>();
            for (Map<String, String> model : models) {
                String tableName = model.get("name");
                String columns = model.get("columns");
                if (columns == null || columns.isEmpty()) continue;

                // Add standard columns
                String[] standardCols = {tableName + "_ID", tableName + "_UU", "AD_Client_ID", "AD_Org_ID",
                                         "IsActive", "Created", "CreatedBy", "Updated", "UpdatedBy"};
                for (String col : standardCols) {
                    if (!processedColumns.contains(col)) {
                        int elemId = elementIdBase++;
                        elementIds.put(col, elemId);
                        writeADElement(pw, elemId, col, col.replace("_", " "), "U");
                        processedColumns.add(col);
                        elementCount++;
                    }
                }

                // Add custom columns
                for (String colDef : columns.split(",")) {
                    String colName = parseColumnName(colDef.trim());
                    if (!colName.isEmpty() && !processedColumns.contains(colName)) {
                        int elemId = elementIdBase++;
                        elementIds.put(colName, elemId);
                        writeADElement(pw, elemId, colName, colName.replace("_", " "), "U");
                        processedColumns.add(colName);
                        elementCount++;
                    }
                }
            }

            // Phase 2: Generate AD_Table and AD_Column
            log.info("Phase 2: Generating AD_Table and AD_Column records...");
            for (Map<String, String> model : models) {
                String tableName = model.get("name");
                String desc = model.get("description");
                String help = model.get("help");
                String master = model.get("master");

                int tableId = tableIdBase++;
                tableIds.put(tableName, tableId);

                // Determine access level based on master relationship
                String accessLevel = "3";  // Client+Org by default
                if (master == null || master.isEmpty()) {
                    accessLevel = "4";  // System+Client for header tables
                }

                // Write AD_Table
                writeADTable(pw, tableId, tableName, desc, help, accessLevel);
                elementCount++;

                // Write AD_Columns
                String columns = model.get("columns");
                int seqNo = 10;

                // Standard key column
                String keyCol = tableName + "_ID";
                int keyColId = columnIdBase++;
                columnIds.put(tableName + "." + keyCol, keyColId);
                writeADColumn(pw, keyColId, tableId, keyCol, keyCol.replace("_", " "),
                             13, 22, seqNo, "Y", "Y", "N", elementIds.getOrDefault(keyCol, 0));
                seqNo += 10;
                elementCount++;

                // UUID column
                String uuCol = tableName + "_UU";
                int uuColId = columnIdBase++;
                columnIds.put(tableName + "." + uuCol, uuColId);
                writeADColumn(pw, uuColId, tableId, uuCol, "UUID",
                             10, 36, seqNo, "N", "N", "N", elementIds.getOrDefault(uuCol, 0));
                seqNo += 10;
                elementCount++;

                // Standard audit columns
                String[][] auditCols = {
                    {"AD_Client_ID", "Client", "19", "22", "Y"},
                    {"AD_Org_ID", "Organization", "19", "22", "Y"},
                    {"IsActive", "Active", "20", "1", "Y"},
                    {"Created", "Created", "16", "29", "N"},
                    {"CreatedBy", "Created By", "18", "22", "N"},
                    {"Updated", "Updated", "16", "29", "N"},
                    {"UpdatedBy", "Updated By", "18", "22", "N"}
                };
                for (String[] audit : auditCols) {
                    int auditColId = columnIdBase++;
                    columnIds.put(tableName + "." + audit[0], auditColId);
                    writeADColumn(pw, auditColId, tableId, audit[0], audit[1],
                                 Integer.parseInt(audit[2]), Integer.parseInt(audit[3]),
                                 seqNo, audit[4], "N", "N", elementIds.getOrDefault(audit[0], 0));
                    seqNo += 10;
                    elementCount++;
                }

                // Parent link column if this is a detail table
                if (master != null && !master.isEmpty()) {
                    String parentCol = master + "_ID";
                    int parentColId = columnIdBase++;
                    columnIds.put(tableName + "." + parentCol, parentColId);
                    writeADColumn(pw, parentColId, tableId, parentCol, master.replace("_", " "),
                                 19, 22, seqNo, "Y", "N", "N", elementIds.getOrDefault(parentCol, 0));
                    seqNo += 10;
                    elementCount++;
                }

                // Custom columns from ColumnSet
                if (columns != null && !columns.isEmpty()) {
                    for (String colDef : columns.split(",")) {
                        colDef = colDef.trim();
                        if (colDef.isEmpty()) continue;

                        String colName = parseColumnName(colDef);
                        int refId = parseColumnType(colDef);
                        int fieldLen = getFieldLength(refId);

                        int colId = columnIdBase++;
                        columnIds.put(tableName + "." + colName, colId);
                        writeADColumn(pw, colId, tableId, colName, colName.replace("_", " "),
                                     refId, fieldLen, seqNo, "N", "N", "N",
                                     elementIds.getOrDefault(colName, 0));
                        seqNo += 10;
                        elementCount++;
                    }
                }
            }

            // Phase 3: Generate AD_Window, AD_Tab, AD_Field
            log.info("Phase 3: Generating AD_Window, AD_Tab, AD_Field records...");
            for (Map<String, String> model : models) {
                String tableName = model.get("name");
                String master = model.get("master");
                String desc = model.get("description");
                String help = model.get("help");

                // Only create windows for header tables (no master)
                if (master == null || master.isEmpty()) {
                    int windowId = windowIdBase++;
                    windowIds.put(tableName, windowId);

                    String windowName = tableName.replace("_", " ");
                    writeADWindow(pw, windowId, windowName, desc, help);
                    elementCount++;

                    // Main tab for this table
                    int tabId = tabIdBase++;
                    tabIds.put(tableName, tabId);
                    Integer tableId = tableIds.get(tableName);
                    writeADTab(pw, tabId, windowId, tableId != null ? tableId : 0,
                               windowName, 0, 10, desc, help, 0);
                    elementCount++;

                    // Fields for main tab
                    elementCount += writeFieldsForTable(pw, tabId, tableName, columnIds, fieldIdBase);
                    fieldIdBase += 100;  // Reserve space

                    // Find and add detail tabs
                    int tabSeq = 20;
                    int tabLevel = 1;
                    for (Map<String, String> detailModel : models) {
                        String detailMaster = detailModel.get("master");
                        if (tableName.equals(detailMaster)) {
                            String detailTable = detailModel.get("name");
                            int detailTabId = tabIdBase++;
                            tabIds.put(detailTable, detailTabId);
                            Integer detailTableId = tableIds.get(detailTable);

                            // Link column for parent-child relationship
                            Integer linkColId = columnIds.get(detailTable + "." + tableName + "_ID");

                            writeADTab(pw, detailTabId, windowId, detailTableId != null ? detailTableId : 0,
                                       detailTable.replace("_", " "), tabLevel, tabSeq,
                                       detailModel.get("description"), detailModel.get("help"),
                                       linkColId != null ? linkColId : 0);
                            elementCount++;

                            elementCount += writeFieldsForTable(pw, detailTabId, detailTable, columnIds, fieldIdBase);
                            fieldIdBase += 100;

                            tabSeq += 10;
                        }
                    }
                }
            }

            // Phase 4: Generate AD_Menu
            log.info("Phase 4: Generating AD_Menu records...");
            // Create folder menu
            int folderMenuId = menuIdBase++;
            writeADMenu(pw, folderMenuId, bundleName, null, 0, true);
            elementCount++;

            // Create window menus
            for (Map<String, String> model : models) {
                String tableName = model.get("name");
                String master = model.get("master");
                if (master == null || master.isEmpty()) {
                    Integer windowId = windowIds.get(tableName);
                    if (windowId != null) {
                        int menuId = menuIdBase++;
                        writeADMenu(pw, menuId, tableName.replace("_", " "), "W", windowId, false);
                        elementCount++;
                    }
                }
            }

            pw.println("</idempiere>");
        }

        // Create ZIP
        File zipFile = new File(outDir, packName + "_" + version.replace(".", "_") + ".zip");
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new java.io.FileOutputStream(zipFile))) {
            zipDirectory(packDir, packDir.getName(), zos);
        }

        deleteDirectory(packDir);

        completeOperation(operationId, "SUCCESS");
        log.info("Generated: " + zipFile.getAbsolutePath() + " (" + elementCount + " elements)");

        return zipFile.getAbsolutePath();
    }

    // Helper methods for packoutModel

    private String parseColumnName(String colDef) {
        if (colDef.contains(":")) {
            return colDef.split(":")[0].trim();
        }
        return colDef.trim();
    }

    private int getFieldLength(int refId) {
        switch (refId) {
            case 10: return 255;  // String
            case 11: case 13: return 22;   // Integer, ID
            case 12: return 22;   // Amount
            case 14: return 4000; // Text
            case 15: case 16: return 29;   // Date, DateTime
            case 17: return 10;   // List
            case 18: case 19: return 22;   // Table, TableDir
            case 20: return 1;    // Yes-No
            case 36: return 36;   // UUID
            default: return 255;
        }
    }

    private void writeADElement(java.io.PrintWriter pw, int elementId, String columnName, String name, String entityType) {
        String uuid = UUID.randomUUID().toString();
        pw.printf("  <AD_Element type=\"table\">%n");
        pw.printf("    <AD_Element_ID>%d</AD_Element_ID>%n", elementId);
        pw.printf("    <AD_Element_UU>%s</AD_Element_UU>%n", uuid);
        pw.printf("    <AD_Client_ID>0</AD_Client_ID>%n");
        pw.printf("    <AD_Org_ID>0</AD_Org_ID>%n");
        pw.printf("    <IsActive>Y</IsActive>%n");
        pw.printf("    <ColumnName>%s</ColumnName>%n", escapeXml(columnName));
        pw.printf("    <Name>%s</Name>%n", escapeXml(name));
        pw.printf("    <PrintName>%s</PrintName>%n", escapeXml(name));
        pw.printf("    <EntityType>%s</EntityType>%n", entityType);
        pw.printf("  </AD_Element>%n");
    }

    private void writeADTable(java.io.PrintWriter pw, int tableId, String tableName, String desc, String help, String accessLevel) {
        String uuid = UUID.randomUUID().toString();
        pw.printf("  <AD_Table type=\"table\">%n");
        pw.printf("    <AD_Table_ID>%d</AD_Table_ID>%n", tableId);
        pw.printf("    <AD_Table_UU>%s</AD_Table_UU>%n", uuid);
        pw.printf("    <AD_Client_ID>0</AD_Client_ID>%n");
        pw.printf("    <AD_Org_ID>0</AD_Org_ID>%n");
        pw.printf("    <IsActive>Y</IsActive>%n");
        pw.printf("    <TableName>%s</TableName>%n", escapeXml(tableName));
        pw.printf("    <Name>%s</Name>%n", escapeXml(tableName.replace("_", " ")));
        pw.printf("    <Description>%s</Description>%n", escapeXml(desc != null ? desc : ""));
        pw.printf("    <Help>%s</Help>%n", escapeXml(help != null ? help : ""));
        pw.printf("    <AccessLevel>%s</AccessLevel>%n", accessLevel);
        pw.printf("    <EntityType>U</EntityType>%n");
        pw.printf("    <IsDeleteable>Y</IsDeleteable>%n");
        pw.printf("    <IsHighVolume>N</IsHighVolume>%n");
        pw.printf("    <IsView>N</IsView>%n");
        pw.printf("    <IsSecurityEnabled>N</IsSecurityEnabled>%n");
        pw.printf("    <IsChangeLog>Y</IsChangeLog>%n");
        pw.printf("    <ReplicationType>L</ReplicationType>%n");
        pw.printf("  </AD_Table>%n");
    }

    private void writeADColumn(java.io.PrintWriter pw, int columnId, int tableId, String columnName,
                               String name, int refId, int fieldLen, int seqNo,
                               String isMandatory, String isKey, String isIdentifier, int elementId) {
        String uuid = UUID.randomUUID().toString();
        pw.printf("  <AD_Column type=\"table\">%n");
        pw.printf("    <AD_Column_ID>%d</AD_Column_ID>%n", columnId);
        pw.printf("    <AD_Column_UU>%s</AD_Column_UU>%n", uuid);
        pw.printf("    <AD_Client_ID>0</AD_Client_ID>%n");
        pw.printf("    <AD_Org_ID>0</AD_Org_ID>%n");
        pw.printf("    <IsActive>Y</IsActive>%n");
        pw.printf("    <AD_Table_ID>%d</AD_Table_ID>%n", tableId);
        pw.printf("    <ColumnName>%s</ColumnName>%n", escapeXml(columnName));
        pw.printf("    <Name>%s</Name>%n", escapeXml(name));
        pw.printf("    <AD_Reference_ID>%d</AD_Reference_ID>%n", refId);
        pw.printf("    <FieldLength>%d</FieldLength>%n", fieldLen);
        pw.printf("    <SeqNo>%d</SeqNo>%n", seqNo);
        pw.printf("    <IsMandatory>%s</IsMandatory>%n", isMandatory);
        pw.printf("    <IsKey>%s</IsKey>%n", isKey);
        pw.printf("    <IsIdentifier>%s</IsIdentifier>%n", isIdentifier);
        pw.printf("    <IsParent>N</IsParent>%n");
        pw.printf("    <IsUpdateable>Y</IsUpdateable>%n");
        pw.printf("    <EntityType>U</EntityType>%n");
        if (elementId > 0) {
            pw.printf("    <AD_Element_ID>%d</AD_Element_ID>%n", elementId);
        }
        pw.printf("  </AD_Column>%n");
    }

    private void writeADWindow(java.io.PrintWriter pw, int windowId, String name, String desc, String help) {
        String uuid = UUID.randomUUID().toString();
        pw.printf("  <AD_Window type=\"table\">%n");
        pw.printf("    <AD_Window_ID>%d</AD_Window_ID>%n", windowId);
        pw.printf("    <AD_Window_UU>%s</AD_Window_UU>%n", uuid);
        pw.printf("    <AD_Client_ID>0</AD_Client_ID>%n");
        pw.printf("    <AD_Org_ID>0</AD_Org_ID>%n");
        pw.printf("    <IsActive>Y</IsActive>%n");
        pw.printf("    <Name>%s</Name>%n", escapeXml(name));
        pw.printf("    <Description>%s</Description>%n", escapeXml(desc != null ? desc : ""));
        pw.printf("    <Help>%s</Help>%n", escapeXml(help != null ? help : ""));
        pw.printf("    <WindowType>M</WindowType>%n");
        pw.printf("    <IsSOTrx>Y</IsSOTrx>%n");
        pw.printf("    <EntityType>U</EntityType>%n");
        pw.printf("  </AD_Window>%n");
    }

    private void writeADTab(java.io.PrintWriter pw, int tabId, int windowId, int tableId,
                            String name, int tabLevel, int seqNo, String desc, String help, int linkColId) {
        String uuid = UUID.randomUUID().toString();
        pw.printf("  <AD_Tab type=\"table\">%n");
        pw.printf("    <AD_Tab_ID>%d</AD_Tab_ID>%n", tabId);
        pw.printf("    <AD_Tab_UU>%s</AD_Tab_UU>%n", uuid);
        pw.printf("    <AD_Client_ID>0</AD_Client_ID>%n");
        pw.printf("    <AD_Org_ID>0</AD_Org_ID>%n");
        pw.printf("    <IsActive>Y</IsActive>%n");
        pw.printf("    <AD_Window_ID>%d</AD_Window_ID>%n", windowId);
        pw.printf("    <AD_Table_ID>%d</AD_Table_ID>%n", tableId);
        pw.printf("    <Name>%s</Name>%n", escapeXml(name));
        pw.printf("    <Description>%s</Description>%n", escapeXml(desc != null ? desc : ""));
        pw.printf("    <Help>%s</Help>%n", escapeXml(help != null ? help : ""));
        pw.printf("    <TabLevel>%d</TabLevel>%n", tabLevel);
        pw.printf("    <SeqNo>%d</SeqNo>%n", seqNo);
        pw.printf("    <IsSingleRow>N</IsSingleRow>%n");
        pw.printf("    <IsReadOnly>N</IsReadOnly>%n");
        pw.printf("    <EntityType>U</EntityType>%n");
        if (linkColId > 0) {
            pw.printf("    <AD_Column_ID>%d</AD_Column_ID>%n", linkColId);
        }
        pw.printf("  </AD_Tab>%n");
    }

    private int writeFieldsForTable(java.io.PrintWriter pw, int tabId, String tableName,
                                    Map<String, Integer> columnIds, int fieldIdBase) {
        int count = 0;
        int seqNo = 10;

        // Find all columns for this table
        for (Map.Entry<String, Integer> entry : columnIds.entrySet()) {
            if (entry.getKey().startsWith(tableName + ".")) {
                String colName = entry.getKey().substring(tableName.length() + 1);
                int colId = entry.getValue();

                // Skip audit columns from display
                if (colName.equals("Created") || colName.equals("CreatedBy") ||
                    colName.equals("Updated") || colName.equals("UpdatedBy")) {
                    continue;
                }

                String uuid = UUID.randomUUID().toString();
                boolean isDisplayed = !colName.endsWith("_ID") || colName.equals(tableName + "_ID");

                pw.printf("  <AD_Field type=\"table\">%n");
                pw.printf("    <AD_Field_ID>%d</AD_Field_ID>%n", fieldIdBase + count);
                pw.printf("    <AD_Field_UU>%s</AD_Field_UU>%n", uuid);
                pw.printf("    <AD_Client_ID>0</AD_Client_ID>%n");
                pw.printf("    <AD_Org_ID>0</AD_Org_ID>%n");
                pw.printf("    <IsActive>Y</IsActive>%n");
                pw.printf("    <AD_Tab_ID>%d</AD_Tab_ID>%n", tabId);
                pw.printf("    <AD_Column_ID>%d</AD_Column_ID>%n", colId);
                pw.printf("    <Name>%s</Name>%n", escapeXml(colName.replace("_", " ")));
                pw.printf("    <SeqNo>%d</SeqNo>%n", seqNo);
                pw.printf("    <IsDisplayed>%s</IsDisplayed>%n", isDisplayed ? "Y" : "N");
                pw.printf("    <IsSameLine>N</IsSameLine>%n");
                pw.printf("    <IsReadOnly>N</IsReadOnly>%n");
                pw.printf("    <EntityType>U</EntityType>%n");
                pw.printf("  </AD_Field>%n");

                count++;
                seqNo += 10;
            }
        }
        return count;
    }

    private void writeADMenu(java.io.PrintWriter pw, int menuId, String name, String action, int windowId, boolean isFolder) {
        String uuid = UUID.randomUUID().toString();
        pw.printf("  <AD_Menu type=\"table\">%n");
        pw.printf("    <AD_Menu_ID>%d</AD_Menu_ID>%n", menuId);
        pw.printf("    <AD_Menu_UU>%s</AD_Menu_UU>%n", uuid);
        pw.printf("    <AD_Client_ID>0</AD_Client_ID>%n");
        pw.printf("    <AD_Org_ID>0</AD_Org_ID>%n");
        pw.printf("    <IsActive>Y</IsActive>%n");
        pw.printf("    <Name>%s</Name>%n", escapeXml(name));
        pw.printf("    <IsSummary>%s</IsSummary>%n", isFolder ? "Y" : "N");
        pw.printf("    <IsSOTrx>N</IsSOTrx>%n");
        pw.printf("    <IsReadOnly>N</IsReadOnly>%n");
        pw.printf("    <EntityType>U</EntityType>%n");
        if (action != null) {
            pw.printf("    <Action>%s</Action>%n", action);
        }
        if (windowId > 0) {
            pw.printf("    <AD_Window_ID>%d</AD_Window_ID>%n", windowId);
        }
        pw.printf("  </AD_Menu>%n");
    }

    // ========== IMPORT-SILENT: 2Pack → PostgreSQL (NO iDempiere Runtime) ==========

    /**
     * Import 2Pack directly to PostgreSQL without iDempiere runtime.
     * Mimics PIPO behavior but works completely standalone via JDBC.
     *
     * @param packPath Path to 2Pack.zip or PackOut.xml
     * @return Number of records imported
     */
    public int importSilent(String packPath) throws Exception {
        if (ideConn == null) {
            throw new SQLException("Not connected to PostgreSQL database");
        }

        File packFile = new File(packPath);
        if (!packFile.exists()) {
            throw new Exception("File not found: " + packPath);
        }

        log.info("Silent import: " + packPath);
        operationId = startOperation("IMPORT_SILENT", packPath);

        int importedCount = 0;
        errors = 0;

        try {
            InputStream xmlStream = null;
            ZipFile zipFile = null;

            if (packPath.toLowerCase().endsWith(".zip")) {
                // Extract PackOut.xml from ZIP
                zipFile = new ZipFile(packFile);
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName().toLowerCase();
                    if (name.endsWith("/dict/packout.xml") || name.equals("dict/packout.xml") ||
                        name.endsWith("packout.xml")) {
                        xmlStream = zipFile.getInputStream(entry);
                        log.info("Found: " + entry.getName());
                        break;
                    }
                }
                if (xmlStream == null) {
                    throw new Exception("PackOut.xml not found in ZIP");
                }
            } else {
                xmlStream = new FileInputStream(packFile);
            }

            // Parse XML using SAX
            System.out.println("\n=== Silent Import Started ===");
            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser parser = factory.newSAXParser();
            SilentImportHandler handler = new SilentImportHandler();
            parser.parse(xmlStream, handler);

            importedCount = handler.getImportedCount();

            // Print summary
            System.out.println("\n=== Import Summary ===");
            System.out.println("Total records: " + importedCount);
            System.out.println("  Inserted: " + handler.getInsertCount());
            System.out.println("  Updated:  " + handler.getUpdateCount());

            if (!handler.getElementCounts().isEmpty()) {
                System.out.println("\nBy element type:");
                for (Map.Entry<String, Integer> entry : handler.getElementCounts().entrySet()) {
                    System.out.println("  " + entry.getKey() + ": " + entry.getValue());
                }
            }

            if (!handler.getSkippedElements().isEmpty()) {
                System.out.println("\n[WARN] Skipped elements (no handler):");
                for (String elem : handler.getSkippedElements()) {
                    System.out.println("  - " + elem);
                }
            }

            if (!handler.getMissingTables().isEmpty()) {
                System.out.println("\n[WARN] Tables not in database:");
                for (String table : handler.getMissingTables()) {
                    System.out.println("  - " + table);
                }
            }

            if (errors == 0) {
                ideConn.commit();
                sqliteConn.commit();
                completeOperation(operationId, "SUCCESS");
                System.out.println("\nStatus: SUCCESS (committed)");
            } else {
                ideConn.rollback();
                completeOperation(operationId, "PARTIAL");
                System.out.println("\nStatus: PARTIAL (rolled back, " + errors + " errors)");
            }

            if (zipFile != null) zipFile.close();

        } catch (Exception e) {
            ideConn.rollback();
            completeOperation(operationId, "FAILED");
            throw e;
        }

        return importedCount;
    }

    /**
     * SAX Handler for silent 2Pack import
     */
    private class SilentImportHandler extends DefaultHandler {
        private String currentElement = null;
        private Map<String, String> currentRecord = new HashMap<>();
        private StringBuilder textContent = new StringBuilder();
        private int importedCount = 0;
        private int insertCount = 0;
        private int updateCount = 0;
        private java.util.Set<String> skippedElements = new java.util.HashSet<>();
        private java.util.Set<String> missingTables = new java.util.HashSet<>();
        private Map<String, Integer> elementCounts = new HashMap<>();

        // Tables we know how to import
        private static final java.util.Set<String> SUPPORTED_TABLES = new java.util.HashSet<>(
            java.util.Arrays.asList("AD_ELEMENT", "AD_TABLE", "AD_COLUMN", "AD_WINDOW",
                                    "AD_TAB", "AD_FIELD", "AD_MENU", "AD_PROCESS",
                                    "MP_MAINTAIN", "MP_METER", "MP_JOBSTANDAR",
                                    "A_ASSET", "A_ASSET_GROUP"));

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            textContent.setLength(0);

            String upperName = qName.toUpperCase();

            // Check if this looks like a table element (has underscore, not a field name)
            boolean looksLikeTable = qName.contains("_") &&
                                     !qName.equals("AD_Client_ID") &&
                                     !qName.endsWith("_ID") &&
                                     !qName.endsWith("_UU") &&
                                     Character.isUpperCase(qName.charAt(0));

            if (SUPPORTED_TABLES.contains(upperName) ||
                upperName.startsWith("AD_") || upperName.startsWith("MP_") ||
                upperName.startsWith("A_ASSET")) {
                currentElement = qName;
                currentRecord.clear();
                // Capture attributes (e.g., action="insert")
                for (int i = 0; i < attrs.getLength(); i++) {
                    currentRecord.put("_attr_" + attrs.getQName(i), attrs.getValue(i));
                }
            } else if (looksLikeTable && currentElement == null) {
                // This looks like a table element but we don't have a handler
                skippedElements.add(qName);
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            textContent.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String text = textContent.toString().trim();

            if (currentElement != null && !qName.equalsIgnoreCase(currentElement)) {
                // This is a child element containing a column value
                if (!text.isEmpty()) {
                    currentRecord.put(qName.toUpperCase(), text);
                }
            } else if (qName.equalsIgnoreCase(currentElement) && !currentRecord.isEmpty()) {
                // End of record - import it
                try {
                    importRecord(currentElement.toUpperCase(), currentRecord);
                    importedCount++;
                } catch (Exception e) {
                    log.warning("Error importing " + currentElement + ": " + e.getMessage());
                    logDetail(currentElement, currentRecord.get("NAME"), "ERROR", e.getMessage());
                    errors++;
                }
                currentElement = null;
            }
        }

        public int getImportedCount() { return importedCount; }
        public int getInsertCount() { return insertCount; }
        public int getUpdateCount() { return updateCount; }
        public java.util.Set<String> getSkippedElements() { return skippedElements; }
        public java.util.Set<String> getMissingTables() { return missingTables; }
        public Map<String, Integer> getElementCounts() { return elementCounts; }

        private void importRecord(String tableName, Map<String, String> record) throws SQLException {
            // Skip if no meaningful data
            if (record.size() <= 1) return;

            // Get table metadata
            String pkColumn = tableName.toLowerCase() + "_id";
            String uuColumn = tableName.toLowerCase() + "_uu";

            // Check if table exists
            java.util.Set<String> dbColumns = getTableColumns(tableName.toLowerCase());
            if (dbColumns.isEmpty()) {
                missingTables.add(tableName);
                System.out.println("  [SKIP] Table not in database: " + tableName);
                return;
            }

            // Check if record exists (by UUID or ID)
            String uuid = record.get(uuColumn.toUpperCase());
            String idStr = record.get(pkColumn.toUpperCase());
            boolean exists = false;

            if (uuid != null && !uuid.isEmpty()) {
                String checkSql = "SELECT 1 FROM " + tableName + " WHERE " + uuColumn + " = ?";
                try (PreparedStatement ps = ideConn.prepareStatement(checkSql)) {
                    ps.setString(1, uuid);
                    try (ResultSet rs = ps.executeQuery()) {
                        exists = rs.next();
                    }
                } catch (SQLException e) {
                    // Table might not exist, continue with insert
                }
            }

            // Get record identifier for logging
            String recordName = record.getOrDefault("NAME",
                               record.getOrDefault("COLUMNNAME",
                               record.getOrDefault("TABLENAME",
                               record.getOrDefault(pkColumn.toUpperCase(), "?"))));

            if (exists) {
                // UPDATE existing record
                updateRecord(tableName, record, uuColumn, uuid);
                updateCount++;
                System.out.println("  [UPDATE] " + tableName + ": " + recordName);
            } else {
                // INSERT new record
                insertRecord(tableName, record, pkColumn);
                insertCount++;
                System.out.println("  [INSERT] " + tableName + ": " + recordName);
            }

            // Track counts by element type
            elementCounts.put(tableName, elementCounts.getOrDefault(tableName, 0) + 1);

            logDetail(tableName, recordName, exists ? "UPDATE" : "INSERT", "OK");
        }

        private void insertRecord(String tableName, Map<String, String> record, String pkColumn) throws SQLException {
            StringBuilder sql = new StringBuilder("INSERT INTO ");
            sql.append(tableName.toLowerCase()).append(" (");
            StringBuilder values = new StringBuilder(" VALUES (");

            List<String> cols = new ArrayList<>();
            List<String> vals = new ArrayList<>();

            // Get actual columns from database
            java.util.Set<String> dbColumns = getTableColumns(tableName.toLowerCase());

            for (Map.Entry<String, String> entry : record.entrySet()) {
                String col = entry.getKey();
                if (col.startsWith("_attr_")) continue;  // Skip attributes

                // Check if column exists in table
                if (!dbColumns.contains(col.toLowerCase())) continue;

                // Skip audit columns - let DB handle them
                if (col.equalsIgnoreCase("CREATED") || col.equalsIgnoreCase("CREATEDBY") ||
                    col.equalsIgnoreCase("UPDATED") || col.equalsIgnoreCase("UPDATEDBY")) {
                    continue;
                }

                cols.add(col.toLowerCase());
                vals.add(formatValueForDB(col, entry.getValue()));
            }

            // Add audit columns
            if (dbColumns.contains("created")) {
                cols.add("created");
                vals.add("NOW()");
            }
            if (dbColumns.contains("createdby")) {
                cols.add("createdby");
                vals.add("0");
            }
            if (dbColumns.contains("updated")) {
                cols.add("updated");
                vals.add("NOW()");
            }
            if (dbColumns.contains("updatedby")) {
                cols.add("updatedby");
                vals.add("0");
            }

            sql.append(String.join(", ", cols));
            sql.append(")");
            values.append(String.join(", ", vals));
            values.append(")");

            String insertSql = sql.toString() + values.toString();

            try (Statement stmt = ideConn.createStatement()) {
                stmt.executeUpdate(insertSql);
            }
        }

        private void updateRecord(String tableName, Map<String, String> record,
                                  String uuColumn, String uuid) throws SQLException {
            StringBuilder sql = new StringBuilder("UPDATE ");
            sql.append(tableName.toLowerCase()).append(" SET ");

            List<String> setClauses = new ArrayList<>();
            java.util.Set<String> dbColumns = getTableColumns(tableName.toLowerCase());

            for (Map.Entry<String, String> entry : record.entrySet()) {
                String col = entry.getKey();
                if (col.startsWith("_attr_")) continue;
                if (col.equalsIgnoreCase(uuColumn)) continue;  // Don't update UUID

                if (!dbColumns.contains(col.toLowerCase())) continue;

                // Skip audit columns
                if (col.equalsIgnoreCase("CREATED") || col.equalsIgnoreCase("CREATEDBY")) {
                    continue;
                }

                setClauses.add(col.toLowerCase() + " = " + formatValueForDB(col, entry.getValue()));
            }

            // Update timestamp
            if (dbColumns.contains("updated")) {
                setClauses.add("updated = NOW()");
            }
            if (dbColumns.contains("updatedby")) {
                setClauses.add("updatedby = 0");
            }

            sql.append(String.join(", ", setClauses));
            sql.append(" WHERE ").append(uuColumn).append(" = '").append(uuid).append("'");

            try (Statement stmt = ideConn.createStatement()) {
                stmt.executeUpdate(sql.toString());
            }
        }

        private String formatValueForDB(String column, String value) {
            if (value == null || value.isEmpty()) {
                return "NULL";
            }

            // Handle SQL functions
            if (value.equalsIgnoreCase("NOW()") || value.equalsIgnoreCase("CURRENT_TIMESTAMP")) {
                return "NOW()";
            }
            if (value.equalsIgnoreCase("uuid_generate_v4()")) {
                return "'" + UUID.randomUUID().toString() + "'";
            }

            // Numeric columns
            String colLower = column.toLowerCase();
            if (colLower.endsWith("_id") || colLower.equals("seqno") || colLower.equals("tablevel") ||
                colLower.equals("fieldlength") || colLower.equals("ad_reference_id")) {
                try {
                    return String.valueOf(Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    return "NULL";
                }
            }

            // Boolean columns
            if (colLower.startsWith("is") || colLower.equals("isactive")) {
                if (value.equalsIgnoreCase("Y") || value.equalsIgnoreCase("true") || value.equals("1")) {
                    return "'Y'";
                } else if (value.equalsIgnoreCase("N") || value.equalsIgnoreCase("false") || value.equals("0")) {
                    return "'N'";
                }
            }

            // String columns - escape quotes
            return "'" + value.replace("'", "''") + "'";
        }

        private java.util.Set<String> getTableColumns(String tableName) throws SQLException {
            java.util.Set<String> columns = new java.util.HashSet<>();
            try {
                java.sql.DatabaseMetaData meta = ideConn.getMetaData();
                try (ResultSet rs = meta.getColumns(null, null, tableName, null)) {
                    while (rs.next()) {
                        columns.add(rs.getString("COLUMN_NAME").toLowerCase());
                    }
                }
            } catch (Exception e) {
                log.fine("Could not get columns for " + tableName + ": " + e.getMessage());
            }
            return columns;
        }
    }

    // ========== XML-UPDATE: Apply SQL to PackOut.xml (NO database) ==========

    /**
     * Apply SQL UPDATE statements to PackOut.xml directly (no database needed).
     * Port of sql2packout.py - preserves original XML formatting.
     *
     * @param packoutXml Path to PackOut.xml file
     * @param sqlInput Path to SQL file or directory containing .sql files
     * @param outputXml Output file path (null = overwrite input)
     * @return Number of fields updated
     */
    public int xmlUpdate(String packoutXml, String sqlInput, String outputXml) throws Exception {
        System.out.println("\n=== XML Update Started ===");
        System.out.println("PackOut: " + packoutXml);
        System.out.println("SQL source: " + sqlInput);

        if (outputXml == null || outputXml.isEmpty()) {
            outputXml = packoutXml;
            System.out.println("Output: (in-place)");
        } else {
            System.out.println("Output: " + outputXml);
        }

        // Collect SQL files
        List<File> sqlFiles = new ArrayList<>();
        File sqlPath = new File(sqlInput);
        if (sqlPath.isDirectory()) {
            File[] files = sqlPath.listFiles((dir, name) -> name.toLowerCase().endsWith(".sql"));
            if (files != null) {
                Arrays.sort(files);
                sqlFiles.addAll(Arrays.asList(files));
            }
        } else {
            sqlFiles.add(sqlPath);
        }

        System.out.println("\nSQL files found: " + sqlFiles.size());

        // Parse SQL to extract updates
        // Structure: updates[table][key_type][key_value] = {field: value}
        Map<String, Map<String, Map<String, Map<String, String>>>> updates = new HashMap<>();

        for (File sqlFile : sqlFiles) {
            System.out.println("  Parsing: " + sqlFile.getName());
            parseSqlFile(sqlFile, updates);
        }

        // Count and display rules
        int totalRules = 0;
        System.out.println("\nUpdate rules extracted:");
        for (Map.Entry<String, Map<String, Map<String, Map<String, String>>>> tableEntry : updates.entrySet()) {
            String table = tableEntry.getKey();
            int tableRules = 0;
            for (Map<String, Map<String, String>> keyUpdates : tableEntry.getValue().values()) {
                tableRules += keyUpdates.size();
            }
            System.out.println("  " + table + ": " + tableRules + " rules");
            totalRules += tableRules;
        }
        System.out.println("Total: " + totalRules + " rules");

        // Read file as lines
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(packoutXml))) {
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
        }

        // Process line by line
        Map<String, Integer> counts = new HashMap<>();
        String[] xmlElements = {"AD_Element", "AD_Window", "AD_Tab", "AD_Field", "AD_Process", "AD_Form", "AD_Menu"};

        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);

            for (String xmlElem : xmlElements) {
                // Check for element start tag (not _ID suffix)
                if (!(line.contains("<" + xmlElem + " ") || line.contains("<" + xmlElem + ">")) ||
                    line.contains("</" + xmlElem + ">")) {
                    continue;
                }

                String table = xmlElem.toUpperCase();
                if (!updates.containsKey(table)) {
                    continue;
                }

                // Find element end (can span 5000+ lines for AD_Window)
                int elemStart = i;
                int elemEnd = i;
                int depth = 1;
                for (int j = i + 1; j < Math.min(i + 50000, lines.size()); j++) {
                    String jLine = lines.get(j);
                    if ((jLine.contains("<" + xmlElem + " ") || jLine.contains("<" + xmlElem + ">")) &&
                        !jLine.contains("</" + xmlElem + ">")) {
                        depth++;
                    }
                    if (jLine.contains("</" + xmlElem + ">")) {
                        depth--;
                        if (depth == 0) {
                            elemEnd = j;
                            break;
                        }
                    }
                }

                // Collect tag values in this element
                Map<String, int[]> elemTags = new HashMap<>();  // tag -> [lineIndex, value as string ref]
                Map<String, String> elemTagValues = new HashMap<>();
                String entityType = null;

                for (int j = elemStart; j <= elemEnd; j++) {
                    String jLine = lines.get(j);
                    String et = getTagValue(jLine, "EntityType");
                    if (et != null) entityType = et;

                    for (String tag : new String[]{"ColumnName", "Name", "AD_Element_ID", "AD_Window_ID",
                            "AD_Tab_ID", "AD_Field_ID", "AD_Process_ID", "AD_Form_ID", "AD_Menu_ID"}) {
                        String val = getTagValue(jLine, tag);
                        if (val != null) {
                            elemTags.put(tag, new int[]{j});
                            elemTagValues.put(tag, val);
                        }
                    }
                }

                // Skip core dictionary (EntityType='D')
                if ("D".equals(entityType)) {
                    i = elemEnd;
                    break;
                }

                // Match with update rules
                boolean matched = false;
                Map<String, Map<String, Map<String, String>>> tableUpdates = updates.get(table);

                for (Map.Entry<String, Map<String, Map<String, String>>> keyEntry : tableUpdates.entrySet()) {
                    String keyType = keyEntry.getKey();
                    if (!elemTagValues.containsKey(keyType)) continue;

                    String keyVal = elemTagValues.get(keyType);
                    Map<String, Map<String, String>> keyUpdates = keyEntry.getValue();

                    if (keyUpdates.containsKey(keyVal)) {
                        Map<String, String> valsToApply = keyUpdates.get(keyVal);

                        for (Map.Entry<String, String> fieldEntry : valsToApply.entrySet()) {
                            String field = fieldEntry.getKey();
                            String newVal = fieldEntry.getValue();

                            // Find and update the field line
                            for (int j = elemStart; j <= elemEnd; j++) {
                                String oldVal = getTagValue(lines.get(j), field);
                                if (oldVal != null) {
                                    String newLine = setTagValue(lines.get(j), field, newVal);
                                    if (!newLine.equals(lines.get(j))) {
                                        lines.set(j, newLine);
                                        counts.put(xmlElem, counts.getOrDefault(xmlElem, 0) + 1);
                                    }
                                    break;
                                }
                            }
                        }
                        matched = true;
                    }
                }

                if (matched) {
                    i = elemEnd;
                }
                break;
            }
            i++;
        }

        // Write output
        System.out.println("\nWriting: " + outputXml);
        try (PrintWriter pw = new PrintWriter(new FileWriter(outputXml))) {
            for (String line : lines) {
                pw.println(line);
            }
        }

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();

        System.out.println("\n=== XML Update Summary ===");
        System.out.println("Fields updated by element type:");
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            System.out.println("  " + entry.getKey() + ": " + entry.getValue());
        }
        System.out.println("Total fields updated: " + total);

        if (total == 0 && totalRules > 0) {
            System.out.println("\n[WARN] No fields updated despite having " + totalRules + " rules.");
            System.out.println("       Possible reasons:");
            System.out.println("       - Element names in SQL don't match PackOut.xml");
            System.out.println("       - WHERE keys (ColumnName, ID, Name) don't match");
            System.out.println("       - Elements have EntityType='D' (core, skipped)");
        }

        return total;
    }

    private void parseSqlFile(File sqlFile, Map<String, Map<String, Map<String, Map<String, String>>>> updates) throws Exception {
        String content = new String(java.nio.file.Files.readAllBytes(sqlFile.toPath()));
        // Remove comments
        content = content.replaceAll("--.*$", "");

        // Split by semicolon + newline
        String[] statements = content.split(";\\s*\\n");

        for (String stmt : statements) {
            stmt = stmt.trim();
            if (!stmt.toUpperCase().startsWith("UPDATE")) continue;

            // Parse: UPDATE table SET col='val',... WHERE condition
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "UPDATE\\s+(\\w+)\\s+SET\\s+(.+?)\\s+WHERE\\s+(.+)",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = p.matcher(stmt);

            if (!m.matches()) continue;

            String table = m.group(1).toUpperCase();
            String setClause = m.group(2);
            String whereClause = m.group(3);

            // Parse SET values
            Map<String, String> vals = new HashMap<>();
            java.util.regex.Pattern setP = java.util.regex.Pattern.compile("(\\w+)\\s*=\\s*'((?:[^']|'')*)'");
            java.util.regex.Matcher setM = setP.matcher(setClause);
            while (setM.find()) {
                vals.put(setM.group(1), setM.group(2).replace("''", "'"));
            }

            if (vals.isEmpty()) continue;

            // Parse WHERE key
            String keyType = null, keyVal = null;

            // Check ColumnName first
            java.util.regex.Matcher colM = java.util.regex.Pattern.compile(
                "ColumnName\\s*=\\s*'([^']+)'", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(whereClause);
            if (colM.find()) {
                keyType = "ColumnName";
                keyVal = colM.group(1);
            } else {
                // Check ID patterns
                java.util.regex.Matcher idM = java.util.regex.Pattern.compile(
                    "(AD_(?:Element|Window|Tab|Field|Process|Form|Menu)_ID)\\s*=\\s*(\\d+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(whereClause);
                if (idM.find()) {
                    keyType = idM.group(1);
                    keyVal = idM.group(2);
                } else {
                    // Check Name
                    java.util.regex.Matcher nameM = java.util.regex.Pattern.compile(
                        "(?<![A-Za-z_])Name\\s*=\\s*'([^']+)'", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(whereClause);
                    if (nameM.find()) {
                        keyType = "Name";
                        keyVal = nameM.group(1);
                    }
                }
            }

            if (keyType != null && keyVal != null) {
                updates.computeIfAbsent(table, k -> new HashMap<>())
                       .computeIfAbsent(keyType, k -> new HashMap<>())
                       .computeIfAbsent(keyVal, k -> new HashMap<>())
                       .putAll(vals);
            }
        }
    }

    private String getTagValue(String line, String tag) {
        // Match <tag>value</tag>
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("<" + tag + ">([^<]*)</" + tag + ">");
        java.util.regex.Matcher m = p.matcher(line);
        if (m.find()) {
            return m.group(1);
        }
        // Match self-closing <tag/> or <tag />
        if (line.matches(".*<" + tag + "\\s*/>.*")) {
            return "";
        }
        return null;
    }

    private String setTagValue(String line, String tag, String value) {
        // Replace <tag>old</tag>
        String newLine = line.replaceAll("<" + tag + ">[^<]*</" + tag + ">",
                                         "<" + tag + ">" + escapeXml(value) + "</" + tag + ">");
        if (!newLine.equals(line)) {
            return newLine;
        }
        // Replace <tag/> or <tag />
        return line.replaceAll("<" + tag + "\\s*/>",
                              "<" + tag + ">" + escapeXml(value) + "</" + tag + ">");
    }

    // ========== XML-SYNC: Sync PackOut.xml from database ==========

    /**
     * Update PackOut.xml with current database Help/Name values.
     * Port of update_packout.py - only updates EntityType='U' elements.
     * Requires PostgreSQL connection.
     *
     * @param packoutXml Path to PackOut.xml file
     * @param outputXml Output file path (null = overwrite input)
     * @return Number of elements updated
     */
    public int xmlSync(String packoutXml, String outputXml) throws Exception {
        if (ideConn == null) {
            throw new SQLException("Not connected to PostgreSQL database");
        }

        System.out.println("\n=== XML Sync Started ===");
        System.out.println("PackOut: " + packoutXml);
        System.out.println("Database: " + ideConn.getMetaData().getURL());

        if (outputXml == null || outputXml.isEmpty()) {
            outputXml = packoutXml;
            System.out.println("Output: (in-place)");
        } else {
            System.out.println("Output: " + outputXml);
        }

        // Read and parse XML
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        org.w3c.dom.Document doc = builder.parse(new File(packoutXml));

        Map<String, Integer> counts = new HashMap<>();
        Map<String, Integer> skipped = new HashMap<>();

        System.out.println("\nSyncing elements from database...");

        // Update each element type
        updateElementsFromDB(doc, "AD_Element", "ColumnName",
            "SELECT Name, PrintName, Help, Description FROM AD_Element WHERE ColumnName = ? AND AD_Client_ID = 0 AND EntityType = 'U'",
            new String[]{"Name", "PrintName", "Help", "Description"}, counts, skipped);

        updateElementsFromDB(doc, "AD_Window", "AD_Window_ID",
            "SELECT Name, Help, Description FROM AD_Window WHERE AD_Window_ID = ? AND EntityType = 'U'",
            new String[]{"Name", "Help", "Description"}, counts, skipped);

        updateElementsFromDB(doc, "AD_Tab", "AD_Tab_ID",
            "SELECT Name, Help, Description FROM AD_Tab WHERE AD_Tab_ID = ? AND EntityType = 'U'",
            new String[]{"Name", "Help", "Description"}, counts, skipped);

        updateElementsFromDB(doc, "AD_Field", "AD_Field_ID",
            "SELECT Name, Help, Description FROM AD_Field WHERE AD_Field_ID = ? AND EntityType = 'U'",
            new String[]{"Name", "Help", "Description"}, counts, skipped);

        updateElementsFromDB(doc, "AD_Process", "AD_Process_ID",
            "SELECT Name, Help, Description FROM AD_Process WHERE AD_Process_ID = ? AND EntityType = 'U'",
            new String[]{"Name", "Help", "Description"}, counts, skipped);

        updateElementsFromDB(doc, "AD_Form", "AD_Form_ID",
            "SELECT Name, Help, Description FROM AD_Form WHERE AD_Form_ID = ? AND EntityType = 'U'",
            new String[]{"Name", "Help", "Description"}, counts, skipped);

        updateElementsFromDB(doc, "AD_Menu", "AD_Menu_ID",
            "SELECT Name, Description FROM AD_Menu WHERE AD_Menu_ID = ? AND EntityType = 'U'",
            new String[]{"Name", "Description"}, counts, skipped);

        // Write output
        System.out.println("\nWriting: " + outputXml);
        javax.xml.transform.TransformerFactory tf = javax.xml.transform.TransformerFactory.newInstance();
        javax.xml.transform.Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "UTF-8");
        transformer.transform(new javax.xml.transform.dom.DOMSource(doc),
                             new javax.xml.transform.stream.StreamResult(new File(outputXml)));

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        int totalSkipped = skipped.values().stream().mapToInt(Integer::intValue).sum();

        System.out.println("\n=== XML Sync Summary ===");
        System.out.println("Elements synced (EntityType='U'):");
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > 0) {
                System.out.println("  " + entry.getKey() + ": " + entry.getValue());
            }
        }
        System.out.println("Total synced: " + total);
        System.out.println("\nSkipped (EntityType!='U'): " + totalSkipped);

        return total;
    }

    private void updateElementsFromDB(org.w3c.dom.Document doc, String elementName, String keyField,
                                      String sql, String[] updateFields,
                                      Map<String, Integer> counts, Map<String, Integer> skipped) throws SQLException {
        org.w3c.dom.NodeList nodes = doc.getElementsByTagName(elementName);

        for (int i = 0; i < nodes.getLength(); i++) {
            org.w3c.dom.Element elem = (org.w3c.dom.Element) nodes.item(i);

            // Check EntityType
            org.w3c.dom.NodeList etNodes = elem.getElementsByTagName("EntityType");
            String entityType = etNodes.getLength() > 0 ? etNodes.item(0).getTextContent() : null;

            if (!"U".equals(entityType)) {
                skipped.put(elementName, skipped.getOrDefault(elementName, 0) + 1);
                continue;
            }

            // Get key value
            org.w3c.dom.NodeList keyNodes = elem.getElementsByTagName(keyField);
            if (keyNodes.getLength() == 0) continue;

            String keyValue = keyNodes.item(0).getTextContent();
            if (keyValue == null || keyValue.isEmpty()) continue;

            try (PreparedStatement ps = ideConn.prepareStatement(sql)) {
                if (keyField.endsWith("_ID")) {
                    ps.setInt(1, Integer.parseInt(keyValue));
                } else {
                    ps.setString(1, keyValue);
                }

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        for (int f = 0; f < updateFields.length; f++) {
                            String fieldName = updateFields[f];
                            String value = rs.getString(f + 1);

                            org.w3c.dom.NodeList fieldNodes = elem.getElementsByTagName(fieldName);
                            if (fieldNodes.getLength() > 0) {
                                fieldNodes.item(0).setTextContent(value != null ? value : "");
                            }
                        }
                        counts.put(elementName, counts.getOrDefault(elementName, 0) + 1);
                    }
                }
            } catch (NumberFormatException e) {
                // Skip invalid IDs
            }
        }
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

                // ===== AD MODEL SYNC =====
                case "sync-ad":
                    connectToIde(piper, args);
                    piper.syncAD();
                    System.out.println("AD model synced to SQLite");
                    break;

                case "info":
                    piper.showADInfo();
                    break;

                // ===== SQL TO 2PACK =====
                case "sql2pack":
                    if (args.length < 3) {
                        System.out.println("Missing SQL file path");
                        System.out.println("Usage: SilentPiper <sqlite.db> sql2pack <data.sql>");
                        return;
                    }
                    String packUU = piper.sql2pack(args[2]);
                    System.out.println("SQL staged. Pack UUID: " + packUU);
                    break;

                case "packs":
                    if (args.length > 2) {
                        piper.showPackDetail(args[2]);
                    } else {
                        piper.showPacks();
                    }
                    break;

                case "export-2pack":
                    if (args.length < 3) {
                        System.out.println("Missing pack name");
                        System.out.println("Usage: SilentPiper <sqlite.db> export-2pack <pack-name> [output-dir]");
                        return;
                    }
                    String outDir = args.length > 3 ? args[3] : ".";
                    File zipFile = piper.export2Pack(args[2], outDir);
                    System.out.println("Created: " + zipFile.getAbsolutePath());
                    break;

                case "packout":
                    // Export SQLite staged data to 2Pack
                    if (args.length < 3) {
                        System.out.println("Missing pack name");
                        System.out.println("Usage: SilentPiper <sqlite.db> packout <pack-name> [output-dir] [client-id]");
                        System.out.println("Example: SilentPiper ninja.db packout GW_SampleData ./output 11");
                        return;
                    }
                    String packoutDir = args.length > 3 ? args[3] : ".";
                    int clientId = args.length > 4 ? Integer.parseInt(args[4]) : 11;
                    String packoutPath = piper.packout(args[2], packoutDir, clientId);
                    System.out.println("Created: " + packoutPath);
                    break;

                case "packout-ad":
                    // Export AD model from PostgreSQL to 2Pack
                    if (args.length < 3) {
                        System.out.println("Missing table prefix");
                        System.out.println("Usage: SilentPiper <sqlite.db> packout-ad <table-prefix> [output-dir]");
                        System.out.println("Example: SilentPiper ninja.db packout-ad MP_ ./output");
                        return;
                    }
                    connectToIde(piper, args);
                    String adPackoutDir = args.length > 3 ? args[3] : ".";
                    String adPackoutPath = piper.packoutAD(args[2], adPackoutDir, false);
                    System.out.println("Created: " + adPackoutPath);
                    break;

                case "packout-model":
                    // Generate AD model 2Pack from SQLite staged model (OFFLINE - no PG needed)
                    if (args.length < 3) {
                        System.out.println("Missing bundle name");
                        System.out.println("Usage: SilentPiper <sqlite.db> packout-model <bundle-name> [output-dir]");
                        System.out.println("Example: SilentPiper ninja.db packout-model HRMIS ./output");
                        System.out.println("  - Generates PIPO-compatible 2Pack from staged Excel model");
                        System.out.println("  - Works COMPLETELY OFFLINE (no PostgreSQL connection)");
                        return;
                    }
                    String modelPackDir = args.length > 3 ? args[3] : ".";
                    String modelPackPath = piper.packoutModel(args[2], modelPackDir);
                    System.out.println("Created: " + modelPackPath);
                    break;

                case "import-silent":
                    // Import 2Pack directly to PostgreSQL (no iDempiere runtime)
                    if (args.length < 3) {
                        System.out.println("Missing 2pack file path");
                        System.out.println("Usage: SilentPiper <sqlite.db> import-silent <2pack.zip>");
                        System.out.println("Example: SilentPiper ninja.db import-silent ./HRMIS_Model_1_0_0.zip");
                        System.out.println("  - Imports 2Pack directly via JDBC (no iDempiere runtime)");
                        System.out.println("  - Supports both AD model and data packs");
                        return;
                    }
                    connectToIde(piper, args);
                    int silentImported = piper.importSilent(args[2]);
                    System.out.println("Imported " + silentImported + " records via silent import");
                    break;

                // ===== DIRECT SQL INJECTION =====
                case "apply-data":
                    if (args.length < 3) {
                        System.out.println("Missing pack name");
                        System.out.println("Usage: SilentPiper <sqlite.db> apply-data <pack-name>");
                        return;
                    }
                    connectToIde(piper, args);
                    int appliedCount = piper.applyData(args[2]);
                    System.out.println("Applied " + appliedCount + " records to iDempiere");
                    break;

                case "applied":
                    if (args.length < 3) {
                        System.out.println("Missing pack name");
                        System.out.println("Usage: SilentPiper <sqlite.db> applied <pack-name>");
                        return;
                    }
                    piper.showApplied(args[2]);
                    break;

                case "clean-data":
                    if (args.length < 3) {
                        System.out.println("Missing pack name");
                        System.out.println("Usage: SilentPiper <sqlite.db> clean-data <pack-name>");
                        return;
                    }
                    connectToIde(piper, args);
                    int cleaned = piper.cleanData(args[2]);
                    System.out.println("Deleted " + cleaned + " records from iDempiere");
                    break;

                // ===== XML TOOLS (PackOut.xml manipulation) =====
                case "xml-update":
                    // Apply SQL UPDATE statements to PackOut.xml (NO database needed)
                    if (args.length < 4) {
                        System.out.println("Missing arguments");
                        System.out.println("Usage: SilentPiper <sqlite.db> xml-update <packout.xml> <sql_file_or_dir> [output.xml]");
                        System.out.println("Example: SilentPiper ninja.db xml-update PackOut.xml ./sql_updates/");
                        System.out.println("  - Applies SQL UPDATE statements to PackOut.xml directly");
                        System.out.println("  - NO database connection needed (pure XML transformation)");
                        return;
                    }
                    String xmlUpdateOutput = args.length > 4 ? args[4] : null;
                    int xmlUpdated = piper.xmlUpdate(args[2], args[3], xmlUpdateOutput);
                    System.out.println("Updated " + xmlUpdated + " fields in PackOut.xml");
                    break;

                case "xml-sync":
                    // Sync PackOut.xml from database (requires PostgreSQL)
                    if (args.length < 3) {
                        System.out.println("Missing arguments");
                        System.out.println("Usage: SilentPiper <sqlite.db> xml-sync <packout.xml> [output.xml]");
                        System.out.println("Example: SilentPiper ninja.db xml-sync PackOut.xml PackOut_synced.xml");
                        System.out.println("  - Updates PackOut.xml with current database values");
                        System.out.println("  - Only updates EntityType='U' (user/module elements)");
                        System.out.println("  - Requires PostgreSQL connection");
                        return;
                    }
                    connectToIde(piper, args);
                    String xmlSyncOutput = args.length > 3 ? args[3] : null;
                    int xmlSynced = piper.xmlSync(args[2], xmlSyncOutput);
                    System.out.println("Synced " + xmlSynced + " elements from database");
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
        System.out.println("SilentPiper - Lite iDempiere Modeler with SQLite Staging");
        System.out.println();
        System.out.println("AD MODEL (sync iDempiere → SQLite):");
        System.out.println("  sync-ad                     - Pull AD model from PG to SQLite");
        System.out.println("  info                        - Show AD model info in SQLite");
        System.out.println();
        System.out.println("SQL DATA (SQL → SQLite → PostgreSQL):");
        System.out.println("  sql2pack <data.sql>         - Parse SQL, stage in SQLite");
        System.out.println("  packs [name]                - Show staged data packs");
        System.out.println("  apply-data <pack>           - Insert data directly to iDempiere PG");
        System.out.println("  applied <pack>              - Show applied records (tracked)");
        System.out.println("  clean-data <pack>           - Delete applied records (cascading)");
        System.out.println("  export-2pack <pack> [dir]   - Generate 2Pack.zip for distribution");
        System.out.println();
        System.out.println("PACKOUT (Generate PIPO-compatible 2Pack):");
        System.out.println("  packout <pack> [dir] [client] - Export SQLite staged data to 2Pack");
        System.out.println("  packout-ad <prefix> [dir]     - Export AD model from PG to 2Pack");
        System.out.println("  packout-model <bundle> [dir]  - Generate AD 2Pack from SQLite model (OFFLINE)");
        System.out.println();
        System.out.println("SILENT IMPORT (2Pack → PostgreSQL, NO iDempiere runtime):");
        System.out.println("  import-silent <2pack.zip>     - Import 2Pack directly to PG via JDBC");
        System.out.println();
        System.out.println("MODEL STAGING (Excel → SQLite → iDempiere):");
        System.out.println("  stage <excel.xlsx>          - Parse Excel to SQLite");
        System.out.println("  show [bundle]               - Show staged models");
        System.out.println("  apply <bundle> [dryrun]     - Apply model to iDempiere");
        System.out.println("  rollback <bundle>           - Rollback applied model");
        System.out.println();
        System.out.println("2PACK IMPORT:");
        System.out.println("  import <2pack.zip>          - Import 2Pack to iDempiere");
        System.out.println("  validate <2pack.zip>        - Validate 2Pack (dry run)");
        System.out.println();
        System.out.println("HISTORY:");
        System.out.println("  history [limit]             - Show operation history");
        System.out.println();
        System.out.println("XML TOOLS (PackOut.xml manipulation):");
        System.out.println("  xml-update <xml> <sql> [out]  - Apply SQL UPDATEs to PackOut.xml (OFFLINE)");
        System.out.println("  xml-sync <xml> [out]          - Sync PackOut.xml from database");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Complete Ninja workflow (Excel → 2Pack, no iDempiere runtime):");
        System.out.println("  SilentPiper ninja.db stage HRMIS.xlsx");
        System.out.println("  SilentPiper ninja.db packout-model HRMIS ./output");
        System.out.println("  SilentPiper ninja.db import-silent ./output/HRMIS_Model_1_0_0.zip");
        System.out.println();
        System.out.println("  # Data packs:");
        System.out.println("  SilentPiper ninja.db sql2pack GardenWorld.sql");
        System.out.println("  SilentPiper ninja.db apply-data GardenWorld_SampleData");
        System.out.println("  SilentPiper ninja.db applied GardenWorld_SampleData");
        System.out.println("  SilentPiper ninja.db clean-data GardenWorld_SampleData");
        System.out.println("  SilentPiper ninja.db packout GW_Sample ./output 11");
        System.out.println();
        System.out.println("  # XML tools (update existing PackOut.xml):");
        System.out.println("  SilentPiper ninja.db xml-update PackOut.xml ./sql_updates/");
        System.out.println("  SilentPiper ninja.db xml-sync PackOut.xml PackOut_synced.xml");
    }
}
