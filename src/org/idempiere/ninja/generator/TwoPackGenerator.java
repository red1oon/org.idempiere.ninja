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
package org.idempiere.ninja.generator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.StringWriter;
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

import org.compiere.model.MColumn;
import org.compiere.model.MField;
import org.compiere.model.MMenu;
import org.compiere.model.MTab;
import org.compiere.model.MTable;
import org.compiere.model.MWindow;
import org.compiere.model.Query;
import org.compiere.util.Env;
import org.idempiere.ninja.core.NinjaProcessor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * 2Pack Generator - Creates 2Pack XML directly without PackOut process
 *
 * Generates a complete 2Pack.zip file containing:
 * - PackOut.xml with all AD model definitions
 * - Proper XML structure compatible with iDempiere PackIn
 *
 * This allows creating distributable 2Packs without needing
 * to run the PackOut process through the UI.
 *
 * @author red1 - red1org@gmail.com
 */
public class TwoPackGenerator {

    private NinjaProcessor processor;
    private String outputDirectory;
    private boolean verbose;

    private String bundleName;
    private String bundleVersion;
    private String entityType;

    private Document doc;
    private Element rootElement;

    public TwoPackGenerator(NinjaProcessor processor, String outputDirectory, boolean verbose) {
        this.processor = processor;
        this.outputDirectory = outputDirectory;
        this.verbose = verbose;

        this.bundleName = processor.getBundleName();
        this.bundleVersion = processor.getBundleVersion();
        this.entityType = processor.getEntityType();
    }

    /**
     * Generate 2Pack.zip file
     * @return Path to generated 2Pack file
     */
    public String generate() throws Exception {
        String twoPackPath = outputDirectory + File.separator + "2Pack_" + bundleVersion + ".zip";

        log("Generating 2Pack: " + twoPackPath);

        // Create XML document
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        doc = builder.newDocument();

        // Create root element
        rootElement = doc.createElement("idempiere");
        doc.appendChild(rootElement);

        // Add package header
        addPackageHeader();

        // Add AD elements in correct order (dependencies first)
        addReferences();
        addTables();
        addWindows();
        addMenus();

        // Convert to XML string
        String xmlContent = documentToString(doc);

        // Create ZIP file
        try (FileOutputStream fos = new FileOutputStream(twoPackPath);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            // Add PackOut.xml
            ZipEntry entry = new ZipEntry("PackOut.xml");
            zos.putNextEntry(entry);
            zos.write(xmlContent.getBytes("UTF-8"));
            zos.closeEntry();

            // Add dict/ folder marker (required by some PackIn versions)
            zos.putNextEntry(new ZipEntry("dict/"));
            zos.closeEntry();
        }

        log("  2Pack created: " + twoPackPath);
        return twoPackPath;
    }

    private void addPackageHeader() {
        Element header = doc.createElement("adempiereAD");
        header.setAttribute("Name", bundleName);
        header.setAttribute("Version", bundleVersion);
        header.setAttribute("CompVer", "iDempiere 11+");
        header.setAttribute("DataBase", "All");
        rootElement.appendChild(header);

        logVerbose("  Added package header");
    }

    private void addReferences() {
        // References are created during List sheet processing
        // They're already in the database, we just need to export them

        logVerbose("  Exporting references...");
        // TODO: Export AD_Reference and AD_Ref_List records
    }

    private void addTables() throws Exception {
        List<MTable> tables = processor.getTables();

        for (MTable table : tables) {
            if (table == null) continue;

            Element tableEl = doc.createElement("AD_Table");
            tableEl.setAttribute("AD_Table_UU", getOrCreateUU(table));

            addAttribute(tableEl, "AccessLevel", table.getAccessLevel());
            addAttribute(tableEl, "EntityType", table.getEntityType());
            addAttribute(tableEl, "IsActive", table.isActive() ? "Y" : "N");
            addAttribute(tableEl, "Name", table.getName());
            addAttribute(tableEl, "TableName", table.getTableName());
            addAttribute(tableEl, "IsDeleteable", "Y");
            addAttribute(tableEl, "IsChangeLog", "N");
            addAttribute(tableEl, "ReplicationType", "L");

            rootElement.appendChild(tableEl);

            // Add columns
            addColumns(table);

            logVerbose("  Added table: " + table.getTableName());
        }
    }

