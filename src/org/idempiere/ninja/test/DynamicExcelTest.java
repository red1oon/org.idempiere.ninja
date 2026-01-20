/******************************************************************************
 * Dynamic Excel Test - Tests XLS structure without iDempiere
 * Can run standalone to validate Excel before attempting DB injection
 *****************************************************************************/
package org.idempiere.ninja.test;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Dynamic Excel Test - validates XLS structure and generates expected SQL
 *
 * No iDempiere/OSGi required - runs with just POI library
 *
 * Usage:
 *   java -cp "lib/*:bin" org.idempiere.ninja.test.DynamicExcelTest path/to/model.xls
 *   java -cp "lib/*:bin" org.idempiere.ninja.test.DynamicExcelTest path/to/model.xlsx
 */
public class DynamicExcelTest {

    private List<String> errors = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private List<String> info = new ArrayList<>();

    // Parsed model info
    private String bundleName;
    private String entityType;
    private String menuPrefix;
    private Map<String, TableDef> tables = new HashMap<>();
    private Map<String, List<String>> references = new HashMap<>();

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: DynamicExcelTest <excel-file> [options]");
            System.out.println();
            System.out.println("Options:");
            System.out.println("  -v         Verbose output");
            System.out.println("  -sql       Generate expected SQL");
            System.out.println("  -validate  Validate structure only (default)");
            System.out.println();
            System.out.println("Examples:");
            System.out.println("  DynamicExcelTest MyModel.xls");
            System.out.println("  DynamicExcelTest AssetMaintenance.xls -sql");
            System.out.println("  DynamicExcelTest Ninja_HRMIS.xlsx -v");
            return;
        }

        String excelPath = args[0];
        boolean verbose = false;
        boolean generateSql = false;

        for (int i = 1; i < args.length; i++) {
            if ("-v".equals(args[i])) verbose = true;
            if ("-sql".equals(args[i])) generateSql = true;
        }

        DynamicExcelTest test = new DynamicExcelTest();
        boolean success = test.runTest(excelPath, verbose, generateSql);

        System.exit(success ? 0 : 1);
    }

    public boolean runTest(String excelPath, boolean verbose, boolean generateSql) {
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║     Dynamic Excel Test                                    ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();

        File file = new File(excelPath);
        if (!file.exists()) {
            System.err.println("[FAIL] File not found: " + excelPath);
            return false;
        }

        System.out.println("[TEST] File: " + excelPath);
        System.out.println("[TEST] Size: " + file.length() + " bytes");
        System.out.println();

        try {
            // Parse based on extension
            if (excelPath.toLowerCase().endsWith(".xlsx")) {
                parseXlsx(file, verbose);
            } else {
                parseXls(file, verbose);
            }

            // Print summary
            printSummary(verbose);

            // Generate SQL if requested
            if (generateSql) {
                generateExpectedSql();
            }

            // Return result
            if (errors.isEmpty()) {
                System.out.println();
                System.out.println("=== TEST PASSED ===");
                return true;
            } else {
                System.out.println();
                System.out.println("=== TEST FAILED ===");
                System.out.println("Errors: " + errors.size());
                for (String err : errors) {
                    System.out.println("  - " + err);
                }
                return false;
            }

        } catch (Exception e) {
            System.err.println("[FAIL] " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void parseXls(File file, boolean verbose) throws Exception {
        System.out.println("[PARSE] Reading XLS file...");

        try (FileInputStream fis = new FileInputStream(file);
             HSSFWorkbook workbook = new HSSFWorkbook(fis)) {

            int sheetCount = workbook.getNumberOfSheets();
            System.out.println("[PASS] Opened workbook: " + sheetCount + " sheets");

            for (int i = 0; i < sheetCount; i++) {
                HSSFSheet sheet = workbook.getSheetAt(i);
                String sheetName = sheet.getSheetName();

                if (verbose) {
                    System.out.println("[INFO] Sheet: " + sheetName + " (" + sheet.getLastRowNum() + " rows)");
                }

                parseSheet(sheetName, sheet, verbose);
            }
        }
    }

    private void parseXlsx(File file, boolean verbose) throws Exception {
        System.out.println("[PARSE] Reading XLSX file...");

        try (FileInputStream fis = new FileInputStream(file);
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {

            int sheetCount = workbook.getNumberOfSheets();
            System.out.println("[PASS] Opened workbook: " + sheetCount + " sheets");

            for (int i = 0; i < sheetCount; i++) {
                XSSFSheet sheet = workbook.getSheetAt(i);
                String sheetName = sheet.getSheetName();

                if (verbose) {
                    System.out.println("[INFO] Sheet: " + sheetName + " (" + sheet.getLastRowNum() + " rows)");
                }

                parseXlsxSheet(sheetName, sheet, verbose);
            }
        }
    }

    private void parseSheet(String name, HSSFSheet sheet, boolean verbose) {
        if ("Config".equalsIgnoreCase(name)) {
            parseConfigSheet(sheet, verbose);
        } else if ("Model".equalsIgnoreCase(name)) {
            parseModelSheet(sheet, verbose);
        } else if ("List".equalsIgnoreCase(name) || "DataTypeMaker".equalsIgnoreCase(name)) {
            parseListSheet(sheet, verbose);
        } else if ("2_RO_ModelMaker".equals(name)) {
            parseLegacyModelMaker(sheet, verbose);
        }
    }

    private void parseXlsxSheet(String name, XSSFSheet sheet, boolean verbose) {
        if ("Config".equalsIgnoreCase(name)) {
            parseXlsxConfigSheet(sheet, verbose);
        } else if ("Model".equalsIgnoreCase(name)) {
            parseXlsxModelSheet(sheet, verbose);
        } else if ("List".equalsIgnoreCase(name) || "DataTypeMaker".equalsIgnoreCase(name)) {
            parseXlsxListSheet(sheet, verbose);
        } else if ("2_RO_ModelMaker".equals(name)) {
            parseXlsxLegacyModelMaker(sheet, verbose);
        }
    }

    private void parseConfigSheet(HSSFSheet sheet, boolean verbose) {
        info.add("Found Config sheet");
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            HSSFRow row = sheet.getRow(r);
            if (row == null) continue;

            String key = getCellString(row.getCell(0));
            String value = getCellString(row.getCell(1));

            if ("Bundle-Name".equalsIgnoreCase(key)) bundleName = value;
            if ("Entity-Type".equalsIgnoreCase(key)) entityType = value;
            if ("Package-Prefix".equalsIgnoreCase(key)) menuPrefix = extractPrefix(value);
        }

        if (bundleName == null) warnings.add("Config: Bundle-Name not found");
        if (entityType == null) entityType = "U"; // Default
    }

    private void parseXlsxConfigSheet(XSSFSheet sheet, boolean verbose) {
        info.add("Found Config sheet");
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            XSSFRow row = sheet.getRow(r);
            if (row == null) continue;

            String key = getXCellString(row.getCell(0));
            String value = getXCellString(row.getCell(1));

            if ("Bundle-Name".equalsIgnoreCase(key)) bundleName = value;
            if ("Entity-Type".equalsIgnoreCase(key)) entityType = value;
            if ("Package-Prefix".equalsIgnoreCase(key)) menuPrefix = extractPrefix(value);
        }

        if (bundleName == null) warnings.add("Config: Bundle-Name not found");
        if (entityType == null) entityType = "U";
    }

    private void parseModelSheet(HSSFSheet sheet, boolean verbose) {
        info.add("Found Model sheet");
        // Parse table definitions from columns
        HSSFRow firstRow = sheet.getRow(0);
        if (firstRow == null) {
            errors.add("Model sheet is empty");
            return;
        }

        for (int c = 1; c < firstRow.getLastCellNum(); c++) {
            String tableName = getCellString(firstRow.getCell(c));
            if (tableName != null && !tableName.isEmpty()) {
                TableDef table = new TableDef(tableName);
                tables.put(tableName, table);

                // Read columns from rows below
                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    HSSFRow row = sheet.getRow(r);
                    if (row == null) continue;
                    String colDef = getCellString(row.getCell(c));
                    if (colDef != null && !colDef.isEmpty()) {
                        table.addColumn(colDef);
                    }
                }

                if (verbose) {
                    System.out.println("[TABLE] " + tableName + ": " + table.columns.size() + " columns");
                }
            }
        }
    }

    private void parseXlsxModelSheet(XSSFSheet sheet, boolean verbose) {
        info.add("Found Model sheet");
        XSSFRow firstRow = sheet.getRow(0);
        if (firstRow == null) {
            errors.add("Model sheet is empty");
            return;
        }

        for (int c = 1; c < firstRow.getLastCellNum(); c++) {
            String tableName = getXCellString(firstRow.getCell(c));
            if (tableName != null && !tableName.isEmpty()) {
                TableDef table = new TableDef(tableName);
                tables.put(tableName, table);

                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    XSSFRow row = sheet.getRow(r);
                    if (row == null) continue;
                    String colDef = getXCellString(row.getCell(c));
                    if (colDef != null && !colDef.isEmpty()) {
                        table.addColumn(colDef);
                    }
                }

                if (verbose) {
                    System.out.println("[TABLE] " + tableName + ": " + table.columns.size() + " columns");
                }
            }
        }
    }

    private void parseListSheet(HSSFSheet sheet, boolean verbose) {
        info.add("Found List sheet");
        HSSFRow headerRow = sheet.getRow(0);
        if (headerRow == null) return;

        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            String refName = getCellString(headerRow.getCell(c));
            if (refName != null && !refName.isEmpty()) {
                List<String> values = new ArrayList<>();
                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    HSSFRow row = sheet.getRow(r);
                    if (row == null) continue;
                    String val = getCellString(row.getCell(c));
                    if (val != null && !val.isEmpty()) {
                        values.add(val);
                    }
                }
                references.put(refName, values);

                if (verbose) {
                    System.out.println("[LIST] " + refName + ": " + values.size() + " values");
                }
            }
        }
    }

    private void parseXlsxListSheet(XSSFSheet sheet, boolean verbose) {
        info.add("Found List sheet");
        XSSFRow headerRow = sheet.getRow(0);
        if (headerRow == null) return;

        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            String refName = getXCellString(headerRow.getCell(c));
            if (refName != null && !refName.isEmpty()) {
                List<String> values = new ArrayList<>();
                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    XSSFRow row = sheet.getRow(r);
                    if (row == null) continue;
                    String val = getXCellString(row.getCell(c));
                    if (val != null && !val.isEmpty()) {
                        values.add(val);
                    }
                }
                references.put(refName, values);

                if (verbose) {
                    System.out.println("[LIST] " + refName + ": " + values.size() + " values");
                }
            }
        }
    }

    private void parseLegacyModelMaker(HSSFSheet sheet, boolean verbose) {
        info.add("Found legacy 2_RO_ModelMaker sheet");
        // Parse old Ninja format
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            HSSFRow row = sheet.getRow(r);
            if (row == null) continue;

            String name = getCellString(row.getCell(4)); // Name column
            String columnSet = getCellString(row.getCell(6)); // ColumnSet column

            if (name != null && !name.isEmpty()) {
                TableDef table = new TableDef(name);
                if (columnSet != null) {
                    for (String col : columnSet.split(",")) {
                        table.addColumn(col.trim());
                    }
                }
                tables.put(name, table);

                if (verbose) {
                    System.out.println("[TABLE] " + name + ": " + table.columns.size() + " columns");
                }
            }
        }
    }

    private void parseXlsxLegacyModelMaker(XSSFSheet sheet, boolean verbose) {
        info.add("Found legacy 2_RO_ModelMaker sheet");
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            XSSFRow row = sheet.getRow(r);
            if (row == null) continue;

            String name = getXCellString(row.getCell(4));
            String columnSet = getXCellString(row.getCell(6));

            if (name != null && !name.isEmpty()) {
                TableDef table = new TableDef(name);
                if (columnSet != null) {
                    for (String col : columnSet.split(",")) {
                        table.addColumn(col.trim());
                    }
                }
                tables.put(name, table);

                if (verbose) {
                    System.out.println("[TABLE] " + name + ": " + table.columns.size() + " columns");
                }
            }
        }
    }

    private void printSummary(boolean verbose) {
        System.out.println();
        System.out.println("=== SUMMARY ===");
        System.out.println("Bundle: " + (bundleName != null ? bundleName : "(not set)"));
        System.out.println("Entity Type: " + entityType);
        System.out.println("Menu Prefix: " + (menuPrefix != null ? menuPrefix : "(auto)"));
        System.out.println("Tables: " + tables.size());
        System.out.println("References: " + references.size());

        if (verbose) {
            System.out.println();
            System.out.println("Tables:");
            for (Map.Entry<String, TableDef> entry : tables.entrySet()) {
                TableDef t = entry.getValue();
                System.out.println("  " + entry.getKey() + " (" + t.columns.size() + " cols)");
            }

            System.out.println();
            System.out.println("References:");
            for (Map.Entry<String, List<String>> entry : references.entrySet()) {
                System.out.println("  " + entry.getKey() + ": " + entry.getValue().size() + " values");
            }
        }

        if (!warnings.isEmpty()) {
            System.out.println();
            System.out.println("Warnings:");
            for (String w : warnings) {
                System.out.println("  - " + w);
            }
        }
    }

    private void generateExpectedSql() {
        System.out.println();
        System.out.println("=== EXPECTED SQL ===");
        System.out.println("-- Generated from Excel structure");
        System.out.println();

        // References
        for (Map.Entry<String, List<String>> entry : references.entrySet()) {
            System.out.println("-- Reference: " + entry.getKey());
            System.out.println("INSERT INTO AD_Reference (Name, ValidationType, EntityType) VALUES ('" +
                entry.getKey() + "', 'L', '" + entityType + "');");
            for (String val : entry.getValue()) {
                String code = val.length() > 2 ? val.substring(0, 2) : val;
                System.out.println("INSERT INTO AD_Ref_List (AD_Reference_ID, Name, Value) VALUES (ref_id, '" +
                    val + "', '" + code + "');");
            }
            System.out.println();
        }

        // Tables
        for (Map.Entry<String, TableDef> entry : tables.entrySet()) {
            String tableName = menuPrefix != null ? menuPrefix + "_" + entry.getKey() : entry.getKey();
            TableDef t = entry.getValue();

            System.out.println("-- Table: " + tableName);
            System.out.println("INSERT INTO AD_Table (TableName, Name, AccessLevel, EntityType) VALUES ('" +
                tableName + "', '" + entry.getKey() + "', '3', '" + entityType + "');");
            System.out.println();

            System.out.println("-- Columns for " + tableName);
            System.out.println("-- (ID column)");
            System.out.println("INSERT INTO AD_Column (ColumnName, Name, AD_Reference_ID, IsKey) VALUES ('" +
                tableName + "_ID', '" + entry.getKey() + "', 13, 'Y');");

            for (String col : t.columns) {
                String colName = col;
                String refType = "10"; // String default

                if (col.startsWith("L#")) {
                    colName = col.substring(2);
                    refType = "17"; // List
                } else if (col.startsWith("D#")) {
                    colName = col.substring(2);
                    refType = "15"; // Date
                } else if (col.startsWith("Y#")) {
                    colName = col.substring(2);
                    refType = "20"; // YesNo
                } else if (col.startsWith("Q#")) {
                    colName = col.substring(2);
                    refType = "29"; // Quantity
                } else if (col.startsWith("T#")) {
                    colName = col.substring(2);
                    refType = "14"; // Text
                } else if (col.endsWith("_ID")) {
                    refType = "19"; // TableDir
                }

                System.out.println("INSERT INTO AD_Column (ColumnName, Name, AD_Reference_ID) VALUES ('" +
                    colName + "', '" + colName + "', " + refType + ");");
            }
            System.out.println();
        }
    }

    // Helper methods
    private String getCellString(HSSFCell cell) {
        if (cell == null) return null;
        try {
            switch (cell.getCellType()) {
                case STRING: return cell.getStringCellValue().trim();
                case NUMERIC: return String.valueOf((int) cell.getNumericCellValue());
                default: return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String getXCellString(XSSFCell cell) {
        if (cell == null) return null;
        try {
            switch (cell.getCellType()) {
                case STRING: return cell.getStringCellValue().trim();
                case NUMERIC: return String.valueOf((int) cell.getNumericCellValue());
                default: return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String extractPrefix(String packagePrefix) {
        if (packagePrefix == null) return null;
        String[] parts = packagePrefix.split("\\.");
        if (parts.length > 0) {
            String last = parts[parts.length - 1];
            return last.toUpperCase().substring(0, Math.min(2, last.length()));
        }
        return null;
    }

    // Inner class for table definition
    static class TableDef {
        String name;
        List<String> columns = new ArrayList<>();

        TableDef(String name) {
            this.name = name;
        }

        void addColumn(String col) {
            columns.add(col);
        }
    }
}
