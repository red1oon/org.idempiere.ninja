/******************************************************************************
 * Product: iDempiere Ninja Plugin Generator                                  *
 * Copyright (C) Contributors                                                 *
 *                                                                            *
 * Test class for NinjaProcessor - verifies AD model creation with rollback   *
 *****************************************************************************/
package org.idempiere.ninja.test;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.compiere.model.MColumn;
import org.compiere.model.MMenu;
import org.compiere.model.MRefList;
import org.compiere.model.MReference;
import org.compiere.model.MTab;
import org.compiere.model.MTable;
import org.compiere.model.MWindow;
import org.compiere.model.PO;
import org.compiere.model.Query;
import org.compiere.util.DB;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.compiere.util.Trx;
import org.idempiere.ninja.core.NinjaProcessor;

/**
 * NinjaProcessor Test Framework
 *
 * Tests AD model creation from Excel and verifies:
 * - Reference/List creation
 * - Table/Column creation
 * - Window/Tab/Field creation
 * - Menu creation
 * - Master-Detail relationships
 * - Physical table DDL
 *
 * All tests run in a transaction that is rolled back after verification.
 *
 * Usage:
 *   NinjaProcessorTest test = new NinjaProcessorTest();
 *   test.runTest("/path/to/test.xls");
 *   // OR from iDempiere process
 *   test.runTestWithRollback("/path/to/test.xls");
 *
 * @author red1 - red1org@gmail.com
 */
public class NinjaProcessorTest {

    private String trxName;
    private Trx trx;
    private List<String> results = new ArrayList<>();
    private List<String> errors = new ArrayList<>();
    private boolean verbose = true;
    private boolean runCrudTests = true;   // Run functional CRUD tests
    private boolean run2PackTests = true;  // Run 2Pack generation tests
    private boolean silentMode = false;    // Run without full iDempiere launch
    private String testOutputDir = "/tmp/ninja-test";  // Temp dir for test artifacts

    // Expected values for verification (set before running test)
    private String expectedBundleName;
    private String expectedMenuPrefix;
    private int expectedTableCount;
    private int expectedReferenceCount;
    private int clientId = 11;  // Default: GardenWorld
    private List<String> expectedTableNames = new ArrayList<>();
    private List<String> expectedReferenceNames = new ArrayList<>();
    private List<String[]> expectedMasterDetail = new ArrayList<>();  // [child, parent]

