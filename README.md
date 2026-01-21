# iDempiere Ninja - Plugin Generator

**Author:** red1 - red1org@gmail.com
**Heritage:** Evolved from org.red1.ninja (2018-2019)

Generate complete iDempiere plugins from simple Excel definitions. No coding required for basic AD models.

## History

Ninja was originally developed as `org.red1.ninja` featuring:
- Model generation from Excel/CSV definitions
- Kanban board auto-generation
- Print format generation
- Yandex translation integration
- Workflow structure generation

This new version (`org.idempiere.ninja`) is a modernized rewrite focusing on:
- Silent command-line operation
- Standalone 2Pack generation
- Complete OSGI plugin scaffolding
- Process and Menu sheet support

## Quick Start

```bash
# Generate everything from Excel
./RUN_Ninja.sh MyModule.xls

# Result: Complete OSGI plugin ready for Eclipse import
```

## What Ninja Does

```
Excel (MyModule.xls)
        │
        ▼
┌─────────────────────────────────────────────────┐
│              NINJA PROCESSOR                    │
├─────────────────────────────────────────────────┤
│                                                 │
│  Mode A: Inject AD Models into Database         │
│  • Creates AD_Table, AD_Column, AD_Window...    │
│  • Synchronizes tables to PostgreSQL/Oracle     │
│                                                 │
│  Mode B: Generate 2Pack.zip                     │
│  • Creates distributable 2Pack file             │
│  • Ready for Incremental2PackActivator          │
│                                                 │
│  Mode C: Create AD_Package_Exp Records          │
│  • Prepares for future changes via PackOut UI   │
│                                                 │
│  Mode D: Generate OSGI Plugin Structure         │
│  • Complete Eclipse project ready to import     │
│  • META-INF/MANIFEST.MF, plugin.xml, etc.       │
│                                                 │
└─────────────────────────────────────────────────┘
        │
        ▼
org.idempiere.mymodule/
├── META-INF/
│   ├── MANIFEST.MF
│   └── 2Pack_1.0.0.zip
├── src/
├── plugin.xml
├── .classpath
├── .project
└── README.md
```

## Excel Format

### Config Sheet (Optional)

| Property       | Value                    |
|----------------|--------------------------|
| Bundle-Name    | org.idempiere.hr         |
| Bundle-Version | 1.0.0                    |
| Bundle-Vendor  | My Company               |
| Package-Prefix | org.idempiere.hr         |
| Entity-Type    | U                        |

### List Sheet (Reference Lists)

Row 1 = Reference names (creates AD_Reference)
Row 2+ = List values (creates AD_Ref_List)

| Status  | Priority | DocType  |
|---------|----------|----------|
| Draft   | High     | Standard |
| Active  | Medium   | Return   |
| Closed  | Low      | Warranty |

### Model Sheet (Tables & Windows)

```
┌────────────────┬───────────┬────────────┬───────────────┐
│ A              │ B         │ C          │ D             │
├────────────────┼───────────┼────────────┼───────────────┤
│ HumanResources │           │            │               │  <- Row 1: Main menu (A1)
│                │ Employee  │ Department │ Employee      │  <- Row 2: Tables (B=parent, C=parent, D=child of B)
│                │ L#Status  │ Name       │ Employee      │  <- Row 1 defines master/parent relationships
│                │ Name      │ Code       │ D#HireDate    │  <- Row 3+: Columns
│                │ A#Salary  │ T#Desc     │ DepartmentRef │
│                │ D#Birth   │            │               │
└────────────────┴───────────┴────────────┴───────────────┘
```

**Row 1:** Cell A1 = Main menu name. First 2 chars become table prefix (e.g., "HumanResources" → "HR_")

**Row 2:** Table/Window names. Each non-empty cell creates a Table + Window + Tab

**Row 3+:** Column definitions for each table (column by column)

### Column Type Prefixes

| Prefix | Type          | Length | Example        |
|--------|---------------|--------|----------------|
| (none) | String        | 22     | Name           |
| S#     | String        | 22     | S#Description  |
| Q#     | Quantity      | 11     | Q#OrderQty     |
| A#     | Amount        | 11     | A#Salary       |
| Y#     | Yes/No        | 1      | Y#IsManager    |
| D#     | Date          | -      | D#HireDate     |
| d#     | DateTime      | -      | d#LoginTime    |
| T#     | Text          | 2000   | T#Comments     |
| L#     | List (Ref)    | -      | L#Status       |

**Auto-detection:** Columns ending in `_ID` become TableDir references. Columns named `IsXxx` become Yes/No.

### Process Sheet (AD_Process)

Define processes with optional table linkage (gear icon):

