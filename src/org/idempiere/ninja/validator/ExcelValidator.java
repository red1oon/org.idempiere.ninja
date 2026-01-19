/******************************************************************************
 * Product: iDempiere Ninja Plugin Generator                                  *
 * Copyright (C) Contributors                                                 *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 *****************************************************************************/
package org.idempiere.ninja.validator;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;

/**
 * Excel Validator - Validates Ninja Excel format with helpful error messages
 *
 * Provides clear guidance when Excel is incorrectly formatted,
 * showing examples of correct format for each issue found.
 *
 * @author iDempiere Community
 */
public class ExcelValidator {

    private String excelFilePath;
    private boolean verbose;
    private List<String> errors = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    // Extracted configuration
    private String bundleName;
    private String bundleVersion = "1.0.0";
    private String bundleVendor = "iDempiere Community";
    private String packagePrefix;
    private String entityType = "U";
    private String menuPrefix;

    // Valid column type prefixes
    private static final Set<String> VALID_TYPE_PREFIXES = new HashSet<>();
    static {
        VALID_TYPE_PREFIXES.add("S");   // String
        VALID_TYPE_PREFIXES.add("Q");   // Quantity
        VALID_TYPE_PREFIXES.add("A");   // Amount
        VALID_TYPE_PREFIXES.add("Y");   // Yes/No
        VALID_TYPE_PREFIXES.add("D");   // Date
        VALID_TYPE_PREFIXES.add("d");   // DateTime
        VALID_TYPE_PREFIXES.add("T");   // Text
        VALID_TYPE_PREFIXES.add("L");   // List
    }

    public ExcelValidator(String excelFilePath, boolean verbose) {
        this.excelFilePath = excelFilePath;
        this.verbose = verbose;
    }

    /**
     * Validate the Excel file structure
     * @return true if valid, false if errors found
     */
    public boolean validate() {
        log("Validating: " + excelFilePath);

        try (FileInputStream fis = new FileInputStream(new File(excelFilePath));
             HSSFWorkbook workbook = new HSSFWorkbook(fis)) {

            // Check required sheets
            validateRequiredSheets(workbook);

            // Validate Config sheet
            validateConfigSheet(workbook);

            // Validate List sheet (if exists)
            validateListSheet(workbook);

            // Validate Model sheet
            validateModelSheet(workbook);

            // Validate data sheets
            validateDataSheets(workbook);

        } catch (Exception e) {
            addError("Failed to read Excel file: " + e.getMessage());
        }

        // Print results
        printValidationResults();

        return errors.isEmpty();
    }

    private void validateRequiredSheets(HSSFWorkbook workbook) {
        boolean hasModel = false;

        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            String sheetName = workbook.getSheetAt(i).getSheetName();
            if ("Model".equals(sheetName)) {
                hasModel = true;
            }
        }

        if (!hasModel) {
            addError("Missing required sheet: 'Model'");
            showExample("REQUIRED_SHEETS", null);
        }