    private void addColumns(MTable table) throws Exception {
        List<MColumn> columns = new Query(Env.getCtx(), MColumn.Table_Name,
                MColumn.COLUMNNAME_AD_Table_ID + "=?", null)
                .setParameters(table.getAD_Table_ID())
                .setOrderBy(MColumn.COLUMNNAME_SeqNo)
                .list();

        for (MColumn column : columns) {
            Element colEl = doc.createElement("AD_Column");
            colEl.setAttribute("AD_Column_UU", getOrCreateUU(column));

            addAttribute(colEl, "AD_Table_ID", table.getTableName());
            addAttribute(colEl, "ColumnName", column.getColumnName());
            addAttribute(colEl, "Name", column.getName());
            addAttribute(colEl, "AD_Reference_ID", String.valueOf(column.getAD_Reference_ID()));
            addAttribute(colEl, "FieldLength", String.valueOf(column.getFieldLength()));
            addAttribute(colEl, "IsMandatory", column.isMandatory() ? "Y" : "N");
            addAttribute(colEl, "IsKey", column.isKey() ? "Y" : "N");
            addAttribute(colEl, "IsParent", column.isParent() ? "Y" : "N");
            addAttribute(colEl, "IsUpdateable", column.isUpdateable() ? "Y" : "N");
            addAttribute(colEl, "EntityType", column.getEntityType());
            addAttribute(colEl, "IsActive", column.isActive() ? "Y" : "N");

            if (column.getDefaultValue() != null) {
                addAttribute(colEl, "DefaultValue", column.getDefaultValue());
            }
            if (column.getAD_Reference_Value_ID() > 0) {
                addAttribute(colEl, "AD_Reference_Value_ID",
                        String.valueOf(column.getAD_Reference_Value_ID()));
            }

            rootElement.appendChild(colEl);
        }
    }

    private void addWindows() throws Exception {
        List<MWindow> windows = processor.getWindows();

        for (MWindow window : windows) {
            if (window == null) continue;

            Element winEl = doc.createElement("AD_Window");
            winEl.setAttribute("AD_Window_UU", getOrCreateUU(window));

            addAttribute(winEl, "Name", window.getName());
            addAttribute(winEl, "WindowType", window.getWindowType());
            addAttribute(winEl, "EntityType", window.getEntityType());
            addAttribute(winEl, "IsActive", window.isActive() ? "Y" : "N");
            addAttribute(winEl, "IsSOTrx", "Y");

            rootElement.appendChild(winEl);

            // Add tabs
            addTabs(window);

            logVerbose("  Added window: " + window.getName());
        }
    }

    private void addTabs(MWindow window) throws Exception {
        List<MTab> tabs = new Query(Env.getCtx(), MTab.Table_Name,
                MTab.COLUMNNAME_AD_Window_ID + "=?", null)
                .setParameters(window.getAD_Window_ID())
                .setOrderBy(MTab.COLUMNNAME_SeqNo)
                .list();

        for (MTab tab : tabs) {
            Element tabEl = doc.createElement("AD_Tab");
            tabEl.setAttribute("AD_Tab_UU", getOrCreateUU(tab));

            addAttribute(tabEl, "AD_Window_ID", window.getName());
            addAttribute(tabEl, "AD_Table_ID", getTableName(tab.getAD_Table_ID()));
            addAttribute(tabEl, "Name", tab.getName());
            addAttribute(tabEl, "SeqNo", String.valueOf(tab.getSeqNo()));
            addAttribute(tabEl, "TabLevel", String.valueOf(tab.getTabLevel()));
            addAttribute(tabEl, "EntityType", tab.getEntityType());
            addAttribute(tabEl, "IsActive", tab.isActive() ? "Y" : "N");
            addAttribute(tabEl, "IsSingleRow", tab.isSingleRow() ? "Y" : "N");
            addAttribute(tabEl, "IsReadOnly", "N");

            rootElement.appendChild(tabEl);

            // Add fields
            addFields(tab);
        }
    }

