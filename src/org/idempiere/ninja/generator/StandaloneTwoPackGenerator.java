/******************************************************************************
 * Product: iDempiere Ninja Plugin Generator                                  *
 * Copyright (C) Contributors                                                 *
 * @author red1 - red1org@gmail.com                                           *
 *                                                                            *
 * Standalone 2Pack Generator - No DB, No OSGI required                       *
 * Reads Excel directly and outputs 2Pack XML                                 *
 *****************************************************************************/
package org.idempiere.ninja.generator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Standalone 2Pack Generator
 *
 * Creates 2Pack.zip directly from Excel WITHOUT:
 * - Database connection
 * - OSGI runtime
 * - iDempiere installation
 *
 * Can be run as simple Java application.
 *
 * @author red1 - red1org@gmail.com
 */
public class StandaloneTwoPackGenerator {

    private String excelFilePath;
    private String outputDirectory;
    private boolean verbose;

    // Config from Excel
    private String bundleName = "org.idempiere.module";
    private String bundleVersion = "1.0.0";
    private String entityType = "U";
    private String menuPrefix = "XX_";

    // Parsed data
    private List<TableDef> tables = new ArrayList<>();
    private List<ReferenceDef> references = new ArrayList<>();
    private String mainMenuName;

    private Document doc;
    private Element rootElement;

    public StandaloneTwoPackGenerator(String excelFilePath, String outputDirectory, boolean verbose) {
        this.excelFilePath = excelFilePath;
        this.outputDirectory = outputDirectory;
        this.verbose = verbose;
    }

    /**
     * Generate 2Pack.zip from Excel - completely standalone
     */
    public String generate() throws Exception {
        log("Standalone 2Pack Generator");
        log("Excel: " + excelFilePath);

        // Parse Excel
        parseExcel();

        // Generate XML
        String twoPackPath = outputDirectory + File.separator + "2Pack_" + bundleVersion + ".zip";
        generateTwoPack(twoPackPath);

        log("2Pack created: " + twoPackPath);
        return twoPackPath;
    }

    private void parseExcel() throws Exception {
        try (FileInputStream fis = new FileInputStream(excelFilePath);
             HSSFWorkbook workbook = new HSSFWorkbook(fis)) {

            // Parse Config sheet
            HSSFSheet configSheet = workbook.getSheet("Config");
            if (configSheet != null) {
                parseConfigSheet(configSheet);
            }

            // Parse List sheet (references)
            HSSFSheet listSheet = workbook.getSheet("List");
            if (listSheet != null) {
                parseListSheet(listSheet);
            }

            // Parse Model sheet (tables/columns)
            HSSFSheet modelSheet = workbook.getSheet("Model");
            if (modelSheet != null) {
                parseModelSheet(modelSheet);
            }
        }
    }

    private void parseConfigSheet(HSSFSheet sheet) {
        for (int i = 0; i <= sheet.getLastRowNum(); i++) {
            HSSFRow row = sheet.getRow(i);
            if (row == null) continue;

            String key = getCellString(row.getCell(0));
            String value = getCellString(row.getCell(1));

            switch (key) {
                case "Bundle-Name": bundleName = value; break;
                case "Bundle-Version": if (!value.isEmpty()) bundleVersion = value; break;
                case "Entity-Type": if (!value.isEmpty()) entityType = value; break;
            }
        }
        logVerbose("Config: " + bundleName + " v" + bundleVersion);
    }

    private void parseListSheet(HSSFSheet sheet) {
        HSSFRow headerRow = sheet.getRow(0);
        if (headerRow == null) return;

        int colCount = headerRow.getLastCellNum();

        for (int col = 0; col < colCount; col++) {
            String refName = getCellString(headerRow.getCell(col));
            if (refName.isEmpty()) continue;

            ReferenceDef ref = new ReferenceDef(refName);

            // Get values
            for (int row = 1; row <= sheet.getLastRowNum(); row++) {
                HSSFRow dataRow = sheet.getRow(row);
                if (dataRow == null) continue;

                String value = getCellString(dataRow.getCell(col));
                if (!value.isEmpty()) {
                    ref.values.add(value);
                }
            }

            references.add(ref);
            logVerbose("Reference: " + refName + " (" + ref.values.size() + " values)");
        }
    }

    private void parseModelSheet(HSSFSheet sheet) {
        // A1 = Main menu name
        HSSFRow row0 = sheet.getRow(0);
        if (row0 != null && row0.getCell(0) != null) {
            mainMenuName = getCellString(row0.getCell(0));
            menuPrefix = mainMenuName.substring(0, Math.min(2, mainMenuName.length())).toUpperCase() + "_";
        }

        // Row 2 = Table names
        HSSFRow row1 = sheet.getRow(1);
        if (row1 == null) return;

        int colCount = row1.getLastCellNum();

        for (int col = 0; col < colCount; col++) {
            String tableName = getCellString(row1.getCell(col));
            if (tableName.isEmpty()) continue;

            String adName = tableName.contains("_") ? tableName : menuPrefix + tableName.replaceAll("\\s+", "");
            TableDef table = new TableDef(adName, tableName);

            // Get columns (row 3+)
            for (int row = 2; row <= sheet.getLastRowNum(); row++) {
                HSSFRow dataRow = sheet.getRow(row);
                if (dataRow == null) continue;

                String colDef = getCellString(dataRow.getCell(col));
                if (colDef.isEmpty()) continue;

                ColumnDef column = parseColumnDef(colDef);
                table.columns.add(column);
            }

            tables.add(table);
            logVerbose("Table: " + adName + " (" + table.columns.size() + " columns)");
        }
    }

