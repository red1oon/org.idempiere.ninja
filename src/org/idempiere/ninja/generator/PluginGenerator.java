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
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

import org.idempiere.ninja.core.NinjaProcessor;

/**
 * Plugin Generator - Creates complete OSGI plugin structure
 *
 * Generates all boilerplate files needed for an iDempiere plugin:
 * - META-INF/MANIFEST.MF
 * - META-INF/2Pack_x.x.x.zip (if 2Pack generated)
 * - plugin.xml
 * - build.properties
 * - .classpath
 * - .project
 * - src/ package structure
 * - README.md
 *
 * The generated plugin can be directly imported into Eclipse.
 *
 * @author red1 - red1org@gmail.com
 */
public class PluginGenerator {

    private NinjaProcessor processor;
    private String outputDirectory;
    private boolean verbose;

    private String bundleName;
    private String bundleVersion;
    private String packagePrefix;
    private String pluginDir;

    public PluginGenerator(NinjaProcessor processor, String outputDirectory, boolean verbose) {
        this.processor = processor;
        this.outputDirectory = outputDirectory;
        this.verbose = verbose;

        this.bundleName = processor.getBundleName();
        this.bundleVersion = processor.getBundleVersion();
        this.packagePrefix = processor.getPackagePrefix();
    }

    /**
     * Generate complete OSGI plugin structure
     * @return Path to generated plugin directory
     */
    public String generate() throws IOException {
        // Create plugin directory
        pluginDir = outputDirectory + File.separator + bundleName;
        new File(pluginDir).mkdirs();
        new File(pluginDir + "/META-INF").mkdirs();
        new File(pluginDir + "/OSGI-INF").mkdirs();
        new File(pluginDir + "/src/" + packagePrefix.replace(".", "/")).mkdirs();

        log("Generating plugin: " + bundleName);
        log("  Output: " + pluginDir);

        // Generate all files
        generateManifest();
        generatePluginXml();
        generateBuildProperties();
        generateClasspath();
        generateProject();
        generateActivator();
        generateReadme();

        log("  Plugin structure generated successfully!");

        return pluginDir;
    }

    private void generateManifest() throws IOException {
        String manifestPath = pluginDir + "/META-INF/MANIFEST.MF";
        log("  Creating: META-INF/MANIFEST.MF");

        try (PrintWriter pw = new PrintWriter(new FileWriter(manifestPath))) {
            pw.println("Manifest-Version: 1.0");
            pw.println("Bundle-ManifestVersion: 2");
            pw.println("Bundle-Name: " + formatBundleName(bundleName));
            pw.println("Bundle-SymbolicName: " + bundleName + ";singleton:=true");
            pw.println("Bundle-Version: " + bundleVersion + ".qualifier");
            pw.println("Bundle-Activator: org.adempiere.plugin.utils.Incremental2PackActivator");
            pw.println("Bundle-Vendor: iDempiere Community");
            pw.println("Bundle-RequiredExecutionEnvironment: JavaSE-17");
            pw.println("Require-Bundle: org.adempiere.base,");
            pw.println(" org.adempiere.plugin.utils");
            pw.println("Export-Package: " + packagePrefix);
            pw.println("Bundle-ActivationPolicy: lazy");
            pw.println("Automatic-Module-Name: " + bundleName);
        }
    }

    private void generatePluginXml() throws IOException {
        String pluginXmlPath = pluginDir + "/plugin.xml";
        log("  Creating: plugin.xml");

        try (PrintWriter pw = new PrintWriter(new FileWriter(pluginXmlPath))) {
            pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            pw.println("<?eclipse version=\"3.4\"?>");
            pw.println("<plugin>");
            pw.println("   <!-- Extension points will be added here as needed -->");
            pw.println("   <!-- Example: Process, ModelValidator, Callout, etc. -->");
            pw.println("</plugin>");
        }
    }

    private void generateBuildProperties() throws IOException {
        String buildPropsPath = pluginDir + "/build.properties";
        log("  Creating: build.properties");

        try (PrintWriter pw = new PrintWriter(new FileWriter(buildPropsPath))) {
            pw.println("source.. = src/");
            pw.println("output.. = bin/");
            pw.println("bin.includes = META-INF/,\\");
            pw.println("               .,\\");
            pw.println("               plugin.xml");
        }
    }