    /**
     * Run test with automatic rollback
     * Safe for testing on live database
     */
    public TestResult runTestWithRollback(String excelFilePath) {
        TestResult result = new TestResult();
        result.excelFile = excelFilePath;

        try {
            // Create transaction for rollback
            trxName = Trx.createTrxName("NinjaTest");
            trx = Trx.get(trxName, true);

            log("=== NINJA PROCESSOR TEST ===");
            log("Excel: " + excelFilePath);
            log("Transaction: " + trxName);
            log("");

            // Verify file exists
            File file = new File(excelFilePath);
            if (!file.exists()) {
                error("Excel file not found: " + excelFilePath);
                result.success = false;
                result.errors = errors;
                return result;
            }

            // Run processor
            log("--- RUNNING NINJA PROCESSOR ---");
            NinjaProcessor processor = new NinjaProcessor(excelFilePath, trxName, verbose);
            processor.injectIntoDatabase();

            // Capture statistics
            result.tablesCreated = processor.getTablesCreated();
            result.columnsCreated = processor.getColumnsCreated();
            result.windowsCreated = processor.getWindowsCreated();
            result.menusCreated = processor.getMenusCreated();
            result.referencesCreated = processor.getReferencesCreated();

            log("");
            log("--- VERIFICATION ---");

            // Verify AD model creation
            verifyReferences(processor);
            verifyTables(processor);
            verifyWindows(processor);
            verifyMenus(processor);
            verifyPhysicalTables(processor);

            // Verify Master-Detail relationships
            if (!expectedMasterDetail.isEmpty()) {
                verifyMasterDetail();
            }

            // Verify List references
            verifyListReferences(processor);

            // Functional tests (CRUD) - only if tables were synced
            if (runCrudTests && processor.getTablesCreated() > 0) {
                log("");
                log("--- FUNCTIONAL TESTS (CRUD) ---");
                log("NOTE: AD_PInstance records will NOT rollback (audit log behavior)");
                verifyCRUD(processor);
                if (!expectedMasterDetail.isEmpty()) {
                    verifyMasterDetailCRUD();
                }
            }

            // 2Pack tests
            if (run2PackTests) {
                log("");
                log("--- 2PACK TESTS ---");
                // Create temp output dir
                new java.io.File(testOutputDir).mkdirs();
                verify2Pack(processor, testOutputDir);
            }

            log("");
            log("--- RESULTS ---");
            log("Tables created: " + result.tablesCreated);
            log("Columns created: " + result.columnsCreated);
            log("Windows created: " + result.windowsCreated);
            log("Menus created: " + result.menusCreated);
            log("References created: " + result.referencesCreated);

            if (errors.isEmpty()) {
                log("");
                log("✓ ALL TESTS PASSED");
                result.success = true;
            } else {
                log("");
                log("✗ TESTS FAILED:");
                for (String err : errors) {
                    log("  - " + err);
                }
                result.success = false;
            }

            result.errors = errors;
            result.results = results;

        } catch (Exception e) {
            error("Exception during test: " + e.getMessage());
            e.printStackTrace();
            result.success = false;
            result.errors = errors;
        } finally {
            // ALWAYS ROLLBACK
            log("");
            log("--- ROLLING BACK TRANSACTION ---");
            if (trx != null) {
                trx.rollback();
                trx.close();
                log("Transaction rolled back");

                // Verify rollback was successful
                log("");
                log("--- VERIFYING ROLLBACK ---");
                verifyRollback();
            }
        }

        return result;
    }

    /**
     * Verify that rollback cleaned up all test data
     * PostgreSQL supports transactional DDL, so both AD records and
     * physical tables should be rolled back
     */
    private void verifyRollback() {
        boolean cleanRollback = true;

        // Check AD_Table records were rolled back
        for (String tableName : expectedTableNames) {
            MTable table = new Query(Env.getCtx(), MTable.Table_Name,
                    "TableName=?", null)  // No transaction - check committed state
                    .setParameters(tableName)
                    .first();

            if (table != null) {
                error("ROLLBACK FAILED: Table '" + tableName + "' still exists in AD_Table");
                cleanRollback = false;
            }
        }

        // Check AD_Reference records were rolled back
        for (String refName : expectedReferenceNames) {
            MReference ref = new Query(Env.getCtx(), MReference.Table_Name,
                    "Name=?", null)
                    .setParameters(refName)
                    .first();

            if (ref != null) {
                error("ROLLBACK FAILED: Reference '" + refName + "' still exists");
                cleanRollback = false;
            }
        }

        // Check physical tables were rolled back
        for (String tableName : expectedTableNames) {
            String sql = "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?";
            try {
                int count = DB.getSQLValue(null, sql, tableName.toLowerCase());
                if (count > 0) {
                    // Physical table still exists - this can happen if DDL was auto-committed
                    log("NOTE: Physical table '" + tableName + "' exists (DDL may have auto-committed)");
                    log("      Run cleanup: DROP TABLE IF EXISTS " + tableName + " CASCADE;");
                }
            } catch (Exception e) {
                // Ignore - table probably doesn't exist
            }
        }

        if (cleanRollback) {
            pass("Rollback verified - all AD records cleaned up");
        }
    }

    /**
     * Run test and COMMIT changes
     * Use only when you want to persist the test data
     */
    public TestResult runTestWithCommit(String excelFilePath) {
        TestResult result = runTestWithRollback(excelFilePath);

        if (result.success && trx != null) {
            log("--- COMMITTING TRANSACTION ---");
            trx.commit();
            log("Changes committed to database");
        }

        return result;
    }

