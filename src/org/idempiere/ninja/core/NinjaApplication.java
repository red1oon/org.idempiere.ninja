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
package org.idempiere.ninja.core;

import java.io.File;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;

import org.adempiere.util.ServerContext;
import org.compiere.Adempiere;
import org.compiere.model.SystemProperties;
import org.compiere.util.CLogMgt;
import org.compiere.util.Env;
import org.compiere.util.Ini;
import org.compiere.util.Trx;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.idempiere.ninja.generator.PluginGenerator;
import org.idempiere.ninja.generator.TwoPackGenerator;
import org.idempiere.ninja.validator.ExcelValidator;

/**
 * Ninja Plugin Generator - Main Application Entry Point
 *
 * Creates iDempiere plugins from Excel definitions:
 * - Injects AD models into database
 * - Generates 2Pack.zip for distribution
 * - Creates AD_Package_Exp for future modifications
 * - Generates complete OSGI plugin structure
 *
 * Usage: RUN_Ninja.sh <ExcelFile> [options]
 *
 * Options:
 *   -a    Inject AD models into database only
 *   -b    Generate 2Pack.zip only
 *   -c    Create AD_Package_Exp records only
 *   -d    Generate OSGI plugin structure only
 *   -o    Output directory (default: same as Excel file)
 *   -v    Verbose logging
 *
 * Default (no options): Execute all modes (-abcd)
 *
 * @author iDempiere Community
 */
public class NinjaApplication implements IApplication {

    // Execution modes
    private boolean modeInjectDB = false;      // -a (requires DB)
    private boolean modeGenerate2Pack = false; // -b (standalone OK)
    private boolean modeCreatePackExp = false; // -c (requires DB)
    private boolean modeGeneratePlugin = false; // -d (standalone OK)
    private boolean verbose = false;
    private boolean requiresDB = false;        // true if -a or -c selected

    private String excelFilePath = null;
    private String outputDirectory = null;
    private String trxName = null;

    // Statistics
    private int tablesCreated = 0;
    private int columnsCreated = 0;
    private int windowsCreated = 0;
    private int menusCreated = 0;
    private int referencesCreated = 0;

    @Override
    public Object start(IApplicationContext context) throws Exception {
        printBanner();

        // Parse command line arguments
        Map<?, ?> args = context.getArguments();
        String[] cmdArgs = (String[]) args.get("application.args");

        if (!parseArguments(cmdArgs)) {
            printUsage();
            return Integer.valueOf(1);
        }

        // Validate Excel file exists
        File excelFile = new File(excelFilePath);
        if (!excelFile.exists()) {
            logError("Excel file not found: " + excelFilePath);
            return Integer.valueOf(1);
        }

        // Set default output directory if not specified
        if (outputDirectory == null) {
            outputDirectory = excelFile.getParent();
        }

        try {
            // Initialize iDempiere
            log("Initializing iDempiere...");
            initializeADempiere();

            // Validate Excel structure
            log("Validating Excel file: " + excelFilePath);
            ExcelValidator validator = new ExcelValidator(excelFilePath, verbose);
            if (!validator.validate()) {
                logError("Excel validation failed. See errors above.");
                return Integer.valueOf(1);
            }
            log("Excel validation passed!");

            // Create transaction
            trxName = Trx.createTrxName("Ninja");
            log("Transaction: " + trxName);

            // Execute requested modes
            NinjaProcessor processor = new NinjaProcessor(excelFilePath, trxName, verbose);

            if (modeInjectDB) {
                log("\n=== Mode A: Injecting AD Models into Database ===");
                processor.injectIntoDatabase();
                tablesCreated = processor.getTablesCreated();
                columnsCreated = processor.getColumnsCreated();
                windowsCreated = processor.getWindowsCreated();
                menusCreated = processor.getMenusCreated();
                referencesCreated = processor.getReferencesCreated();
            }

            String twoPackPath = null;
            PluginGenerator pluginGen = null;

            // Generate plugin structure first (if requested)
            if (modeGeneratePlugin) {
                log("\n=== Mode D: Generating OSGI Plugin Structure ===");
                pluginGen = new PluginGenerator(processor, outputDirectory, verbose);
                String pluginPath = pluginGen.generate();
                log("Plugin generated: " + pluginPath);
            }

            // Generate 2Pack (into plugin META-INF if plugin was generated)
            if (modeGenerate2Pack) {
                log("\n=== Mode B: Generating 2Pack.zip ===");
                String twoPackDir = (pluginGen != null) ?
                    outputDirectory + "/" + processor.getBundleName() + "/META-INF" :
                    outputDirectory;
                TwoPackGenerator twoPackGen = new TwoPackGenerator(processor, twoPackDir, verbose);
                twoPackPath = twoPackGen.generate();
                log("2Pack created: " + twoPackPath);
            }

            if (modeCreatePackExp) {
                log("\n=== Mode C: Creating AD_Package_Exp Records ===");
                processor.createPackageExpRecords();
                log("AD_Package_Exp records created for future PackOut");
            }

            // Commit transaction
            Trx trx = Trx.get(trxName, false);
            trx.commit();
            trx.close();

            // Print summary
            printSummary();

            return IApplication.EXIT_OK;

        } catch (Exception e) {
            logError("Ninja failed: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            // Rollback on error
            if (trxName != null) {
                Trx trx = Trx.get(trxName, false);
                if (trx != null) {
                    trx.rollback();
                    trx.close();
                }
            }
            return Integer.valueOf(1);
        }
    }

    private boolean parseArguments(String[] args) {
        if (args == null || args.length == 0) {
            logError("No arguments provided");
            return false;
        }

        boolean modesSpecified = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if (arg.startsWith("-")) {
                // Parse options
                for (char c : arg.substring(1).toCharArray()) {
                    switch (c) {
                        case 'a':
                            modeInjectDB = true;
                            modesSpecified = true;
                            break;
                        case 'b':
                            modeGenerate2Pack = true;
                            modesSpecified = true;
                            break;
                        case 'c':
                            modeCreatePackExp = true;
                            modesSpecified = true;
                            break;
                        case 'd':
                            modeGeneratePlugin = true;
                            modesSpecified = true;
                            break;
                        case 'v':
                            verbose = true;
                            break;
                        case 'o':
                            // Next argument is output directory
                            if (i + 1 < args.length) {
                                outputDirectory = args[++i];
                            }
                            break;
                        default:
                            logError("Unknown option: -" + c);
                            return false;
                    }
                }
            } else {
                // Excel file path
                if (excelFilePath == null) {
                    excelFilePath = arg;
                } else {
                    logError("Multiple Excel files specified");
                    return false;
                }
            }
        }

        if (excelFilePath == null) {
            logError("No Excel file specified");
            return false;
        }

        // Default: all modes if none specified
        if (!modesSpecified) {
            modeInjectDB = true;
            modeGenerate2Pack = true;
            modeCreatePackExp = true;
            modeGeneratePlugin = true;
        }

        return true;
    }