    private ColumnDef parseColumnDef(String def) {
        String name = def;
        String type = "String";
        int length = 22;
        int refId = 10; // String

        if (def.contains("#")) {
            String[] parts = def.split("#");
            String prefix = parts[0].trim();
            name = parts[1].trim();

            switch (prefix) {
                case "Q": type = "Quantity"; refId = 29; length = 11; break;
                case "A": type = "Amount"; refId = 12; length = 11; break;
                case "Y": type = "YesNo"; refId = 20; length = 1; break;
                case "D": type = "Date"; refId = 15; break;
                case "d": type = "DateTime"; refId = 16; break;
                case "T": type = "Text"; refId = 14; length = 2000; break;
                case "L": type = "List"; refId = 17; break;
            }
        }

        String colName = name.replaceAll("\\s+", "");
        if (colName.endsWith("_ID") && !colName.contains(menuPrefix)) {
            colName = menuPrefix + colName;
            refId = 19; // TableDir
        }

        return new ColumnDef(colName, name, type, refId, length);
    }

    private void generateTwoPack(String outputPath) throws Exception {
        // Create XML document
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        doc = builder.newDocument();

        rootElement = doc.createElement("idempiere");
        doc.appendChild(rootElement);

        // Add header
        Element header = doc.createElement("adempiereAD");
        header.setAttribute("Name", bundleName);
        header.setAttribute("Version", bundleVersion);
        header.setAttribute("CompVer", "iDempiere 11+");
        header.setAttribute("DataBase", "All");
        rootElement.appendChild(header);

        // Add references
        for (ReferenceDef ref : references) {
            addReference(ref);
        }

        // Add tables with columns
        for (TableDef table : tables) {
            addTable(table);
        }

        // Add windows/tabs
        for (TableDef table : tables) {
            addWindow(table);
        }

        // Add menu
        addMenu();

        // Write to ZIP
        String xmlContent = documentToString(doc);

        try (FileOutputStream fos = new FileOutputStream(outputPath);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            ZipEntry entry = new ZipEntry("PackOut.xml");
            zos.putNextEntry(entry);
            zos.write(xmlContent.getBytes("UTF-8"));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("dict/"));
            zos.closeEntry();
        }
    }

    private void addReference(ReferenceDef ref) {
        Element refEl = doc.createElement("AD_Reference");
        refEl.setAttribute("AD_Reference_UU", uuid());
        refEl.setAttribute("Name", ref.name);
        refEl.setAttribute("ValidationType", "L");
        refEl.setAttribute("EntityType", entityType);
        refEl.setAttribute("IsActive", "Y");
        rootElement.appendChild(refEl);

        for (String value : ref.values) {
            Element listEl = doc.createElement("AD_Ref_List");
            listEl.setAttribute("AD_Ref_List_UU", uuid());
            listEl.setAttribute("AD_Reference_ID", ref.name);
            listEl.setAttribute("Name", value);
            listEl.setAttribute("Value", value.substring(0, Math.min(2, value.length())));
            listEl.setAttribute("EntityType", entityType);
            listEl.setAttribute("IsActive", "Y");
            rootElement.appendChild(listEl);
        }
    }

    private void addTable(TableDef table) {
        Element tableEl = doc.createElement("AD_Table");
        tableEl.setAttribute("AD_Table_UU", uuid());
        tableEl.setAttribute("TableName", table.adName);
        tableEl.setAttribute("Name", addSpaces(table.displayName));
        tableEl.setAttribute("AccessLevel", "3");
        tableEl.setAttribute("EntityType", entityType);
        tableEl.setAttribute("IsActive", "Y");
        tableEl.setAttribute("IsDeleteable", "Y");
        tableEl.setAttribute("IsChangeLog", "N");
        tableEl.setAttribute("ReplicationType", "L");
        rootElement.appendChild(tableEl);

        // ID column
        addColumn(table.adName, table.adName + "_ID", addSpaces(table.displayName), 13, 22, true, true);

        // Standard columns
        addColumn(table.adName, "AD_Client_ID", "Client", 19, 22, true, false);
        addColumn(table.adName, "AD_Org_ID", "Organization", 19, 22, true, false);
        addColumn(table.adName, "IsActive", "Active", 20, 1, true, false);
        addColumn(table.adName, "Created", "Created", 16, 7, true, false);
        addColumn(table.adName, "CreatedBy", "Created By", 18, 22, true, false);
        addColumn(table.adName, "Updated", "Updated", 16, 7, true, false);
        addColumn(table.adName, "UpdatedBy", "Updated By", 18, 22, true, false);

        // Custom columns
        for (ColumnDef col : table.columns) {
            addColumn(table.adName, col.columnName, addSpaces(col.displayName), col.referenceId, col.length, false, false);
        }
    }

    private void addColumn(String tableName, String colName, String name, int refId, int length, boolean mandatory, boolean isKey) {
        Element colEl = doc.createElement("AD_Column");
        colEl.setAttribute("AD_Column_UU", uuid());
        colEl.setAttribute("AD_Table_ID", tableName);
        colEl.setAttribute("ColumnName", colName);
        colEl.setAttribute("Name", name);
        colEl.setAttribute("AD_Reference_ID", String.valueOf(refId));
        colEl.setAttribute("FieldLength", String.valueOf(length));
        colEl.setAttribute("IsMandatory", mandatory ? "Y" : "N");
        colEl.setAttribute("IsKey", isKey ? "Y" : "N");
        colEl.setAttribute("IsUpdateable", isKey ? "N" : "Y");
        colEl.setAttribute("EntityType", entityType);
        colEl.setAttribute("IsActive", "Y");
        rootElement.appendChild(colEl);
    }

    private void addWindow(TableDef table) {
        String winName = addSpaces(table.displayName);

        Element winEl = doc.createElement("AD_Window");
        winEl.setAttribute("AD_Window_UU", uuid());
        winEl.setAttribute("Name", winName);
        winEl.setAttribute("WindowType", "M");
        winEl.setAttribute("EntityType", entityType);
        winEl.setAttribute("IsActive", "Y");
        winEl.setAttribute("IsSOTrx", "Y");
        rootElement.appendChild(winEl);

        // Tab
        Element tabEl = doc.createElement("AD_Tab");
        tabEl.setAttribute("AD_Tab_UU", uuid());
        tabEl.setAttribute("AD_Window_ID", winName);
        tabEl.setAttribute("AD_Table_ID", table.adName);
        tabEl.setAttribute("Name", winName);
        tabEl.setAttribute("SeqNo", "10");
        tabEl.setAttribute("TabLevel", "0");
        tabEl.setAttribute("EntityType", entityType);
        tabEl.setAttribute("IsActive", "Y");
        rootElement.appendChild(tabEl);
    }

    private void addMenu() {
        if (mainMenuName == null) return;

        // Main menu (summary)
        Element mainEl = doc.createElement("AD_Menu");
        mainEl.setAttribute("AD_Menu_UU", uuid());
        mainEl.setAttribute("Name", mainMenuName);
        mainEl.setAttribute("IsSummary", "Y");
        mainEl.setAttribute("EntityType", entityType);
        mainEl.setAttribute("IsActive", "Y");
        mainEl.setAttribute("IsSOTrx", "Y");
        mainEl.setAttribute("IsReadOnly", "N");
        rootElement.appendChild(mainEl);

        // Window menus
        for (TableDef table : tables) {
            Element menuEl = doc.createElement("AD_Menu");
            menuEl.setAttribute("AD_Menu_UU", uuid());
            menuEl.setAttribute("Name", addSpaces(table.displayName));
            menuEl.setAttribute("Action", "W");
            menuEl.setAttribute("AD_Window_ID", addSpaces(table.displayName));
            menuEl.setAttribute("IsSummary", "N");
            menuEl.setAttribute("EntityType", entityType);
            menuEl.setAttribute("IsActive", "Y");
            menuEl.setAttribute("IsSOTrx", "Y");
            rootElement.appendChild(menuEl);
        }
    }

    // Helper classes
    private static class TableDef {
        String adName;
        String displayName;
        List<ColumnDef> columns = new ArrayList<>();
        TableDef(String adName, String displayName) {
            this.adName = adName;
            this.displayName = displayName;
        }
    }

    private static class ColumnDef {
        String columnName;
        String displayName;
        String type;
        int referenceId;
        int length;
        ColumnDef(String columnName, String displayName, String type, int refId, int length) {
            this.columnName = columnName;
            this.displayName = displayName;
            this.type = type;
            this.referenceId = refId;
            this.length = length;
        }
    }

    private static class ReferenceDef {
        String name;
        List<String> values = new ArrayList<>();
        ReferenceDef(String name) { this.name = name; }
    }

    // Utilities
    private String getCellString(HSSFCell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue();
        if (cell.getCellType() == CellType.NUMERIC) return String.valueOf((int) cell.getNumericCellValue());
        return "";
    }

    private String addSpaces(String s) {
        return s.replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    private String uuid() {
        return UUID.randomUUID().toString();
    }

    private String documentToString(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }

    private void log(String msg) { System.out.println("[2PACK] " + msg); }
    private void logVerbose(String msg) { if (verbose) log(msg); }

    /**
     * Main - can run completely standalone
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java StandaloneTwoPackGenerator <ExcelFile> [outputDir]");
            System.exit(1);
        }

        String excel = args[0];
        String outDir = args.length > 1 ? args[1] : new File(excel).getParent();

        StandaloneTwoPackGenerator gen = new StandaloneTwoPackGenerator(excel, outDir, true);
        gen.generate();
    }
}