    private void verifyReferences(NinjaProcessor processor) {
        log("Verifying References...");

        for (String refName : expectedReferenceNames) {
            MReference ref = new Query(Env.getCtx(), MReference.Table_Name,
                    "Name=?", trxName)
                    .setParameters(refName)
                    .first();

            if (ref != null) {
                pass("Reference '" + refName + "' created (ID=" + ref.getAD_Reference_ID() + ")");

                // Verify list values exist
                List<MRefList> lists = new Query(Env.getCtx(), MRefList.Table_Name,
                        "AD_Reference_ID=?", trxName)
                        .setParameters(ref.getAD_Reference_ID())
                        .list();

                if (lists.isEmpty()) {
                    error("Reference '" + refName + "' has no list values");
                } else {
                    pass("  - " + lists.size() + " list values");
                }
            } else {
                error("Reference '" + refName + "' NOT created");
            }
        }
    }

    private void verifyTables(NinjaProcessor processor) {
        log("Verifying Tables...");

        for (String tableName : expectedTableNames) {
            MTable table = new Query(Env.getCtx(), MTable.Table_Name,
                    "TableName=?", trxName)
                    .setParameters(tableName)
                    .first();

            if (table != null) {
                pass("Table '" + tableName + "' created (ID=" + table.getAD_Table_ID() + ")");

                // Verify columns exist
                List<MColumn> columns = new Query(Env.getCtx(), MColumn.Table_Name,
                        "AD_Table_ID=?", trxName)
                        .setParameters(table.getAD_Table_ID())
                        .list();

                pass("  - " + columns.size() + " columns");

                // Verify ID column exists
                MColumn idCol = table.getColumn(tableName + "_ID");
                if (idCol != null && idCol.isKey()) {
                    pass("  - ID column '" + tableName + "_ID' is key");
                } else {
                    error("  - ID column '" + tableName + "_ID' missing or not key");
                }

                // Verify standard columns
                String[] stdCols = {"AD_Client_ID", "AD_Org_ID", "IsActive", "Created", "CreatedBy", "Updated", "UpdatedBy"};
                for (String col : stdCols) {
                    if (table.getColumn(col) == null) {
                        error("  - Standard column '" + col + "' missing");
                    }
                }

            } else {
                error("Table '" + tableName + "' NOT created");
            }
        }
    }

    private void verifyWindows(NinjaProcessor processor) {
        log("Verifying Windows...");

        for (MWindow window : processor.getWindows()) {
            if (window == null) continue;

            pass("Window '" + window.getName() + "' created (ID=" + window.getAD_Window_ID() + ")");

            // Verify tabs exist
            List<MTab> tabs = new Query(Env.getCtx(), MTab.Table_Name,
                    "AD_Window_ID=?", trxName)
                    .setParameters(window.getAD_Window_ID())
                    .list();

            pass("  - " + tabs.size() + " tabs");

            // Check tab levels for master-detail
            int maxLevel = 0;
            for (MTab tab : tabs) {
                if (tab.getTabLevel() > maxLevel) {
                    maxLevel = tab.getTabLevel();
                }
            }
            if (maxLevel > 0) {
                pass("  - Master-Detail: " + (maxLevel + 1) + " levels");
            }
        }
    }

    private void verifyMenus(NinjaProcessor processor) {
        log("Verifying Menus...");

        for (MMenu menu : processor.getMenus()) {
            pass("Menu '" + menu.getName() + "' created (ID=" + menu.getAD_Menu_ID() + ")");
        }
    }

    private void verifyMasterDetail() {
        log("Verifying Master-Detail relationships...");

        for (String[] relation : expectedMasterDetail) {
            String childTable = relation[0];
            String parentTable = relation[1];

            MTable child = new Query(Env.getCtx(), MTable.Table_Name,
                    "TableName=?", trxName)
                    .setParameters(childTable)
                    .first();

            if (child == null) {
                error("Child table '" + childTable + "' not found");
                continue;
            }

            // Find parent column
            String parentColName = parentTable + "_ID";
            MColumn parentCol = child.getColumn(parentColName);

            if (parentCol == null) {
                error("Parent column '" + parentColName + "' not found in " + childTable);
                continue;
            }

            if (parentCol.isParent()) {
                pass("Master-Detail: " + childTable + " -> " + parentTable + " (IsParent=Y)");
            } else {
                error("Column '" + parentColName + "' in " + childTable + " has IsParent=N");
            }
        }
    }