| ProcessName | Class | Table | Parameters |
|-------------|-------|-------|------------|
| CheckUpdate | org.idempiere.process.CheckUpdate | UD_History | ForceCheck:YesNo |
| RunReport | org.idempiere.process.RunReport | | StartDate:Date, EndDate:Date |

**Parameters format:** `ParamName:Type` (comma-separated)
**Types:** String, YesNo, Integer, Date, DateTime, Amount, Quantity, List, TableDir

### Menu Sheet (Custom Menu Structure)

Override auto-generated menu hierarchy:

| MenuName | Type | Action | Target | Parent |
|----------|------|--------|--------|--------|
| HR System | Summary | | | |
| Employees | Window | W | Employee | HR System |
| Run Payroll | Process | P | RunPayroll | HR System |

### Data Sheets (Optional)

Sheet name must match table name (e.g., "HR_Department")

| Name  | Code   |
|-------|--------|
| Sales | SALES  |
| HR    | HR     |
| IT    | IT     |

## Command Line Usage

```bash
# All modes (default)
./RUN_Ninja.sh MyModule.xls

# Specific modes
./RUN_Ninja.sh MyModule.xls -a        # DB inject only
./RUN_Ninja.sh MyModule.xls -b        # 2Pack only
./RUN_Ninja.sh MyModule.xls -d        # Plugin structure only
./RUN_Ninja.sh MyModule.xls -abd      # All except PackExp

# Options
./RUN_Ninja.sh MyModule.xls -v        # Verbose logging
./RUN_Ninja.sh MyModule.xls -o /tmp   # Custom output directory
```

## Modes

| Flag | Mode              | Description                              |
|------|-------------------|------------------------------------------|
| -a   | DB Inject         | Create AD models in database             |
| -b   | 2Pack Generate    | Create 2Pack.zip file                    |
| -c   | PackExp Create    | Create AD_Package_Exp for future changes |
| -d   | Plugin Generate   | Create complete OSGI plugin structure    |
| -v   | Verbose           | Show detailed logging                    |
| -o   | Output Directory  | Specify where to generate files          |

## Generated Plugin Structure

```
org.idempiere.mymodule/
├── META-INF/
│   ├── MANIFEST.MF           # OSGI bundle configuration
│   └── 2Pack_1.0.0.zip       # AD model definitions
├── OSGI-INF/                  # Service component XMLs
├── src/
│   └── org/idempiere/mymodule/
│       └── Activator.java    # Uses Incremental2PackActivator
├── plugin.xml                 # Eclipse extension points
├── build.properties
├── .classpath
├── .project
└── README.md                  # Module documentation
```

## Templates

The `templates/` directory contains example Excel files:
- **VersionMaster.xls.txt** - System utility plugin template (version tracking, plugin registry, backup management)

## Workflow

### First Time Setup

1. Create Excel file with your model definitions
2. Run: `./RUN_Ninja.sh MyModule.xls`
3. Import generated plugin into Eclipse
4. Test and customize

### Making Changes

**Option A: Re-run Ninja**
1. Update Excel file
2. Re-run Ninja with new version in Config sheet
3. New 2Pack will be created

**Option B: Use iDempiere UI**
1. Ninja created AD_Package_Exp records (mode -c)
2. Make changes in Application Dictionary
3. Use PackOut to create new 2Pack

### Deploying Updates

1. Increment version in Excel Config sheet
2. Run Ninja
3. Copy new `2Pack_x.x.x.zip` to plugin's META-INF/
4. `Incremental2PackActivator` auto-imports on restart

## Examples

### Simple HR Module

```
Config sheet:
| Bundle-Name    | org.idempiere.hr |
| Bundle-Version | 1.0.0            |

Model sheet:
| HumanResources |          |            |
|                | Employee | Department |
|                | Name     | Name       |
|                | L#Status | Code       |
|                | A#Salary |            |
|                | D#Birth  |            |
```

### With Child Tables

```
Model sheet:
| SalesOrder    |         |             |
|               | Order   | Order       |  <- Row 1: "Order" is parent of col D
|               | DocNo   | OrderLine   |  <- Row 2: Tables
|               | Date    | Q#Qty       |  <- Row 3+: Columns
|               | A#Total | A#Price     |
```

## Requirements

- iDempiere 11+
- Java 17+
- PostgreSQL or Oracle database
- Excel (.xls format - use LibreOffice or Excel)

## Troubleshooting

### "Excel file not found"
Provide full path: `./RUN_Ninja.sh /full/path/to/MyModule.xls`

### "idempiere.properties not found"
Run from iDempiere server directory, or set `IDEMPIERE_HOME`

### "Table already exists"
Ninja skips existing tables. Change table name or drop existing table.