    private void generateClasspath() throws IOException {
        String classpathPath = pluginDir + "/.classpath";
        log("  Creating: .classpath");

        try (PrintWriter pw = new PrintWriter(new FileWriter(classpathPath))) {
            pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            pw.println("<classpath>");
            pw.println("\t<classpathentry kind=\"con\" path=\"org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-17\"/>");
            pw.println("\t<classpathentry kind=\"con\" path=\"org.eclipse.pde.core.requiredPlugins\"/>");
            pw.println("\t<classpathentry kind=\"src\" path=\"src\"/>");
            pw.println("\t<classpathentry kind=\"output\" path=\"bin\"/>");
            pw.println("</classpath>");
        }
    }

    private void generateProject() throws IOException {
        String projectPath = pluginDir + "/.project";
        log("  Creating: .project");

        try (PrintWriter pw = new PrintWriter(new FileWriter(projectPath))) {
            pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            pw.println("<projectDescription>");
            pw.println("\t<name>" + bundleName + "</name>");
            pw.println("\t<comment>Generated by iDempiere Ninja</comment>");
            pw.println("\t<projects>");
            pw.println("\t</projects>");
            pw.println("\t<buildSpec>");
            pw.println("\t\t<buildCommand>");
            pw.println("\t\t\t<name>org.eclipse.jdt.core.javabuilder</name>");
            pw.println("\t\t\t<arguments>");
            pw.println("\t\t\t</arguments>");
            pw.println("\t\t</buildCommand>");
            pw.println("\t\t<buildCommand>");
            pw.println("\t\t\t<name>org.eclipse.pde.ManifestBuilder</name>");
            pw.println("\t\t\t<arguments>");
            pw.println("\t\t\t</arguments>");
            pw.println("\t\t</buildCommand>");
            pw.println("\t\t<buildCommand>");
            pw.println("\t\t\t<name>org.eclipse.pde.SchemaBuilder</name>");
            pw.println("\t\t\t<arguments>");
            pw.println("\t\t\t</arguments>");
            pw.println("\t\t</buildCommand>");
            pw.println("\t</buildSpec>");
            pw.println("\t<natures>");
            pw.println("\t\t<nature>org.eclipse.pde.PluginNature</nature>");
            pw.println("\t\t<nature>org.eclipse.jdt.core.javanature</nature>");
            pw.println("\t</natures>");
            pw.println("</projectDescription>");
        }
    }

    private void generateActivator() throws IOException {
        String packageDir = pluginDir + "/src/" + packagePrefix.replace(".", "/");
        String activatorPath = packageDir + "/Activator.java";
        log("  Creating: Activator.java");

        try (PrintWriter pw = new PrintWriter(new FileWriter(activatorPath))) {
            pw.println("/******************************************************************************");
            pw.println(" * Generated by iDempiere Ninja Plugin Generator                              *");
            pw.println(" * @author red1 - red1org@gmail.com                                           *");
            pw.println(" *****************************************************************************/");
            pw.println("package " + packagePrefix + ";");
            pw.println();
            pw.println("import org.adempiere.plugin.utils.Incremental2PackActivator;");
            pw.println();
            pw.println("/**");
            pw.println(" * Plugin Activator");
            pw.println(" *");
            pw.println(" * Uses Incremental2PackActivator to automatically import 2Pack files");
            pw.println(" * from META-INF/ on bundle start.");
            pw.println(" *");
            pw.println(" * 2Pack files should be named: 2Pack_x.x.x.zip");
            pw.println(" * e.g., 2Pack_1.0.0.zip, 2Pack_1.0.1.zip");
            pw.println(" */");
            pw.println("public class Activator extends Incremental2PackActivator {");
            pw.println();
            pw.println("\t@Override");
            pw.println("\tprotected void afterPackIn() {");
            pw.println("\t\t// Called after 2Pack import completes");
            pw.println("\t\t// Add any post-import initialization here");
            pw.println("\t}");
            pw.println("}");
        }
    }