    private void initializeADempiere() throws Exception {
        Properties serverContext = new Properties();
        ServerContext.setCurrentInstance(serverContext);

        String propertyFile = Ini.getFileName(false);
        File prop = new File(propertyFile);
        if (!prop.exists()) {
            throw new IllegalStateException(
                "idempiere.properties not found at: " + prop.getAbsolutePath() + "\n" +
                "Make sure you are running from the iDempiere server directory.");
        }

        if (!Adempiere.isStarted()) {
            boolean started = Adempiere.startup(false);
            if (!started) {
                throw new Exception("Could not start iDempiere");
            }
        }

        // Set log level
        String logLevel = SystemProperties.getLogLevel();
        if (logLevel == null) logLevel = verbose ? "FINE" : "INFO";
        switch (logLevel) {
            case "SEVERE":  CLogMgt.setLevel(Level.SEVERE); break;
            case "WARNING": CLogMgt.setLevel(Level.WARNING); break;
            case "INFO":    CLogMgt.setLevel(Level.INFO); break;
            case "CONFIG":  CLogMgt.setLevel(Level.CONFIG); break;
            case "FINE":    CLogMgt.setLevel(Level.FINE); break;
            case "FINER":   CLogMgt.setLevel(Level.FINER); break;
            case "FINEST":  CLogMgt.setLevel(Level.FINEST); break;
            default:        CLogMgt.setLevel(Level.INFO); break;
        }

        Env.setContext(Env.getCtx(), Env.AD_CLIENT_ID, 0);
        Env.setContext(Env.getCtx(), Env.AD_ORG_ID, 0);
    }

    private void printBanner() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║     _   _ _       _                                       ║");
        System.out.println("║    | \\ | (_)     (_)                                      ║");
        System.out.println("║    |  \\| |_ _ __  _  __ _                                 ║");
        System.out.println("║    | . ` | | '_ \\| |/ _` |                                ║");
        System.out.println("║    | |\\  | | | | | | (_| |                                ║");
        System.out.println("║    |_| \\_|_|_| |_| |\\__,_|                                ║");
        System.out.println("║                  _/ |                                     ║");
        System.out.println("║                 |__/                                      ║");
        System.out.println("║                                                           ║");
        System.out.println("║    iDempiere Plugin Generator v1.0                        ║");
        System.out.println("║    Excel -> AD Models -> 2Pack -> OSGI Plugin             ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private void printUsage() {
        System.out.println("Usage: RUN_Ninja.sh <ExcelFile> [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -a    Inject AD models into database");
        System.out.println("  -b    Generate 2Pack.zip for distribution");
        System.out.println("  -c    Create AD_Package_Exp records for future changes");
        System.out.println("  -d    Generate complete OSGI plugin structure");
        System.out.println("  -o    Output directory (default: same as Excel file)");
        System.out.println("  -v    Verbose logging");
        System.out.println();
        System.out.println("Default (no mode options): Execute all modes (-abcd)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  ./RUN_Ninja.sh MyModule.xls           # All modes");
        System.out.println("  ./RUN_Ninja.sh MyModule.xls -a        # DB inject only");
        System.out.println("  ./RUN_Ninja.sh MyModule.xls -bd       # 2Pack + Plugin");
        System.out.println("  ./RUN_Ninja.sh MyModule.xls -o /tmp   # Custom output dir");
        System.out.println();
    }

    private void printSummary() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║                    NINJA COMPLETE                         ║");
        System.out.println("╠═══════════════════════════════════════════════════════════╣");
        if (modeInjectDB) {
            System.out.println("║  Database Injection:                                      ║");
            System.out.printf("║    Tables: %-5d  Columns: %-5d                          ║%n", tablesCreated, columnsCreated);
            System.out.printf("║    Windows: %-4d  Menus: %-5d  References: %-4d          ║%n", windowsCreated, menusCreated, referencesCreated);
        }
        if (modeGenerate2Pack) {
            System.out.println("║  2Pack: Generated                                         ║");
        }
        if (modeCreatePackExp) {
            System.out.println("║  AD_Package_Exp: Created                                  ║");
        }
        if (modeGeneratePlugin) {
            System.out.println("║  OSGI Plugin: Generated                                   ║");
        }
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private void log(String message) {
        System.out.println("[NINJA] " + message);
    }

    private void logError(String message) {
        System.err.println("[NINJA ERROR] " + message);
    }

    @Override
    public void stop() {
    }
}