### Validation Errors
Ninja provides helpful examples when Excel format is wrong:
```
[VALIDATOR] ERROR: Model sheet: Cell A1 is empty
  ┌─────────────────────────────────────────────────────────────┐
  │  MODEL SHEET - CELL A1:                                     │
  │                                                             │
  │  Cell A1 must contain the MAIN MENU name.                   │
  │  Example:                                                   │
  │  ┌────────────────┬──────────┬────────────┐                 │
  │  │ HumanResources │          │            │  <- A1          │
  │  └────────────────┴──────────┴────────────┘                 │
  └─────────────────────────────────────────────────────────────┘
```

---

# SilentPiper - Offline Model & Data Tool

Standalone tool for deploying iDempiere AD models and data **without requiring iDempiere runtime**.

## Why "Silent"?

**Silent = No iDempiere Runtime Required**

Traditional iDempiere development requires running server, OSGi container, full application context.

SilentPiper bypasses all of that:
- Direct JDBC to PostgreSQL
- Pure Java + SQLite for staging
- PIPO-compatible output

## Silent Workflow

```
Excel → SQLite → 2Pack → PostgreSQL
       (silent)  (silent)  (silent)
```

| Operation | iDempiere Required? | Database Required? |
|-----------|--------------------|--------------------|
| `stage` | No | SQLite only |
| `packout-model` | No | SQLite only |
| `packout` | No | SQLite only |
| `import-silent` | No | PostgreSQL (JDBC) |
| `apply-data` | No | PostgreSQL (JDBC) |
| `import` | Yes | PostgreSQL + iDempiere |
| `apply` | Yes | PostgreSQL + iDempiere |

## SilentPiper Quick Start

```bash
# 1. Stage Excel model to SQLite (offline)
java -jar SilentPiper.jar ninja.db stage HRMIS.xlsx

# 2. Generate AD model 2Pack (offline)
java -jar SilentPiper.jar ninja.db packout-model HRMIS ./output

# 3. Import to PostgreSQL (no iDempiere runtime needed!)
java -jar SilentPiper.jar ninja.db import-silent ./output/HRMIS_Model_1_0_0.zip
```

## SilentPiper Commands

### Model Staging (Excel → SQLite)

| Command | Description |
|---------|-------------|
| `stage <excel.xlsx>` | Parse Excel intent model to SQLite |
| `show [bundle]` | Display staged models |

### 2Pack Generation (SQLite → ZIP)

| Command | Description |
|---------|-------------|
| `packout-model <bundle> [dir]` | Generate AD 2Pack from SQLite (OFFLINE) |
| `packout <pack> [dir] [client]` | Export staged data to 2Pack |
| `packout-ad <prefix> [dir]` | Export AD model from PostgreSQL |

**packout-model** generates PIPO-compatible 2Pack containing:
- AD_Element, AD_Table, AD_Column
- AD_Window, AD_Tab, AD_Field
- AD_Menu

### Silent Import (ZIP → PostgreSQL)

| Command | Description |
|---------|-------------|
| `import-silent <2pack.zip>` | Import 2Pack directly via JDBC |
| `apply-data <pack>` | Insert staged data directly |

**import-silent** workflow:
```
2Pack.zip → SAX Parser → SQL INSERT/UPDATE → PostgreSQL
              (no iDempiere classes needed)
```

Supported: All `AD_*`, `MP_*`, `A_ASSET*` tables

### SQL Data Operations

| Command | Description |
|---------|-------------|
| `sql2pack <data.sql>` | Parse SQL INSERT statements to SQLite |
| `packs [name]` | Show staged data packs |
| `applied <pack>` | Show applied records |
| `clean-data <pack>` | Delete applied records |

### AD Model Sync

| Command | Description |
|---------|-------------|
| `sync-ad` | Pull AD model from PostgreSQL to SQLite |
| `info` | Show AD model statistics |
| `history [limit]` | Show operation history |

## SilentPiper Examples

### Complete Offline Workflow
```bash
# Stage, generate, deploy - all without iDempiere running
java -jar SilentPiper.jar ninja.db stage ./models/HRMIS.xlsx
java -jar SilentPiper.jar ninja.db packout-model HRMIS ./output
java -jar SilentPiper.jar ninja.db import-silent ./output/HRMIS_Model_1_0_0.zip
```

### Data Pack Workflow
```bash
java -jar SilentPiper.jar ninja.db sql2pack ./data/sample.sql
java -jar SilentPiper.jar ninja.db packout GW_Sample ./output 11
java -jar SilentPiper.jar ninja.db apply-data GW_Sample
```

---

## License

GPL v2 - Free for commercial and non-commercial use.

## Contributing

Report issues and contribute at: https://github.com/red1oon/org.idempiere.ninja

---
*Ninja - Making iDempiere plugin development accessible to everyone*