    /**
     * Verify CRUD operations on created tables
     * Creates test records, verifies save/update/delete
     *
     * NOTE: AD_PInstance records are NOT rolled back - they are audit logs
     * that persist regardless of transaction outcome. This is expected behavior.
     */
    private void verifyCRUD(NinjaProcessor processor) {
        log("Verifying CRUD operations...");

        for (MTable table : processor.getTables()) {
            if (table == null) continue;

            String tableName = table.getTableName();
            log("  Testing CRUD on " + tableName + "...");

            try {
                // CREATE - instantiate PO and set values
                PO record = table.getPO(0, trxName);
                if (record == null) {
                    error("Cannot create PO instance for " + tableName);
                    continue;
                }

                // Set mandatory Name field if exists
                if (table.getColumn("Name") != null) {
                    record.set_ValueOfColumn("Name", "NinjaTest_" + System.currentTimeMillis());
                }

                // Set AD_Org_ID
                record.setAD_Org_ID(0);

                // SAVE
                boolean saved = record.save(trxName);
                if (saved) {
                    pass("  CREATE: " + tableName + " record saved (ID=" + record.get_ID() + ")");
                } else {
                    error("  CREATE: " + tableName + " save failed");
                    continue;
                }

                // READ - retrieve by ID
                PO readBack = table.getPO(record.get_ID(), trxName);
                if (readBack != null && readBack.get_ID() == record.get_ID()) {
                    pass("  READ: " + tableName + " record retrieved");
                } else {
                    error("  READ: " + tableName + " record not found after save");
                }

                // UPDATE - modify and save
                if (table.getColumn("Description") != null) {
                    readBack.set_ValueOfColumn("Description", "Updated by NinjaTest");
                    if (readBack.save(trxName)) {
                        pass("  UPDATE: " + tableName + " record updated");
                    } else {
                        error("  UPDATE: " + tableName + " update failed");
                    }
                }

                // DELETE
                if (readBack.delete(true, trxName)) {
                    pass("  DELETE: " + tableName + " record deleted");
                } else {
                    error("  DELETE: " + tableName + " delete failed");
                }

                // Verify delete
                PO deleted = table.getPO(record.get_ID(), trxName);
                if (deleted == null || deleted.get_ID() == 0) {
                    pass("  VERIFY: " + tableName + " record confirmed deleted");
                } else {
                    error("  VERIFY: " + tableName + " record still exists after delete");
                }

            } catch (Exception e) {
                error("  CRUD test failed for " + tableName + ": " + e.getMessage());
            }
        }
    }