        logVerbose("Found " + workbook.getNumberOfSheets() + " sheets");
    }

    private void validateConfigSheet(HSSFWorkbook workbook) {
        HSSFSheet configSheet = workbook.getSheet("Config");

        if (configSheet == null) {
            // Config is optional, use defaults
            logVerbose("No Config sheet found, using defaults");
            return;
        }

        log("Validating Config sheet...");

        for (int i = 0; i <= configSheet.getLastRowNum(); i++) {
            HSSFRow row = configSheet.getRow(i);
            if (row == null) continue;

            HSSFCell keyCell = row.getCell(0);
            HSSFCell valueCell = row.getCell(1);

            if (keyCell == null) continue;

            String key = getCellStringValue(keyCell);
            String value = valueCell != null ? getCellStringValue(valueCell) : "";

            switch (key) {
                case "Bundle-Name":
                    bundleName = value;
                    break;
                case "Bundle-Version":
                    if (!value.isEmpty()) bundleVersion = value;
                    break;
                case "Bundle-Vendor":
                    if (!value.isEmpty()) bundleVendor = value;
                    break;
                case "Package-Prefix":
                    packagePrefix = value;
                    break;
                case "Entity-Type":
                    if (!value.isEmpty()) entityType = value;
                    break;
            }
        }

        logVerbose("Config: Bundle=" + bundleName + ", Version=" + bundleVersion);
    }

    private void validateListSheet(HSSFWorkbook workbook) {
        HSSFSheet listSheet = workbook.getSheet("List");

        if (listSheet == null) {
            logVerbose("No List sheet found (optional)");
            return;
        }

        log("Validating List sheet...");

        HSSFRow headerRow = listSheet.getRow(0);
        if (headerRow == null) {
            addError("List sheet: Row 1 (header) is empty");
            showExample("LIST_SHEET", null);
            return;
        }

        // Check each column has a header
        int colCount = headerRow.getLastCellNum();
        for (int col = 0; col < colCount; col++) {
            HSSFCell cell = headerRow.getCell(col);
            if (cell == null || getCellStringValue(cell).trim().isEmpty()) {
                addError("List sheet: Column " + (col + 1) + " header is empty");
                showExample("LIST_SHEET", null);
            }
        }

        // Check for values under each header
        for (int col = 0; col < colCount; col++) {
            boolean hasValues = false;
            for (int row = 1; row <= listSheet.getLastRowNum(); row++) {
                HSSFRow dataRow = listSheet.getRow(row);
                if (dataRow != null) {
                    HSSFCell cell = dataRow.getCell(col);
                    if (cell != null && !getCellStringValue(cell).trim().isEmpty()) {
                        hasValues = true;
                        break;
                    }
                }
            }
            if (!hasValues) {
                String header = getCellStringValue(headerRow.getCell(col));
                addWarning("List sheet: Reference '" + header + "' has no values");
            }
        }
    }

    private void validateModelSheet(HSSFWorkbook workbook) {
        HSSFSheet modelSheet = workbook.getSheet("Model");

        if (modelSheet == null) {
            return; // Already reported in validateRequiredSheets
        }

        log("Validating Model sheet...");

        // Check A1 - Main Menu name
        HSSFRow firstRow = modelSheet.getRow(0);
        if (firstRow == null) {
            addError("Model sheet: Row 1 is empty. Cell A1 must contain the main menu name.");
            showExample("MODEL_SHEET_A1", null);
            return;
        }

        HSSFCell a1 = firstRow.getCell(0);
        if (a1 == null || getCellStringValue(a1).trim().isEmpty()) {
            addError("Model sheet: Cell A1 is empty. Must contain main menu name (e.g., 'HumanResources')");
            showExample("MODEL_SHEET_A1", null);
            return;
        }

        String mainMenu = getCellStringValue(a1);
        menuPrefix = mainMenu.substring(0, Math.min(2, mainMenu.length())).toUpperCase() + "_";
        logVerbose("Main menu: " + mainMenu + ", Prefix: " + menuPrefix);

        // Check Row 2 - Table/Window names
        HSSFRow row2 = modelSheet.getRow(1);
        if (row2 == null || row2.getLastCellNum() <= 0) {
            addError("Model sheet: Row 2 is empty. Must contain table/window names.");
            showExample("MODEL_SHEET_ROW2", null);
            return;
        }

        // Validate each column
        int tableCount = 0;
        for (int col = 0; col < row2.getLastCellNum(); col++) {
            HSSFCell cell = row2.getCell(col);
            if (cell != null && !getCellStringValue(cell).trim().isEmpty()) {
                tableCount++;
                validateTableColumn(modelSheet, col);
            }
        }

        if (tableCount == 0) {
            addError("Model sheet: No tables defined in Row 2");
            showExample("MODEL_SHEET_ROW2", null);
        }

        logVerbose("Found " + tableCount + " table definitions");
    }

    private void validateTableColumn(HSSFSheet modelSheet, int col) {
        HSSFRow row2 = modelSheet.getRow(1);
        String tableName = getCellStringValue(row2.getCell(col));

        // Check for columns (rows 3+)
        int columnCount = 0;
        for (int row = 2; row <= modelSheet.getLastRowNum(); row++) {
            HSSFRow dataRow = modelSheet.getRow(row);
            if (dataRow == null) continue;

            HSSFCell cell = dataRow.getCell(col);
            if (cell == null) continue;

            String value = getCellStringValue(cell).trim();
            if (value.isEmpty()) continue;

            columnCount++;

            // Validate column type prefix
            if (value.contains("#")) {
                String[] parts = value.split("#");
                String prefix = parts[0].trim();
                if (!VALID_TYPE_PREFIXES.contains(prefix)) {
                    addError("Model sheet: Invalid column type prefix '" + prefix + "' at row " + (row + 1) + ", column " + (col + 1));
                    showExample("COLUMN_TYPES", value);
                }

                // Check L# has corresponding reference
                if ("L".equals(prefix) && parts.length > 1) {
                    String refName = parts[1].trim();
                    // Will validate against List sheet later
                }
            }
        }

        if (columnCount == 0) {
            addWarning("Model sheet: Table '" + tableName + "' has no columns defined");
        }
    }

    private void validateDataSheets(HSSFWorkbook workbook) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            HSSFSheet sheet = workbook.getSheetAt(i);
            String sheetName = sheet.getSheetName();

            // Data sheets have underscore in name (e.g., HR_Employee)
            if (sheetName.contains("_") && !"Config".equals(sheetName)) {
                validateDataSheet(sheet);
            }
        }
    }

    private void validateDataSheet(HSSFSheet sheet) {
        String sheetName = sheet.getSheetName();
        logVerbose("Validating data sheet: " + sheetName);

        HSSFRow headerRow = sheet.getRow(0);
        if (headerRow == null) {
            addError("Data sheet '" + sheetName + "': Row 1 (column headers) is empty");
            showExample("DATA_SHEET", sheetName);
            return;
        }

        // Check headers match column names
        int colCount = headerRow.getLastCellNum();
        for (int col = 0; col < colCount; col++) {
            HSSFCell cell = headerRow.getCell(col);
            if (cell == null || getCellStringValue(cell).trim().isEmpty()) {
                addError("Data sheet '" + sheetName + "': Column " + (col + 1) + " header is empty");
            }
        }

        // Check for data rows
        if (sheet.getLastRowNum() < 1) {
            addWarning("Data sheet '" + sheetName + "': No data rows found");
        }
    }

    private void showExample(String exampleType, String context) {
        System.out.println();
        System.out.println("  ┌─────────────────────────────────────────────────────────────┐");

        switch (exampleType) {
            case "REQUIRED_SHEETS":
                System.out.println("  │  REQUIRED EXCEL STRUCTURE:                                  │");
                System.out.println("  │                                                             │");
                System.out.println("  │  Your Excel file should have these sheets:                  │");
                System.out.println("  │                                                             │");
                System.out.println("  │    [Config]  - Optional: Bundle configuration               │");
                System.out.println("  │    [List]    - Optional: Reference list values              │");
                System.out.println("  │    [Model]   - REQUIRED: Table/Window definitions           │");
                System.out.println("  │    [XX_Name] - Optional: Data import sheets                 │");
                break;

            case "LIST_SHEET":
                System.out.println("  │  LIST SHEET FORMAT:                                         │");
                System.out.println("  │                                                             │");
                System.out.println("  │  Row 1 = Reference names (headers)                          │");
                System.out.println("  │  Row 2+ = List values                                       │");
                System.out.println("  │                                                             │");
                System.out.println("  │  Example:                                                   │");
                System.out.println("  │  ┌──────────┬──────────┬──────────┐                         │");
                System.out.println("  │  │ Status   │ Priority │ Category │  <- Row 1 (headers)     │");
                System.out.println("  │  ├──────────┼──────────┼──────────┤                         │");
                System.out.println("  │  │ Draft    │ High     │ TypeA    │  <- Row 2 (values)      │");
                System.out.println("  │  │ Active   │ Medium   │ TypeB    │                         │");
                System.out.println("  │  │ Closed   │ Low      │ TypeC    │                         │");
                System.out.println("  │  └──────────┴──────────┴──────────┘                         │");
                break;

            case "MODEL_SHEET_A1":
                System.out.println("  │  MODEL SHEET - CELL A1:                                     │");
                System.out.println("  │                                                             │");
                System.out.println("  │  Cell A1 must contain the MAIN MENU name.                   │");
                System.out.println("  │  The first 2 characters become the table prefix.            │");
                System.out.println("  │                                                             │");
                System.out.println("  │  Example:                                                   │");
                System.out.println("  │  ┌────────────────┬──────────┬────────────┐                 │");
                System.out.println("  │  │ HumanResources │          │            │  <- A1          │");
                System.out.println("  │  └────────────────┴──────────┴────────────┘                 │");
                System.out.println("  │                                                             │");
                System.out.println("  │  This creates: Menu='HumanResources', Prefix='HR_'          │");
                break;

            case "MODEL_SHEET_ROW2":
                System.out.println("  │  MODEL SHEET - ROW 2 (Table/Window Names):                  │");
                System.out.println("  │                                                             │");
                System.out.println("  │  Row 2 defines your tables/windows.                         │");
                System.out.println("  │  Column A = empty, Columns B+ = table names                 │");
                System.out.println("  │                                                             │");
                System.out.println("  │  Example:                                                   │");
                System.out.println("  │  ┌────────────────┬──────────┬────────────┐                 │");
                System.out.println("  │  │ HumanResources │          │            │  <- Row 1       │");
                System.out.println("  │  ├────────────────┼──────────┼────────────┤                 │");
                System.out.println("  │  │                │ Employee │ Department │  <- Row 2       │");
                System.out.println("  │  ├────────────────┼──────────┼────────────┤                 │");
                System.out.println("  │  │                │ Name     │ Name       │  <- Row 3+      │");
                System.out.println("  │  │                │ L#Status │ Code       │  (columns)      │");
                System.out.println("  │  │                │ A#Salary │            │                 │");
                System.out.println("  │  └────────────────┴──────────┴────────────┘                 │");
                break;

            case "COLUMN_TYPES":
                System.out.println("  │  COLUMN TYPE PREFIXES:                                      │");
                System.out.println("  │                                                             │");
                System.out.println("  │  Found invalid prefix in: '" + padRight(context, 30) + "'  │");
                System.out.println("  │                                                             │");
                System.out.println("  │  Valid prefixes:                                            │");
                System.out.println("  │    (none)  String, 22 chars      Example: Name              │");
                System.out.println("  │    S#      String, 22 chars      Example: S#Description     │");
                System.out.println("  │    Q#      Quantity (number)     Example: Q#Quantity        │");
                System.out.println("  │    A#      Amount (currency)     Example: A#Salary          │");
                System.out.println("  │    Y#      Yes/No (checkbox)     Example: Y#IsActive        │");
                System.out.println("  │    D#      Date                  Example: D#HireDate        │");
                System.out.println("  │    d#      DateTime              Example: d#StartTime       │");
                System.out.println("  │    T#      Text (2000 chars)     Example: T#Comments        │");
                System.out.println("  │    L#      List (reference)      Example: L#Status          │");
                break;

            case "DATA_SHEET":
                System.out.println("  │  DATA SHEET FORMAT ('" + padRight(context, 30) + "'):      │");
                System.out.println("  │                                                             │");
                System.out.println("  │  Row 1 = Column names (must match Model sheet)              │");
                System.out.println("  │  Row 2+ = Data to import                                    │");
                System.out.println("  │                                                             │");
                System.out.println("  │  Example for sheet 'HR_Department':                         │");
                System.out.println("  │  ┌──────────┬────────────────┐                              │");
                System.out.println("  │  │ Name     │ Code           │  <- Row 1 (column names)    │");
                System.out.println("  │  ├──────────┼────────────────┤                              │");
                System.out.println("  │  │ Sales    │ SALES          │  <- Row 2+ (data)           │");
                System.out.println("  │  │ HR       │ HR             │                              │");
                System.out.println("  │  │ IT       │ IT             │                              │");
                System.out.println("  │  └──────────┴────────────────┘                              │");
                break;
        }

        System.out.println("  └─────────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    private String padRight(String s, int n) {
        if (s == null) s = "";
        return String.format("%-" + n + "s", s.length() > n ? s.substring(0, n) : s);
    }

    private void printValidationResults() {
        System.out.println();

        if (!warnings.isEmpty()) {
            System.out.println("  WARNINGS (" + warnings.size() + "):");
            for (String warning : warnings) {
                System.out.println("    [!] " + warning);
            }
            System.out.println();
        }

        if (!errors.isEmpty()) {
            System.out.println("  ERRORS (" + errors.size() + "):");
            for (String error : errors) {
                System.out.println("    [X] " + error);
            }
            System.out.println();
        }

        if (errors.isEmpty()) {
            System.out.println("  [OK] Validation passed!");
        } else {
            System.out.println("  [FAILED] Please fix the errors above and try again.");
        }
        System.out.println();
    }

    private void addError(String message) {
        errors.add(message);
    }

    private void addWarning(String message) {
        warnings.add(message);
    }

    private void log(String message) {
        System.out.println("[VALIDATOR] " + message);
    }

    private void logVerbose(String message) {
        if (verbose) {
            System.out.println("[VALIDATOR] " + message);
        }
    }

    private String getCellStringValue(HSSFCell cell) {
        if (cell == null) return "";

        if (cell.getCellType() == CellType.STRING) {
            return cell.getStringCellValue();
        } else if (cell.getCellType() == CellType.NUMERIC) {
            return String.valueOf((int) cell.getNumericCellValue());
        } else if (cell.getCellType() == CellType.BOOLEAN) {
            return String.valueOf(cell.getBooleanCellValue());
        }
        return "";
    }

    // Getters for extracted configuration
    public String getBundleName() { return bundleName; }
    public String getBundleVersion() { return bundleVersion; }
    public String getBundleVendor() { return bundleVendor; }
    public String getPackagePrefix() { return packagePrefix; }
    public String getEntityType() { return entityType; }
    public String getMenuPrefix() { return menuPrefix; }
}
