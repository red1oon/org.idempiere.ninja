/******************************************************************************
 * Product: iDempiere Ninja Plugin Generator                                  *
 * Copyright (C) Contributors                                                 *
 * @author red1 - red1org@gmail.com                                           *
 *                                                                            *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 *****************************************************************************/
package org.idempiere.ninja.core;

import java.io.File;
import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFDataFormatter;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import org.compiere.model.MColumn;
import org.compiere.model.MField;
import org.compiere.model.MMenu;
import org.compiere.model.MPackageExp;
import org.compiere.model.MPackageExpDetail;
import org.compiere.model.MProcess;
import org.compiere.model.MProcessPara;
import org.compiere.model.MRefList;
import org.compiere.model.MReference;
import org.compiere.model.MTab;
import org.compiere.model.MTable;
import org.compiere.model.MTree_Base;
import org.compiere.model.MTree_NodeMM;
import org.compiere.model.MWindow;
import org.compiere.model.M_Element;
import org.compiere.model.PO;
import org.compiere.model.Query;
import org.compiere.model.X_AD_Menu;
import org.compiere.model.X_AD_Process;
import org.compiere.model.X_AD_Reference;
import org.compiere.model.X_AD_TreeNodeMM;
import org.compiere.util.DB;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;

/**
 * Ninja Processor - Core AD model creation engine
 *
 * Ported and enhanced from ModuleMaker by red1
 * Creates AD models (Tables, Columns, Windows, Tabs, Fields, Menus)
 * from Excel definitions.
 *
 * @author red1 - red1org@gmail.com
 */
public class NinjaProcessor {

    private String excelFilePath;
    private String trxName;
    private boolean verbose;
    private HSSFWorkbook workbook;

    // Configuration
    private String bundleName;
    private String bundleVersion = "1.0.0";
    private String packagePrefix;
    private String entityType = "U";
    private String menuPrefix;

    // Tracking collections
    private List<Integer> refset = new ArrayList<>();
    private List<String> masterset = new ArrayList<>();
    private List<MTable> tableset = new ArrayList<>();
    private List<MWindow> windowset = new ArrayList<>();
    private List<MTab> tabset = new ArrayList<>();
    private List<MMenu> menuset = new ArrayList<>();
    private List<MProcess> processset = new ArrayList<>();
    private MMenu mainMenu;

    // Statistics
    private int tablesCreated = 0;
    private int columnsCreated = 0;
    private int windowsCreated = 0;
    private int menusCreated = 0;
    private int referencesCreated = 0;
    private int fieldsCreated = 0;
    private int processesCreated = 0;

    public NinjaProcessor(String excelFilePath, String trxName, boolean verbose) {
        this.excelFilePath = excelFilePath;
        this.trxName = trxName;
        this.verbose = verbose;
    }