    private void generateReadme() throws IOException {
        String readmePath = pluginDir + "/README.md";
        log("  Creating: README.md");

        try (PrintWriter pw = new PrintWriter(new FileWriter(readmePath))) {
            pw.println("# " + formatBundleName(bundleName));
            pw.println();
            pw.println("Generated by **iDempiere Ninja Plugin Generator**");
            pw.println();
            pw.println("## Installation");
            pw.println();
            pw.println("### From Eclipse IDE:");
            pw.println("1. File -> Import -> Existing Projects into Workspace");
            pw.println("2. Select this directory");
            pw.println("3. Add to your iDempiere target platform");
            pw.println();
            pw.println("### From Server:");
            pw.println("1. Copy the plugin folder to `$IDEMPIERE_HOME/plugins/`");
            pw.println("2. Restart iDempiere server");
            pw.println();
            pw.println("## Structure");
            pw.println();
            pw.println("```");
            pw.println(bundleName + "/");
            pw.println("├── META-INF/");
            pw.println("│   ├── MANIFEST.MF          # OSGI bundle manifest");
            pw.println("│   └── 2Pack_" + bundleVersion + ".zip    # AD model definitions");
            pw.println("├── src/");
            pw.println("│   └── " + packagePrefix.replace(".", "/") + "/");
            pw.println("│       └── Activator.java   # Bundle activator");
            pw.println("├── plugin.xml               # Eclipse extension points");
            pw.println("├── build.properties");
            pw.println("├── .classpath");
            pw.println("├── .project");
            pw.println("└── README.md");
            pw.println("```");
            pw.println();
            pw.println("## 2Pack Auto-Import");
            pw.println();
            pw.println("This plugin uses `Incremental2PackActivator` which automatically imports");
            pw.println("2Pack files from `META-INF/` when the bundle starts.");
            pw.println();
            pw.println("To add updates:");
            pw.println("1. Create a new 2Pack with incremented version (e.g., `2Pack_1.0.1.zip`)");
            pw.println("2. Place it in `META-INF/`");
            pw.println("3. Restart iDempiere - new changes will be imported automatically");
            pw.println();
            pw.println("## Customization");
            pw.println();
            pw.println("### Adding a Process");
            pw.println("1. Create a class extending `SvrProcess` in `src/`");
            pw.println("2. Register in `plugin.xml`:");
            pw.println("```xml");
            pw.println("<extension point=\"org.adempiere.base.Process\">");
            pw.println("   <process class=\"" + packagePrefix + ".YourProcess\"/>");
            pw.println("</extension>");
            pw.println("```");
            pw.println();
            pw.println("### Adding a Model Validator");
            pw.println("```xml");
            pw.println("<extension point=\"org.adempiere.base.ModelValidator\">");
            pw.println("   <validator class=\"" + packagePrefix + ".YourValidator\"/>");
            pw.println("</extension>");
            pw.println("```");
            pw.println();
            pw.println("### Adding a Callout");
            pw.println("```xml");
            pw.println("<extension point=\"org.adempiere.base.IColumnCallout\">");
            pw.println("   <callout class=\"" + packagePrefix + ".YourCallout\"");
            pw.println("            tablename=\"YourTable\" columnname=\"YourColumn\"/>");
            pw.println("</extension>");
            pw.println("```");
            pw.println();
            pw.println("---");
            pw.println("*Generated by Ninja - https://github.com/idempiere/ninja*");
        }
    }

    /**
     * Copy 2Pack file to plugin META-INF
     */
    public void add2Pack(String twoPackPath) throws IOException {
        File source = new File(twoPackPath);
        File dest = new File(pluginDir + "/META-INF/" + source.getName());

        java.nio.file.Files.copy(source.toPath(), dest.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        log("  Added 2Pack: " + dest.getName());

        // Update build.properties to include 2Pack
        String buildPropsPath = pluginDir + "/build.properties";
        try (PrintWriter pw = new PrintWriter(new FileWriter(buildPropsPath))) {
            pw.println("source.. = src/");
            pw.println("output.. = bin/");
            pw.println("bin.includes = META-INF/,\\");
            pw.println("               .,\\");
            pw.println("               plugin.xml");
        }
    }

    private String formatBundleName(String name) {
        // Convert org.idempiere.mymodule to "My Module"
        String[] parts = name.split("\\.");
        String lastPart = parts[parts.length - 1];
        return addSpacesToCamelCase(
                lastPart.substring(0, 1).toUpperCase() + lastPart.substring(1)
        );
    }

    private String addSpacesToCamelCase(String s) {
        return s.replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    private void log(String message) {
        System.out.println("[PLUGIN-GEN] " + message);
    }
}