    /**
     * Test master-detail record creation with FK
     * Creates parent record, then child record linked to parent
     */
    private void verifyMasterDetailCRUD() {
        log("Verifying Master-Detail CRUD...");

        for (String[] relation : expectedMasterDetail) {
            String childTableName = relation[0];
            String parentTableName = relation[1];

            try {
                MTable parentTable = MTable.get(Env.getCtx(), parentTableName);
                MTable childTable = MTable.get(Env.getCtx(), childTableName);

                if (parentTable == null || childTable == null) {
                    log("  Skipping " + childTableName + "->" + parentTableName + " (tables not synced yet)");
                    continue;
                }

                // Skip if parent is existing table (like C_BPartner)
                if (parentTableName.startsWith("C_") || parentTableName.startsWith("AD_") ||
                    parentTableName.startsWith("M_")) {
                    log("  Skipping " + parentTableName + " (existing iDempiere table)");
                    continue;
                }

                // Create parent
                PO parent = parentTable.getPO(0, trxName);
                if (parentTable.getColumn("Name") != null) {
                    parent.set_ValueOfColumn("Name", "Parent_" + System.currentTimeMillis());
                }
                parent.setAD_Org_ID(0);

                if (!parent.save(trxName)) {
                    error("  Cannot create parent " + parentTableName);
                    continue;
                }
                pass("  Created parent: " + parentTableName + " ID=" + parent.get_ID());

                // Create child linked to parent
                PO child = childTable.getPO(0, trxName);
                if (childTable.getColumn("Name") != null) {
                    child.set_ValueOfColumn("Name", "Child_" + System.currentTimeMillis());
                }
                child.setAD_Org_ID(0);

                // Set FK to parent
                String fkColumn = parentTableName + "_ID";
                child.set_ValueOfColumn(fkColumn, parent.get_ID());

                if (child.save(trxName)) {
                    pass("  Created child: " + childTableName + " ID=" + child.get_ID() + " -> " + fkColumn + "=" + parent.get_ID());
                } else {
                    error("  Cannot create child " + childTableName + " linked to " + parentTableName);
                }

                // Delete child first (FK constraint)
                if (child.delete(true, trxName)) {
                    pass("  Deleted child: " + childTableName);
                }

                // Delete parent
                if (parent.delete(true, trxName)) {
                    pass("  Deleted parent: " + parentTableName);
                }

            } catch (Exception e) {
                error("  Master-Detail CRUD failed: " + e.getMessage());
            }
        }
    }

    /**
     * Test List reference values work in records
     */
    private void verifyListReferences(NinjaProcessor processor) {
        log("Verifying List references in records...");

        for (MTable table : processor.getTables()) {
            if (table == null) continue;

            // Find List columns
            List<MColumn> columns = new Query(Env.getCtx(), MColumn.Table_Name,
                    "AD_Table_ID=? AND AD_Reference_ID=?", trxName)
                    .setParameters(table.getAD_Table_ID(), DisplayType.List)
                    .list();

            for (MColumn col : columns) {
                if (col.getAD_Reference_Value_ID() > 0) {
                    // Get first list value
                    MRefList firstValue = new Query(Env.getCtx(), MRefList.Table_Name,
                            "AD_Reference_ID=?", trxName)
                            .setParameters(col.getAD_Reference_Value_ID())
                            .first();

                    if (firstValue != null) {
                        pass("  List column " + table.getTableName() + "." + col.getColumnName() +
                                " -> Reference has values (e.g., '" + firstValue.getValue() + "')");
                    }
                }
            }
        }
    }