    private void addFields(MTab tab) throws Exception {
        List<MField> fields = new Query(Env.getCtx(), MField.Table_Name,
                MField.COLUMNNAME_AD_Tab_ID + "=?", null)
                .setParameters(tab.getAD_Tab_ID())
                .setOrderBy(MField.COLUMNNAME_SeqNo)
                .list();

        for (MField field : fields) {
            Element fieldEl = doc.createElement("AD_Field");
            fieldEl.setAttribute("AD_Field_UU", getOrCreateUU(field));

            addAttribute(fieldEl, "AD_Tab_ID", tab.getName());
            addAttribute(fieldEl, "AD_Column_ID", getColumnName(field.getAD_Column_ID()));
            addAttribute(fieldEl, "Name", field.getName());
            addAttribute(fieldEl, "SeqNo", String.valueOf(field.getSeqNo()));
            addAttribute(fieldEl, "IsDisplayed", field.isDisplayed() ? "Y" : "N");
            addAttribute(fieldEl, "IsSameLine", field.isSameLine() ? "Y" : "N");
            addAttribute(fieldEl, "EntityType", field.getEntityType());
            addAttribute(fieldEl, "IsActive", field.isActive() ? "Y" : "N");

            if (field.getXPosition() > 0) {
                addAttribute(fieldEl, "XPosition", String.valueOf(field.getXPosition()));
            }

            rootElement.appendChild(fieldEl);
        }
    }

    private void addMenus() throws Exception {
        List<MMenu> menus = processor.getMenus();

        for (MMenu menu : menus) {
            if (menu == null) continue;

            Element menuEl = doc.createElement("AD_Menu");
            menuEl.setAttribute("AD_Menu_UU", getOrCreateUU(menu));

            addAttribute(menuEl, "Name", menu.getName());
            addAttribute(menuEl, "Action", menu.getAction() != null ? menu.getAction() : "");
            addAttribute(menuEl, "IsSummary", menu.isSummary() ? "Y" : "N");
            addAttribute(menuEl, "EntityType", menu.getEntityType());
            addAttribute(menuEl, "IsActive", menu.isActive() ? "Y" : "N");
            addAttribute(menuEl, "IsSOTrx", "Y");
            addAttribute(menuEl, "IsReadOnly", "N");

            if (menu.getAD_Window_ID() > 0) {
                addAttribute(menuEl, "AD_Window_ID", getWindowName(menu.getAD_Window_ID()));
            }

            rootElement.appendChild(menuEl);

            logVerbose("  Added menu: " + menu.getName());
        }
    }

    // Helper methods

    private void addAttribute(Element element, String name, String value) {
        if (value != null && !value.isEmpty()) {
            element.setAttribute(name, value);
        }
    }

    private String getOrCreateUU(Object po) {
        // Try to get existing UU, or generate new one
        try {
            java.lang.reflect.Method getUU = po.getClass().getMethod("get_ValueAsString", String.class);
            String uu = (String) getUU.invoke(po, po.getClass().getSimpleName().substring(1) + "_UU");
            if (uu != null && !uu.isEmpty()) {
                return uu;
            }
        } catch (Exception e) {
            // Ignore
        }
        return UUID.randomUUID().toString();
    }

    private String getTableName(int tableId) {
        MTable table = MTable.get(Env.getCtx(), tableId);
        return table != null ? table.getTableName() : String.valueOf(tableId);
    }

    private String getColumnName(int columnId) {
        MColumn column = MColumn.get(Env.getCtx(), columnId);
        return column != null ? column.getColumnName() : String.valueOf(columnId);
    }

    private String getWindowName(int windowId) {
        MWindow window = new MWindow(Env.getCtx(), windowId, null);
        return window != null ? window.getName() : String.valueOf(windowId);
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

    private void log(String message) {
        System.out.println("[2PACK-GEN] " + message);
    }

    private void logVerbose(String message) {
        if (verbose) {
            System.out.println("[2PACK-GEN] " + message);
        }
    }
}