    /**
     * Inject AD models into database from Excel
     */
    public void injectIntoDatabase() throws Exception {
        log("Reading Excel file: " + excelFilePath);

        try (FileInputStream fis = new FileInputStream(new File(excelFilePath))) {
            workbook = new HSSFWorkbook(fis);

            // Parse Config sheet first
            parseConfigSheet();

            // Process sheets in order
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                HSSFSheet sheet = workbook.getSheetAt(i);
                String sheetName = sheet.getSheetName();

                if ("Config".equals(sheetName)) {
                    continue; // Already processed
                } else if ("List".equals(sheetName)) {
                    log("Processing List sheet (Reference values)...");
                    processListSheet(sheet);
                } else if ("Model".equals(sheetName)) {
                    log("Processing Model sheet (Tables/Windows)...");
                    processModelSheet(sheet);
                } else if ("Process".equals(sheetName)) {
                    log("Processing Process sheet (AD_Process)...");
                    processProcessSheet(sheet);
                } else if ("Menu".equals(sheetName)) {
                    log("Processing Menu sheet (custom menu structure)...");
                    processMenuSheet(sheet);
                } else if (sheetName.contains("_")) {
                    log("Processing Data sheet: " + sheetName);
                    processDataSheet(sheet);
                }
            }

            // Synchronize tables to database
            log("Synchronizing tables to database...");
            synchronizeTables();

            logSummary();

        } finally {
            if (workbook != null) {
                workbook.close();
            }
        }
    }

    private void parseConfigSheet() {
        HSSFSheet configSheet = workbook.getSheet("Config");
        if (configSheet == null) return;

        for (int i = 0; i <= configSheet.getLastRowNum(); i++) {
            HSSFRow row = configSheet.getRow(i);
            if (row == null) continue;

            String key = getCellString(row.getCell(0));
            String value = getCellString(row.getCell(1));

            switch (key) {
                case "Bundle-Name":
                    bundleName = value;
                    break;
                case "Bundle-Version":
                    if (!value.isEmpty()) bundleVersion = value;
                    break;
                case "Package-Prefix":
                    packagePrefix = value;
                    break;
                case "Entity-Type":
                    if (!value.isEmpty()) entityType = value;
                    break;
            }
        }
    }

    private void processListSheet(HSSFSheet sheet) {
        int rowCount = sheet.getLastRowNum() + 1;

        for (int i = 0; i < rowCount; i++) {
            HSSFRow row = sheet.getRow(i);
            if (row == null) continue;

            int colCount = row.getLastCellNum();

            if (i == 0) {
                // Header row - create references
                for (int col = 0; col < colCount; col++) {
                    HSSFCell cell = row.getCell(col);
                    if (cell != null && !getCellString(cell).isEmpty()) {
                        int refId = createReference(cell);
                        refset.add(refId);
                        referencesCreated++;
                    }
                }
            } else {
                // Data rows - create reference list values
                for (int col = 0; col < colCount && col < refset.size(); col++) {
                    HSSFCell cell = row.getCell(col);
                    if (cell != null && !getCellString(cell).isEmpty()) {
                        createRefListValue(cell, refset.get(col));
                    }
                }
            }
        }

        log("  Created " + referencesCreated + " references");
    }

    private int createReference(HSSFCell cell) {
        String refName = getCellString(cell);

        MReference ref = new Query(Env.getCtx(), X_AD_Reference.Table_Name,
                X_AD_Reference.COLUMNNAME_Name + "=?", trxName)
                .setParameters(refName)
                .first();

        if (ref == null) {
            ref = new MReference(Env.getCtx(), 0, trxName);
            ref.setValidationType(X_AD_Reference.VALIDATIONTYPE_ListValidation);
            ref.setEntityType(entityType);
            ref.setName(refName);
            ref.saveEx(trxName);
            logVerbose("  Created Reference: " + refName);
        }

        return ref.getAD_Reference_ID();
    }

    private void createRefListValue(HSSFCell cell, int referenceId) {
        String name = getCellString(cell);
        if (name.isEmpty()) return;

        MRefList refList = new Query(Env.getCtx(), MRefList.Table_Name,
                MRefList.COLUMNNAME_Name + "=? AND " + MRefList.COLUMNNAME_AD_Reference_ID + "=?", trxName)
                .setParameters(name, referenceId)
                .first();

        if (refList == null) {
            refList = new MRefList(Env.getCtx(), 0, trxName);
            refList.setAD_Reference_ID(referenceId);
            refList.setName(name);
            refList.setValue(name.substring(0, Math.min(2, name.length())));
            refList.saveEx(trxName);
            logVerbose("    Added list value: " + name);
        }
    }

    private void processModelSheet(HSSFSheet sheet) throws Exception {
        // A1 = Main menu name
        HSSFRow firstRow = sheet.getRow(0);
        HSSFCell a1 = firstRow.getCell(0);
        String mainMenuName = getCellString(a1);

        menuPrefix = mainMenuName.substring(0, Math.min(2, mainMenuName.length())).toUpperCase() + "_";
        if (packagePrefix == null) {
            packagePrefix = "org.idempiere." + mainMenuName.toLowerCase().replaceAll("\\s+", "");
        }
        if (bundleName == null) {
            bundleName = packagePrefix;
        }

        log("  Menu: " + mainMenuName + ", Prefix: " + menuPrefix);

        // Create main menu
        mainMenu = createMainMenu(mainMenuName);

        int rowCount = sheet.getLastRowNum() + 1;

        for (int i = 0; i < rowCount; i++) {
            HSSFRow row = sheet.getRow(i);
            if (row == null) continue;

            int colCount = row.getLastCellNum();

            for (int col = 0; col < colCount; col++) {
                HSSFCell cell = row.getCell(col);
                if (cell == null) continue;

                String cellValue = getCellString(cell).trim();
                if (cellValue.isEmpty()) {
                    if (i == 0) masterset.add("");
                    continue;
                }

                // Build AD name with prefix
                String adName;
                if (cellValue.contains("_")) {
                    adName = cellValue;
                } else {
                    adName = menuPrefix + cellValue.replaceAll("\\s+", "");
                }

                if (i == 0) {
                    // Row 1 - Master/parent names
                    if (col == 0) {
                        masterset.add("");
                    } else {
                        masterset.add(cellValue);
                    }
                } else if (i == 1) {
                    // Row 2 - Table/Window names
                    if (col == 0 || (col < masterset.size() && masterset.get(col).isEmpty())) {
                        MWindow window = createWindow(cellValue);
                        windowset.add(window);
                        windowsCreated++;
                    } else {
                        // Child table - reuse parent window
                        MWindow window = new Query(Env.getCtx(), MWindow.Table_Name,
                                MWindow.COLUMNNAME_Name + "=?", trxName)
                                .setParameters(masterset.get(col))
                                .first();
                        windowset.add(window);
                    }

                    MTable table = createTable(cellValue, adName);
                    tableset.add(table);
                    tablesCreated++;

                    MTab tab = createTab(cellValue, col);
                    tabset.add(tab);

                } else {
                    // Row 3+ - Column definitions
                    if (col < tableset.size()) {
                        addColumn(tableset.get(col), cellValue);
                        columnsCreated++;
                    }
                }
            }
        }

        // Create fields for all tabs
        for (MTab tab : tabset) {
            createTabFields(tab);
        }

        log("  Created " + tablesCreated + " tables, " + windowsCreated + " windows");
    }

    /**
     * Process Process sheet - creates AD_Process, AD_Process_Para, AD_Table_Process
     *
     * Excel format:
     * | ProcessName | Class | Table | Parameters |
     * | CheckUpdate | org.idempiere.updater.process.CheckUpdateProcess | UD_UpdateHistory | ForceCheck:YesNo |
     */
    private void processProcessSheet(HSSFSheet sheet) {
        if (sheet == null) return;

        // Skip header row
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            HSSFRow row = sheet.getRow(i);
            if (row == null) continue;

            String processName = getCellString(row.getCell(0));
            String className = getCellString(row.getCell(1));
            String tableName = getCellString(row.getCell(2));
            String parameters = getCellString(row.getCell(3));

            if (processName.isEmpty()) continue;

            // Create AD_Process
            MProcess process = createProcess(processName, className);
            if (process == null) continue;

            processset.add(process);
            processesCreated++;

            // Create AD_Process_Para for each parameter
            if (!parameters.isEmpty()) {
                createProcessParameters(process, parameters);
            }

            // Link to table via AD_Table_Process
            if (!tableName.isEmpty()) {
                linkProcessToTable(process, tableName);
            }

            // Create menu entry for process
            createProcessMenu(process);
        }

        log("  Created " + processesCreated + " processes");
    }

    private MProcess createProcess(String processName, String className) {
        String displayName = addSpacesToCamelCase(processName);

        MProcess process = new Query(Env.getCtx(), X_AD_Process.Table_Name,
                X_AD_Process.COLUMNNAME_Value + "=?", trxName)
                .setParameters(processName)
                .first();

        if (process == null) {
            process = new MProcess(Env.getCtx(), 0, trxName);
            process.setValue(processName);
            process.setName(displayName);
            process.setEntityType(entityType);
            process.setAccessLevel(X_AD_Process.ACCESSLEVEL_ClientPlusOrganization);

            if (!className.isEmpty()) {
                process.setClassname(className);
            }

            process.saveEx(trxName);
            logVerbose("  Created process: " + displayName);
        }

        return process;
    }

    private void createProcessParameters(MProcess process, String parameters) {
        // Parameters format: "ParamName:Type, ParamName2:Type2"
        String[] params = parameters.split(",");
        int seqNo = 10;

        for (String param : params) {
            param = param.trim();
            if (param.isEmpty()) continue;

            String[] parts = param.split(":");
            String paramName = parts[0].trim();
            String paramType = parts.length > 1 ? parts[1].trim() : "String";

            MProcessPara para = new Query(Env.getCtx(), MProcessPara.Table_Name,
                    MProcessPara.COLUMNNAME_AD_Process_ID + "=? AND " + MProcessPara.COLUMNNAME_ColumnName + "=?", trxName)
                    .setParameters(process.getAD_Process_ID(), paramName)
                    .first();

            if (para == null) {
                // Get or create element
                M_Element element = M_Element.get(Env.getCtx(), paramName, trxName);
                if (element == null) {
                    element = new M_Element(Env.getCtx(), paramName, entityType, trxName);
                    element.saveEx(trxName);
                }

                para = new MProcessPara(process);
                para.setColumnName(paramName);
                para.setName(addSpacesToCamelCase(paramName));
                para.setAD_Element_ID(element.getAD_Element_ID());
                para.setSeqNo(seqNo);
                para.setEntityType(entityType);
                para.setFieldLength(60);

                // Set reference type
                int reference = getProcessParamReference(paramType);
                para.setAD_Reference_ID(reference);

                // For List type, find reference
                if ("List".equalsIgnoreCase(paramType) || paramType.startsWith("L#")) {
                    String refName = paramType.startsWith("L#") ? paramType.substring(2) : paramName;
                    MReference ref = new Query(Env.getCtx(), X_AD_Reference.Table_Name,
                            X_AD_Reference.COLUMNNAME_Name + "=?", trxName)
                            .setParameters(refName)
                            .first();
                    if (ref != null) {
                        para.setAD_Reference_Value_ID(ref.getAD_Reference_ID());
                    }
                }

                para.saveEx(trxName);
                logVerbose("    Added parameter: " + paramName + " (" + paramType + ")");
            }

            seqNo += 10;
        }
    }

    private int getProcessParamReference(String paramType) {
        switch (paramType.toLowerCase()) {
            case "yesno":
            case "boolean":
                return DisplayType.YesNo;
            case "integer":
            case "int":
                return DisplayType.Integer;
            case "date":
                return DisplayType.Date;
            case "datetime":
                return DisplayType.DateTime;
            case "amount":
                return DisplayType.Amount;
            case "quantity":
                return DisplayType.Quantity;
            case "tabledir":
                return DisplayType.TableDir;
            case "table":
                return DisplayType.Table;
            case "list":
                return DisplayType.List;
            default:
                if (paramType.startsWith("L#")) return DisplayType.List;
                return DisplayType.String;
        }
    }

    private void linkProcessToTable(MProcess process, String tableName) {
        MTable table = new Query(Env.getCtx(), MTable.Table_Name,
                MTable.COLUMNNAME_TableName + "=?", trxName)
                .setParameters(tableName)
                .first();

        if (table == null) {
            log("  WARNING: Table " + tableName + " not found for process link");
            return;
        }

        // Try to link via AD_Table_Process using direct SQL (compatible with all versions)
        // AD_Table_Process is the gear icon feature in iDempiere
        try {
            String checkSql = "SELECT COUNT(*) FROM AD_Table_Process WHERE AD_Table_ID=? AND AD_Process_ID=?";
            int count = DB.getSQLValue(trxName, checkSql, table.getAD_Table_ID(), process.getAD_Process_ID());

            if (count == 0) {
                // Insert directly via SQL for compatibility
                String insertSql = "INSERT INTO AD_Table_Process " +
                        "(AD_Table_Process_ID, AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy, Updated, UpdatedBy, " +
                        "AD_Table_ID, AD_Process_ID, EntityType, AD_Table_Process_UU) " +
                        "VALUES (nextID(AD_SEQUENCE_ID,'AD_Table_Process'), 0, 0, 'Y', NOW(), 100, NOW(), 100, " +
                        "?, ?, ?, generate_uuid())";

                int no = DB.executeUpdate(insertSql, new Object[]{table.getAD_Table_ID(), process.getAD_Process_ID(), entityType}, false, trxName);
                if (no > 0) {
                    logVerbose("  Linked process " + process.getName() + " to table " + tableName + " (gear icon)");
                }
            }
        } catch (Exception e) {
            // AD_Table_Process table may not exist in older iDempiere versions - just log and continue
            logVerbose("  Note: Could not link process to table (AD_Table_Process may not exist): " + e.getMessage());
        }
    }

    private void createProcessMenu(MProcess process) {
        MMenu menu = new Query(Env.getCtx(), MMenu.Table_Name,
                MMenu.COLUMNNAME_AD_Process_ID + "=?", trxName)
                .setParameters(process.getAD_Process_ID())
                .first();

        if (menu == null) {
            menu = new MMenu(Env.getCtx(), 0, trxName);
            menu.setName(process.getName());
            menu.setAD_Process_ID(process.getAD_Process_ID());
            menu.setAction(X_AD_Menu.ACTION_Process);
            menu.setEntityType(entityType);
            menu.set_ValueOfColumn(MMenu.COLUMNNAME_AD_Client_ID, 0);
            menu.setAD_Org_ID(0);
            menu.saveEx(trxName);

            // Attach to main menu tree
            if (mainMenu != null) {
                attachMenuToTree(menu);
            }

            menusCreated++;
            menuset.add(menu);
        }
    }

    /**
     * Process Menu sheet - custom menu structure (optional override)
     *
     * Excel format:
     * | MenuName | Type | Action | Window/Process | Parent |
     * | Updater | Summary | | | |
     * | Update History | Window | W | UpdateHistory | Updater |
     */
    private void processMenuSheet(HSSFSheet sheet) {
        if (sheet == null) return;

        // Skip header row
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            HSSFRow row = sheet.getRow(i);
            if (row == null) continue;

            String menuName = getCellString(row.getCell(0));
            String type = getCellString(row.getCell(1));
            String action = getCellString(row.getCell(2));
            String target = getCellString(row.getCell(3));
            String parent = getCellString(row.getCell(4));

            if (menuName.isEmpty()) continue;

            // Check if menu already exists
            MMenu menu = new Query(Env.getCtx(), MMenu.Table_Name,
                    MMenu.COLUMNNAME_Name + "=?", trxName)
                    .setParameters(menuName)
                    .first();

            if (menu == null) {
                menu = new MMenu(Env.getCtx(), 0, trxName);
                menu.setName(menuName);
                menu.setEntityType(entityType);
                menu.set_ValueOfColumn(MMenu.COLUMNNAME_AD_Client_ID, 0);
                menu.setAD_Org_ID(0);

                if ("Summary".equalsIgnoreCase(type)) {
                    menu.setIsSummary(true);
                } else {
                    menu.setIsSummary(false);

                    if ("W".equals(action) || "Window".equalsIgnoreCase(type)) {
                        menu.setAction(X_AD_Menu.ACTION_Window);
                        MWindow window = new Query(Env.getCtx(), MWindow.Table_Name,
                                MWindow.COLUMNNAME_Name + "=?", trxName)
                                .setParameters(addSpacesToCamelCase(target))
                                .first();
                        if (window != null) {
                            menu.setAD_Window_ID(window.getAD_Window_ID());
                        }
                    } else if ("P".equals(action) || "Process".equalsIgnoreCase(type)) {
                        menu.setAction(X_AD_Menu.ACTION_Process);
                        MProcess process = new Query(Env.getCtx(), X_AD_Process.Table_Name,
                                X_AD_Process.COLUMNNAME_Value + "=?", trxName)
                                .setParameters(target)
                                .first();
                        if (process != null) {
                            menu.setAD_Process_ID(process.getAD_Process_ID());
                        }
                    }
                }

                menu.saveEx(trxName);
                menusCreated++;
                menuset.add(menu);
            }

            // Attach to parent in menu tree
            if (!parent.isEmpty()) {
                MMenu parentMenu = new Query(Env.getCtx(), MMenu.Table_Name,
                        MMenu.COLUMNNAME_Name + "=?", trxName)
                        .setParameters(parent)
                        .first();
                if (parentMenu != null) {
                    attachMenuToParent(menu, parentMenu);
                }
            }
        }

        log("  Processed custom menu structure");
    }

    private void attachMenuToParent(MMenu menu, MMenu parentMenu) {
        MTree_Base menuTree = new MTree_Base(Env.getCtx(), 10, trxName);
        X_AD_TreeNodeMM treeNode = new Query(Env.getCtx(), MTree_NodeMM.Table_Name,
                MTree_NodeMM.COLUMNNAME_AD_Tree_ID + "=? AND " + MTree_NodeMM.COLUMNNAME_Node_ID + "=?", trxName)
                .setParameters(menuTree.get_ID(), menu.get_ID())
                .first();

        if (treeNode != null) {
            treeNode.setParent_ID(parentMenu.get_ID());
            treeNode.setAD_Org_ID(0);
            treeNode.saveEx(trxName);
        }
    }

    private MMenu createMainMenu(String menuName) {
        MMenu menu = new Query(Env.getCtx(), MMenu.Table_Name,
                MMenu.COLUMNNAME_Name + "=?", trxName)
                .setParameters(menuName)
                .first();

        if (menu == null) {
            menu = new MMenu(Env.getCtx(), 0, trxName);
            menu.setName(menuName);
            menu.setAction(X_AD_Menu.ACTION_Window);
            menu.setEntityType(entityType);
            menu.set_ValueOfColumn(MMenu.COLUMNNAME_AD_Client_ID, 0);
            menu.setAD_Org_ID(0);
            menu.setIsSummary(true);
            menu.saveEx(trxName);
            menusCreated++;
            logVerbose("  Created main menu: " + menuName);
        }

        menuset.add(menu);
        return menu;
    }

    private MWindow createWindow(String windowName) {
        String displayName = addSpacesToCamelCase(windowName);

        MWindow window = new Query(Env.getCtx(), MWindow.Table_Name,
                MWindow.COLUMNNAME_Name + "=?", trxName)
                .setParameters(displayName)
                .first();

        if (window == null) {
            window = new MWindow(Env.getCtx(), 0, trxName);
            window.setName(displayName);
            window.setWindowType("M");
            window.setEntityType(entityType);
            window.saveEx(trxName);

            // Create menu for window
            MMenu windowMenu = createWindowMenu(window);
            attachMenuToTree(windowMenu);

            logVerbose("  Created window: " + displayName);
        }

        return window;
    }

    private MMenu createWindowMenu(MWindow window) {
        MMenu menu = new Query(Env.getCtx(), MMenu.Table_Name,
                MMenu.COLUMNNAME_AD_Window_ID + "=?", trxName)
                .setParameters(window.getAD_Window_ID())
                .first();

        if (menu == null) {
            menu = new MMenu(Env.getCtx(), 0, trxName);
            menu.setName(window.getName());
            menu.setAD_Window_ID(window.getAD_Window_ID());
            menu.setAction(X_AD_Menu.ACTION_Window);
            menu.setEntityType(entityType);
            menu.set_ValueOfColumn(MMenu.COLUMNNAME_AD_Client_ID, 0);
            menu.setAD_Org_ID(0);
            menu.saveEx(trxName);
            menusCreated++;
        }

        menuset.add(menu);
        return menu;
    }

    private void attachMenuToTree(MMenu menu) {
        MTree_Base menuTree = new MTree_Base(Env.getCtx(), 10, trxName);
        X_AD_TreeNodeMM treeNode = new Query(Env.getCtx(), MTree_NodeMM.Table_Name,
                MTree_NodeMM.COLUMNNAME_AD_Tree_ID + "=? AND " + MTree_NodeMM.COLUMNNAME_Node_ID + "=?", trxName)
                .setParameters(menuTree.get_ID(), menu.get_ID())
                .first();

        if (treeNode != null) {
            treeNode.setParent_ID(mainMenu.get_ID());
            treeNode.setAD_Org_ID(0);
            treeNode.saveEx(trxName);
        }
    }

    private MTable createTable(String tableName, String adName) throws Exception {
        MTable table = new Query(Env.getCtx(), MTable.Table_Name,
                MTable.COLUMNNAME_TableName + "=?", trxName)
                .setParameters(adName)
                .first();

        if (table != null) {
            return table;
        }

        String displayName = addSpacesToCamelCase(tableName);

        // Create element for ID column
        M_Element idElement = M_Element.get(Env.getCtx(), adName + "_ID", trxName);
        if (idElement == null) {
            idElement = new M_Element(Env.getCtx(), 0, trxName);
            idElement.setColumnName(adName + "_ID");
            idElement.setPrintName(displayName);
            idElement.setName(displayName);
            idElement.setEntityType(entityType);
            idElement.saveEx(trxName);
        }

        // Create UUID element
        M_Element uuElement = M_Element.get(Env.getCtx(), adName + "_UU");
        if (uuElement == null) {
            uuElement = new M_Element(Env.getCtx(), adName + "_UU", entityType, trxName);
            uuElement.saveEx(trxName);
        }

        // Create table
        table = new MTable(Env.getCtx(), 0, trxName);
        table.setTableName(adName);
        table.setName(displayName);
        table.setDescription("");
        table.setEntityType(entityType);
        table.setAccessLevel("3"); // Client+Organization
        table.set_ValueOfColumn("AD_Client_ID", 0);
        table.saveEx(trxName);

        // Create ID column
        MColumn idColumn = new MColumn(Env.getCtx(), 0, trxName);
        idColumn.setColumnName(adName + "_ID");
        idColumn.setName(displayName);
        idColumn.setAD_Element_ID(idElement.get_ID());
        idColumn.setAD_Reference_ID(DisplayType.ID);
        idColumn.setFieldLength(22);
        idColumn.setIsMandatory(true);
        idColumn.setIsKey(true);
        idColumn.setAD_Table_ID(table.get_ID());
        idColumn.setEntityType(entityType);
        idColumn.saveEx(trxName);

        // Create standard columns
        createStandardColumns(table);

        logVerbose("  Created table: " + adName);
        return table;
    }

    private void createStandardColumns(MTable table) throws Exception {
        String copyList = "'AD_Client_ID','AD_Org_ID','Created','CreatedBy','UpdatedBy','Updated','IsActive'";
        String sql = "SELECT AD_COLUMN_ID FROM AD_COLUMN WHERE AD_Table_ID=102 AND ColumnName IN (" + copyList + ")";

        try (PreparedStatement ps = DB.prepareStatement(sql, trxName);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                MColumn srcCol = new MColumn(Env.getCtx(), rs.getInt(1), trxName);
                MColumn destCol = new MColumn(Env.getCtx(), 0, trxName);
                PO.copyValues(srcCol, destCol);
                destCol.set_ValueNoCheck("AD_Table_ID", table.get_ID());
                destCol.set_ValueNoCheck("EntityType", entityType);
                destCol.setIsMandatory(false);
                destCol.setDefaultValue("");

                try {
                    destCol.saveEx(trxName);
                    destCol.setIsMandatory(srcCol.isMandatory());
                    destCol.setDefaultValue(srcCol.getDefaultValue());
                    destCol.setDescription("");
                    destCol.setHelp("");
                    destCol.saveEx(trxName);
                } catch (Exception e) {
                    logVerbose("  Column already exists: " + srcCol.getColumnName());
                }
            }
        }
    }

    private MTab createTab(String tabName, int colIndex) throws Exception {
        MTable table = tableset.get(colIndex);
        String displayName = addSpacesToCamelCase(tabName);

        MTab tab = new Query(Env.getCtx(), MTab.Table_Name,
                MTab.COLUMNNAME_AD_Table_ID + "=? AND " + MTab.COLUMNNAME_Name + "=?", trxName)
                .setParameters(table.get_ID(), displayName)
                .first();

        if (tab != null) {
            return tab;
        }

        int windowIndex = colIndex;
        if (windowIndex >= windowset.size() || windowset.get(windowIndex) == null) {
            windowIndex--;
        }

        MWindow window = windowset.get(windowIndex);
        tab = new MTab(window);
        tab.setName(displayName);
        tab.setAD_Table_ID(table.getAD_Table_ID());

        int tabCount = new Query(Env.getCtx(), MTab.Table_Name,
                MTab.COLUMNNAME_AD_Window_ID + "=?", trxName)
                .setParameters(window.get_ID())
                .count();

        tab.setSeqNo(tabCount * 10 + 10);
        tab.setEntityType(entityType);

        // Set tab level based on parent
        int tabLevel = 0;
        if (colIndex < masterset.size() && !masterset.get(colIndex).isEmpty()) {
            MTab parentTab = new Query(Env.getCtx(), MTab.Table_Name,
                    MTab.COLUMNNAME_Name + "=?", trxName)
                    .setParameters(masterset.get(colIndex))
                    .first();
            if (parentTab != null) {
                tabLevel = parentTab.getTabLevel() + 1;
            }
        }
        tab.setTabLevel(tabLevel);
        tab.saveEx(trxName);

        logVerbose("  Created tab: " + displayName + " (level " + tabLevel + ")");
        return tab;
    }

    private void addColumn(MTable table, String cellValue) {
        String name = cellValue;
        String typePrefix = "";

        // Parse type prefix (e.g., "L#Status" -> type="L", name="Status")
        if (cellValue.contains("#")) {
            String[] parts = cellValue.split("#");
            typePrefix = parts[0].trim();
            name = parts[1].trim();
        }

        String columnName = name.replaceAll("\\s+", "");

        // Add prefix for _ID columns
        if (columnName.endsWith("_ID") && !columnName.contains(menuPrefix) && columnName.split("_").length < 3) {
            columnName = menuPrefix + columnName;
        }

        MColumn column = table.getColumn(columnName);
        if (column == null) {
            column = new MColumn(Env.getCtx(), 0, trxName);
        }

        // Get or create element
        M_Element element = M_Element.get(Env.getCtx(), columnName, trxName);
        if (element == null) {
            element = new M_Element(Env.getCtx(), columnName, entityType, trxName);
            element.saveEx(trxName);
        }

        column.setAD_Element_ID(element.get_ID());
        column.setColumnName(columnName);
        column.setAD_Table_ID(table.getAD_Table_ID());
        column.setEntityType(entityType);
        column.setName(addSpacesToCamelCase(name));
        column.setIsMandatory(false);
        column.setIsKey(false);

        // Set type based on prefix
        int reference = DisplayType.String;
        int length = 22;

        switch (typePrefix) {
            case "Q": // Quantity
                reference = DisplayType.Quantity;
                length = 11;
                break;
            case "Y": // Yes/No
                reference = DisplayType.YesNo;
                length = 1;
                break;
            case "A": // Amount
                reference = DisplayType.Amount;
                length = 11;
                break;
            case "D": // Date
                reference = DisplayType.Date;
                break;
            case "d": // DateTime
                reference = DisplayType.DateTime;
                break;
            case "T": // Text
                reference = DisplayType.Text;
                length = 2000;
                break;
            case "L": // List
                reference = DisplayType.List;
                MReference ref = new Query(Env.getCtx(), X_AD_Reference.Table_Name,
                        X_AD_Reference.COLUMNNAME_Name + "=?", trxName)
                        .setParameters(columnName)
                        .first();
                if (ref != null) {
                    column.setAD_Reference_Value_ID(ref.get_ID());
                }
                break;
            default:
                // Auto-detect from common patterns
                if (columnName.contains("Qty")) reference = DisplayType.Quantity;
                else if (columnName.startsWith("Is")) reference = DisplayType.YesNo;
                else if (columnName.contains("Amt")) reference = DisplayType.Amount;
                else if (columnName.contains("Date")) reference = DisplayType.Date;
                else if (columnName.endsWith("_ID")) reference = DisplayType.TableDir;
        }

        column.setAD_Reference_ID(reference);
        column.setFieldLength(length);
        column.setIsUpdateable(true);
        column.saveEx(trxName);

        logVerbose("    Added column: " + columnName + " (" + reference + ")");
    }

    private void createTabFields(MTab tab) throws SQLException {
        String sql = "SELECT * FROM AD_Column c " +
                "WHERE AD_Table_ID=? " +
                "AND NOT (Name LIKE 'Created%' OR Name LIKE 'Updated%') " +
                "AND IsActive='Y' " +
                "ORDER BY AD_Column_ID";

        try (PreparedStatement ps = DB.prepareStatement(sql, trxName)) {
            ps.setInt(1, tab.getAD_Table_ID());

            try (ResultSet rs = ps.executeQuery()) {
                int seqNo = 0;
                boolean toggle = false;

                while (rs.next()) {
                    MColumn column = new MColumn(Env.getCtx(), rs, trxName);

                    MField field = new Query(Env.getCtx(), MField.Table_Name,
                            MField.COLUMNNAME_AD_Column_ID + "=? AND " + MField.COLUMNNAME_AD_Tab_ID + "=?", trxName)
                            .setParameters(column.get_ID(), tab.get_ID())
                            .first();

                    if (field == null) {
                        field = new MField(tab);
                        field.setColumn(column);

                        String columnName = column.getColumnName();

                        if (column.isKey()) {
                            field.setIsDisplayed(false);
                            field.setIsDisplayedGrid(false);
                        }

                        if ("AD_Client_ID".equals(columnName)) {
                            seqNo = 3;
                            field.setIsDisplayedGrid(false);
                        } else if ("AD_Org_ID".equals(columnName)) {
                            seqNo = 6;
                            field.setIsSameLine(true);
                            field.setIsDisplayedGrid(false);
                            field.setXPosition(3);
                        } else if ("IsActive".equals(columnName)) {
                            field.setIsSameLine(true);
                            field.setIsDisplayedGrid(false);
                        } else {
                            seqNo += 10;
                            if (toggle) field.setXPosition(3);
                            toggle = !toggle;
                        }

                        field.setIsCentrallyMaintained(false);
                        field.setName(addSpacesToCamelCase(column.getName()));
                        field.setSeqNo(seqNo);

                        if (field.getAD_Client_ID() != 0) {
                            field.set_ValueOfColumn("AD_Client_ID", 0);
                        }

                        field.saveEx(trxName);
                        fieldsCreated++;
                    }
                }
            }
        }
    }

    private void processDataSheet(HSSFSheet sheet) {
        HSSFDataFormatter formatter = new HSSFDataFormatter();
        String tableName = sheet.getSheetName();

        MTable table = new Query(Env.getCtx(), MTable.Table_Name,
                MTable.COLUMNNAME_TableName + "=?", trxName)
                .setParameters(tableName)
                .first();

        if (table == null) {
            log("  WARNING: Table " + tableName + " not found, skipping data import");
            return;
        }

        // Get column mappings from header row
        HSSFRow headerRow = sheet.getRow(0);
        if (headerRow == null) return;

        List<MColumn> columns = new ArrayList<>();
        for (int col = 0; col < headerRow.getLastCellNum(); col++) {
            HSSFCell cell = headerRow.getCell(col);
            if (cell == null) break;

            String colName = getCellString(cell);
            MColumn column = new Query(Env.getCtx(), MColumn.Table_Name,
                    MColumn.COLUMNNAME_Name + "=? AND " + MColumn.COLUMNNAME_AD_Table_ID + "=?", trxName)
                    .setParameters(colName, table.get_ID())
                    .first();

            if (column == null) {
                log("  WARNING: Column " + colName + " not found in table " + tableName);
            }
            columns.add(column);
        }

        // Import data rows
        int imported = 0;
        for (int row = 1; row <= sheet.getLastRowNum(); row++) {
            HSSFRow dataRow = sheet.getRow(row);
            if (dataRow == null) continue;

            // Build INSERT SQL
            StringBuilder colList = new StringBuilder();
            StringBuilder valList = new StringBuilder();

            for (int col = 0; col < columns.size(); col++) {
                MColumn column = columns.get(col);
                if (column == null) continue;

                HSSFCell cell = dataRow.getCell(col);
                String value = cell != null ? formatter.formatCellValue(cell) : "";

                if (colList.length() > 0) {
                    colList.append(",");
                    valList.append(",");
                }
                colList.append(column.getColumnName());

                if (column.getAD_Reference_ID() == DisplayType.String ||
                        column.getAD_Reference_ID() == DisplayType.Text) {
                    valList.append("'").append(value.replace("'", "''")).append("'");
                } else if (value.isEmpty()) {
                    valList.append("NULL");
                } else {
                    valList.append(value);
                }
            }

            if (colList.length() > 0) {
                // Add standard columns
                String sql = String.format(
                        "INSERT INTO %s (AD_Client_ID, AD_Org_ID, IsActive, Created, CreatedBy, Updated, UpdatedBy, %s_ID, %s) " +
                                "VALUES (0, 0, 'Y', NOW(), 100, NOW(), 100, %d, %s)",
                        table.getTableName(),
                        table.getTableName(),
                        colList,
                        1000000 + row,
                        valList
                );

                try {
                    DB.executeUpdateEx(sql, trxName);
                    imported++;
                } catch (Exception e) {
                    log("  WARNING: Failed to import row " + row + ": " + e.getMessage());
                }
            }
        }

        log("  Imported " + imported + " records into " + tableName);
    }

    private int synchronizeTables() throws Exception {
        int tablesSynced = 0;

        for (MTable table : tableset) {
            if (table == null) continue;

            List<MColumn> columns = new Query(Env.getCtx(), MColumn.Table_Name,
                    MColumn.COLUMNNAME_AD_Table_ID + "=?", trxName)
                    .setParameters(table.get_ID())
                    .list();

            for (MColumn column : columns) {
                try (Connection conn = DB.getConnection()) {
                    DatabaseMetaData md = conn.getMetaData();
                    String catalog = DB.getDatabase().getCatalog();
                    String schema = DB.getDatabase().getSchema();
                    String tableName = table.getTableName();

                    if (md.storesUpperCaseIdentifiers()) {
                        tableName = tableName.toUpperCase();
                    } else if (md.storesLowerCaseIdentifiers()) {
                        tableName = tableName.toLowerCase();
                    }

                    ResultSet rs = md.getColumns(catalog, schema, tableName, null);
                    int noColumns = 0;
                    String sql = null;

                    while (rs.next()) {
                        noColumns++;
                        String columnName = rs.getString("COLUMN_NAME");
                        if (!columnName.equalsIgnoreCase(column.getColumnName())) continue;

                        boolean notNull = DatabaseMetaData.columnNoNulls == rs.getInt("NULLABLE");
                        sql = column.getSQLModify(table, column.isMandatory() != notNull);
                        break;
                    }
                    rs.close();

                    boolean isNoTable = noColumns == 0;

                    if (isNoTable) {
                        sql = table.getSQLCreate();
                    } else if (sql == null) {
                        sql = column.getSQLAdd(table);
                    }

                    // Execute SQL
                    if (sql != null && !sql.isEmpty()) {
                        if (sql.contains(DB.SQLSTATEMENT_SEPARATOR)) {
                            String[] statements = sql.split(DB.SQLSTATEMENT_SEPARATOR);
                            for (String stmt : statements) {
                                DB.executeUpdateEx(stmt, null);
                            }
                        } else {
                            DB.executeUpdate(sql, false, null);
                        }
                    }

                    if (isNoTable) break; // Table created, no need to process more columns
                }
            }
            tablesSynced++;
        }

        return tablesSynced;
    }

    // Package Export Detail Type constants (for compatibility with all iDempiere versions)
    private static final String PKG_TYPE_TABLE = "T";
    private static final String PKG_TYPE_WINDOW = "W";
    private static final String PKG_TYPE_PROCESS = "P";
    private static final String PKG_TYPE_MENU = "M";

    /**
     * Create AD_Package_Exp records for future PackOut
     */
    public void createPackageExpRecords() {
        MPackageExp packageExp = new Query(Env.getCtx(), MPackageExp.Table_Name,
                MPackageExp.COLUMNNAME_Name + "=?", trxName)
                .setParameters(bundleName)
                .first();

        if (packageExp == null) {
            packageExp = new MPackageExp(Env.getCtx(), 0, trxName);
            packageExp.setName(bundleName);
            packageExp.setPK_Version(bundleVersion);
            packageExp.setVersion(bundleVersion);
            packageExp.setDescription("Generated by Ninja from Excel");
            // Set EntityType via column value for compatibility
            packageExp.set_ValueOfColumn("EntityType", entityType);
            packageExp.saveEx(trxName);
        }

        int line = 10;

        // Add tables
        for (MTable table : tableset) {
            if (table == null) continue;
            createPackageExpDetail(packageExp, PKG_TYPE_TABLE,
                    table.getAD_Table_ID(), line);
            line += 10;
        }

        // Add windows
        for (MWindow window : windowset) {
            if (window == null) continue;
            createPackageExpDetail(packageExp, PKG_TYPE_WINDOW,
                    window.getAD_Window_ID(), line);
            line += 10;
        }

        // Add processes
        for (MProcess process : processset) {
            if (process == null) continue;
            createPackageExpDetail(packageExp, PKG_TYPE_PROCESS,
                    process.getAD_Process_ID(), line);
            line += 10;
        }

        // Add menus
        for (MMenu menu : menuset) {
            createPackageExpDetail(packageExp, PKG_TYPE_MENU,
                    menu.getAD_Menu_ID(), line);
            line += 10;
        }

        log("  Created AD_Package_Exp with " + (line / 10 - 1) + " detail records");
    }

    private void createPackageExpDetail(MPackageExp exp, String type, int recordId, int line) {
        MPackageExpDetail detail = new MPackageExpDetail(Env.getCtx(), 0, trxName);
        detail.setAD_Package_Exp_ID(exp.getAD_Package_Exp_ID());
        detail.setType(type);
        detail.setLine(line);

        switch (type) {
            case PKG_TYPE_TABLE:
                detail.setAD_Table_ID(recordId);
                break;
            case PKG_TYPE_WINDOW:
                detail.setAD_Window_ID(recordId);
                break;
            case PKG_TYPE_PROCESS:
                detail.setAD_Process_ID(recordId);
                break;
            case PKG_TYPE_MENU:
                detail.setAD_Menu_ID(recordId);
                break;
        }

        // Set EntityType via column value for compatibility
        detail.set_ValueOfColumn("EntityType", entityType);
        detail.saveEx(trxName);
    }

    private void logSummary() {
        log("\n  Summary:");
        log("    Tables: " + tablesCreated);
        log("    Columns: " + columnsCreated);
        log("    Windows: " + windowsCreated);
        log("    Processes: " + processesCreated);
        log("    Menus: " + menusCreated);
        log("    References: " + referencesCreated);
        log("    Fields: " + fieldsCreated);
    }

    // Utility methods
    private String getCellString(HSSFCell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue();
        if (cell.getCellType() == CellType.NUMERIC) return String.valueOf((int) cell.getNumericCellValue());
        if (cell.getCellType() == CellType.BOOLEAN) return String.valueOf(cell.getBooleanCellValue());
        return "";
    }

    private String addSpacesToCamelCase(String s) {
        String[] parts = s.split("(?=\\p{Lu})");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;
            if (part.length() == 1) {
                result.append(part);
            } else {
                result.append(part).append(" ");
            }
        }
        return result.toString().trim();
    }

    private void log(String message) {
        System.out.println("[PROCESSOR] " + message);
    }

    private void logVerbose(String message) {
        if (verbose) {
            System.out.println("[PROCESSOR] " + message);
        }
    }

    // Getters for statistics
    public int getTablesCreated() { return tablesCreated; }
    public int getColumnsCreated() { return columnsCreated; }
    public int getWindowsCreated() { return windowsCreated; }
    public int getProcessesCreated() { return processesCreated; }
    public int getMenusCreated() { return menusCreated; }
    public int getReferencesCreated() { return referencesCreated; }

    // Getters for generators
    public String getBundleName() { return bundleName; }
    public String getBundleVersion() { return bundleVersion; }
    public String getPackagePrefix() { return packagePrefix; }
    public String getEntityType() { return entityType; }
    public String getMenuPrefix() { return menuPrefix; }
    public List<MTable> getTables() { return tableset; }
    public List<MWindow> getWindows() { return windowset; }
    public List<MProcess> getProcesses() { return processset; }
    public List<MMenu> getMenus() { return menuset; }
}