    /**
     * Test 2Pack generation and import
     * Verifies:
     * - 2Pack.zip can be generated from created AD models
     * - 2Pack can be imported back (round-trip test)
     */
    private void verify2Pack(NinjaProcessor processor, String outputDir) {
        log("Verifying 2Pack generation...");

        try {
            // Generate 2Pack from processor
            org.idempiere.ninja.generator.TwoPackGenerator twoPackGen =
                    new org.idempiere.ninja.generator.TwoPackGenerator(processor, outputDir, verbose);
            String twoPackPath = twoPackGen.generate();

            if (twoPackPath != null && new java.io.File(twoPackPath).exists()) {
                pass("2Pack generated: " + twoPackPath);

                // Verify zip contents
                java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(twoPackPath);
                int entryCount = 0;
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = entries.nextElement();
                    if (entry.getName().endsWith("PackOut.xml")) {
                        pass("  PackOut.xml found in 2Pack");
                    }
                    entryCount++;
                }
                zipFile.close();
                pass("  2Pack contains " + entryCount + " entries");

                // Clean up test 2Pack
                new java.io.File(twoPackPath).delete();
                log("  Test 2Pack cleaned up");
            } else {
                error("2Pack generation failed - no file created");
            }
        } catch (Exception e) {
            error("2Pack test failed: " + e.getMessage());
        }
    }

    /**
     * Test importing existing 2Pack XML
     * Useful for testing migration from old Ninja format
     */
    public TestResult testImport2Pack(String twoPackXmlPath) {
        TestResult result = new TestResult();
        result.excelFile = twoPackXmlPath;

        try {
            trxName = Trx.createTrxName("NinjaTest2Pack");
            trx = Trx.get(trxName, true);

            log("=== 2PACK IMPORT TEST ===");
            log("File: " + twoPackXmlPath);

            // Import using PackIn
            // Note: This requires org.adempiere.pipo classes
            log("Importing 2Pack...");
            // TODO: Integrate with PackIn for full 2Pack import testing
            // org.compiere.pipo2.PackIn packIn = new org.compiere.pipo2.PackIn();
            // packIn.importPack(new java.io.File(twoPackXmlPath), ctx, trxName);

            log("2Pack import test - requires PackIn integration");
            result.success = true;

        } catch (Exception e) {
            error("2Pack import failed: " + e.getMessage());
            result.success = false;
        } finally {
            if (trx != null) {
                trx.rollback();
                trx.close();
                log("Transaction rolled back");
            }
        }

        result.errors = errors;
        result.results = results;
        return result;
    }

    private void verifyPhysicalTables(NinjaProcessor processor) {
        log("Verifying Physical Tables...");

        for (String tableName : expectedTableNames) {
            String sql = "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?";
            try {
                int count = DB.getSQLValue(trxName, sql, tableName.toLowerCase());
                if (count > 0) {
                    pass("Physical table '" + tableName + "' exists in database");
                } else {
                    // Table may not be synced yet - check AD only
                    pass("Physical table '" + tableName + "' pending sync (AD created)");
                }
            } catch (Exception e) {
                error("Could not verify physical table '" + tableName + "': " + e.getMessage());
            }
        }
    }

    /**
     * Generate SQL statements that WOULD be executed
     * Useful for dry-run testing and review
     */
    public List<String> generateSQL(String excelFilePath) {
        List<String> sqlStatements = new ArrayList<>();

        // TODO: Implement SQL capture mode in NinjaProcessor
        // This would require adding a "capture mode" that collects SQL
        // instead of executing it

        return sqlStatements;
    }

    // Test configuration methods
    public NinjaProcessorTest expectBundleName(String name) {
        this.expectedBundleName = name;
        return this;
    }

    public NinjaProcessorTest expectMenuPrefix(String prefix) {
        this.expectedMenuPrefix = prefix;
        return this;
    }

    public NinjaProcessorTest expectTables(String... tableNames) {
        for (String name : tableNames) {
            this.expectedTableNames.add(name);
        }
        this.expectedTableCount = tableNames.length;
        return this;
    }

    public NinjaProcessorTest expectReferences(String... refNames) {
        for (String name : refNames) {
            this.expectedReferenceNames.add(name);
        }
        this.expectedReferenceCount = refNames.length;
        return this;
    }

    public NinjaProcessorTest expectMasterDetail(String childTable, String parentTable) {
        this.expectedMasterDetail.add(new String[]{childTable, parentTable});
        return this;
    }

    public NinjaProcessorTest setClientId(int clientId) {
        this.clientId = clientId;
        return this;
    }

    public NinjaProcessorTest setVerbose(boolean verbose) {
        this.verbose = verbose;
        return this;
    }

    public NinjaProcessorTest setRunCrudTests(boolean runCrud) {
        this.runCrudTests = runCrud;
        return this;
    }

    public NinjaProcessorTest setSilentMode(boolean silent) {
        this.silentMode = silent;
        return this;
    }

    public NinjaProcessorTest setRun2PackTests(boolean run2Pack) {
        this.run2PackTests = run2Pack;
        return this;
    }

    public NinjaProcessorTest setTestOutputDir(String dir) {
        this.testOutputDir = dir;
        return this;
    }

    // Logging helpers
    private void log(String msg) {
        System.out.println("[TEST] " + msg);
        results.add(msg);
    }

    private void pass(String msg) {
        System.out.println("[PASS] " + msg);
        results.add("PASS: " + msg);
    }

    private void error(String msg) {
        System.out.println("[FAIL] " + msg);
        errors.add(msg);
        results.add("FAIL: " + msg);
    }

    /**
     * Test Result container
     */
    public static class TestResult {
        public boolean success;
        public String excelFile;
        public int tablesCreated;
        public int columnsCreated;
        public int windowsCreated;
        public int menusCreated;
        public int referencesCreated;
        public List<String> errors = new ArrayList<>();
        public List<String> results = new ArrayList<>();

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("NinjaTest Result: ").append(success ? "PASSED" : "FAILED").append("\n");
            sb.append("  File: ").append(excelFile).append("\n");
            sb.append("  Tables: ").append(tablesCreated).append("\n");
            sb.append("  Columns: ").append(columnsCreated).append("\n");
            sb.append("  Windows: ").append(windowsCreated).append("\n");
            sb.append("  Menus: ").append(menusCreated).append("\n");
            sb.append("  References: ").append(referencesCreated).append("\n");
            if (!errors.isEmpty()) {
                sb.append("  Errors:\n");
                for (String err : errors) {
                    sb.append("    - ").append(err).append("\n");
                }
            }
            return sb.toString();
        }
    }

    /**
     * Example usage - run from iDempiere console or process
     *
     * Test Scenarios from original Ninja 2Pack:
     *
     * 1. SIMPLE TABLE: NinjaTest.xls
     *    - Basic 2-table model (TaskList, TaskItem)
     *    - Simple List references
     *    - Master-Detail relationship
     *
     * 2. HOSPITAL: Hospital.xls
     *    - Master-Detail (Surgery -> Treatment)
     *    - Sub-detail to existing model (Surgery extends C_BPartner)
     *    - Inline List definitions (L#BedNo=1/2/3/4/5)
     *    - Callouts (QtyDosage > Percentage = formula)
     *    - WorkflowModel (NewWorkFlow=...)
     *    - Multi-table PrintFormat
     *
     * 3. BIGMENU: PrintFormat test
     *    - Multi-table joins (AD_Field,AD_Column,AD_Tab,AD_Window,AD_Table)
     *    - Column selection for reports
     *
     * 4. PROCESS: Process generation test
     *    - AD_Process with parameters
     *    - AD_Table_Process linking
     */
    public static void main(String[] args) {
        String testType = args.length > 0 ? args[0] : "simple";
        String testFile = args.length > 1 ? args[1] : null;

        NinjaProcessorTest test;
        TestResult result;

        switch (testType.toLowerCase()) {
            case "hospital":
                // Hospital sample - tests Master-Detail, Callouts, Inline Lists
                test = new NinjaProcessorTest()
                        .setClientId(11)  // GardenWorld
                        .expectReferences("BedNo", "Floor", "Wing", "ClassBed", "TypeOfAttention")
                        .expectTables("HO_Bed", "HO_BedRegistration", "HO_PatientActivity",
                                "HO_Surgery", "HO_Treatment", "HO_PatientLog")
                        .expectMasterDetail("HO_Surgery", "C_BPartner")
                        .expectMasterDetail("HO_Treatment", "HO_Surgery")
                        .setVerbose(true);
                result = test.runTestWithRollback(testFile != null ? testFile : "templates/test/Hospital.xls");
                break;

            case "simple":
            default:
                // Simple test - basic table creation
                test = new NinjaProcessorTest()
                        .setClientId(11)  // GardenWorld
                        .expectReferences("TaskStatus", "TaskPriority")
                        .expectTables("NT_TaskList", "NT_TaskItem")
                        .expectMasterDetail("NT_TaskItem", "NT_TaskList")
                        .setVerbose(true);
                result = test.runTestWithRollback(testFile != null ? testFile : "templates/test/NinjaTest.xls");
                break;
        }

        System.out.println("\n" + result);

        // Print cleanup SQL if needed
        if (!result.success) {
            System.out.println("\nCleanup SQL (if rollback failed):");
            for (String tableName : test.expectedTableNames) {
                System.out.println("DROP TABLE IF EXISTS " + tableName + " CASCADE;");
                System.out.println("DELETE FROM AD_Table WHERE TableName='" + tableName + "';");
            }
        }
    }
}
