# SilentPiper - Offline iDempiere Model & Data Tool

Standalone tool for creating and deploying iDempiere AD models and data **without requiring iDempiere runtime**.

## Why "Silent"?

**Silent = No iDempiere Runtime Required**

Traditional iDempiere development requires:
- Running iDempiere server
- OSGi container
- Full application context

SilentPiper bypasses all of that:
- Direct JDBC to PostgreSQL
- Pure Java + SQLite for staging
- PIPO-compatible output

## Overview

SilentPiper enables the complete Ninja workflow:
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

## Quick Start

```bash
# 1. Stage Excel model to SQLite (offline)
java -jar SilentPiper.jar ninja.db stage HRMIS.xlsx

# 2. Generate AD model 2Pack (offline)
java -jar SilentPiper.jar ninja.db packout-model HRMIS ./output

# 3. Import to PostgreSQL (requires DB connection)
java -jar SilentPiper.jar ninja.db import-silent ./output/HRMIS_Model_1_0_0.zip
```

## Commands Reference

### Model Staging (Excel → SQLite)

| Command | Description |
|---------|-------------|
| `stage <excel.xlsx>` | Parse Excel intent model to SQLite staging tables |
| `show [bundle]` | Display staged models |
| `apply <bundle> [dryrun]` | Apply staged model to iDempiere (requires runtime) |
| `rollback <bundle>` | Deactivate applied model records |

**Staging Tables:**
- `RO_ModelHeader` - Bundle metadata (name, version, author)
- `RO_ModelMaker` - Table definitions (name, columns, master relationship)

### 2Pack Generation (SQLite → ZIP)

| Command | Description |
|---------|-------------|
| `packout-model <bundle> [dir]` | Generate AD 2Pack from SQLite model (OFFLINE) |
| `packout <pack> [dir] [client]` | Export staged data to 2Pack |
| `packout-ad <prefix> [dir]` | Export AD model from PostgreSQL |
| `export-2pack <pack> [dir]` | Generate 2Pack.zip for distribution |

**packout-model** generates PIPO-compatible 2Pack containing:
- AD_Element (column definitions)
- AD_Table (table structure)
- AD_Column (column metadata)
- AD_Window (UI windows)
- AD_Tab (window tabs)
- AD_Field (tab fields)
- AD_Menu (navigation entries)

### Silent Import (ZIP → PostgreSQL) - NO iDempiere Runtime!

| Command | Description | iDempiere? |
|---------|-------------|------------|
| `import-silent <2pack.zip>` | Import 2Pack directly via JDBC | **No** |
| `apply-data <pack>` | Insert staged data directly | **No** |
| `import <2pack.zip>` | Import using iDempiere PIPO | Yes |
| `validate <2pack.zip>` | Validate 2Pack structure | Yes |

**import-silent** - The Key Silent Feature:
```
2Pack.zip → SAX Parser → SQL INSERT/UPDATE → PostgreSQL
              (no iDempiere classes needed)
```

How it works:
1. Extracts `PackOut.xml` from ZIP
2. Parses XML using SAX (memory efficient)
3. Detects table columns via JDBC metadata
4. Generates INSERT or UPDATE based on UUID existence
5. Commits transaction on success

Supported tables:
- All `AD_*` tables (Element, Table, Column, Window, Tab, Field, Menu, Process)
- All `MP_*` tables (Maintenance module)
- All `A_ASSET*` tables (Asset module)
- Any table following iDempiere conventions (`TableName_ID`, `TableName_UU`)

### SQL Data Operations

| Command | Description |
|---------|-------------|
| `sql2pack <data.sql>` | Parse SQL INSERT statements to SQLite |
| `packs [name]` | Show staged data packs |
| `apply-data <pack>` | Insert data directly to PostgreSQL |
| `applied <pack>` | Show applied records (tracked for cleanup) |
| `clean-data <pack>` | Delete applied records (reverse order) |

### AD Model Sync

| Command | Description |
|---------|-------------|
| `sync-ad` | Pull AD model from PostgreSQL to SQLite |
| `info` | Show AD model statistics in SQLite |

### History & Tracking

| Command | Description |
|---------|-------------|
| `history [limit]` | Show operation history |

All operations are logged to SQLite tables:
- `piper_operation` - Operation log (type, status, counts)
- `piper_detail` - Record-level details
- `piper_version` - Version tracking

## Excel Format

### Config Sheet
| Key | Value |
|-----|-------|
| Bundle-Name | HRMIS |
| Bundle-Version | 1.0.0 |
| Package-Prefix | org.hrmis |
| Entity-Type | U |

### Model Sheet (or ModelMakerSource)
| WorkflowStructure | KanbanBoard | SeqNo | Master | TableName | Help | ColumnSet |
|-------------------|-------------|-------|--------|-----------|------|-----------|
| N | N | 10 | | HR_Employee | Employee records | Name,Email,Phone |
| N | N | 20 | HR_Employee | HR_Contract | Employment contracts | StartDate:D,EndDate:D,Salary:A |

### Column Type Prefixes
| Prefix | Type | Example |
|--------|------|---------|
| (none) | String | Name |
| Q# | Quantity | Q#Qty |
| A# | Amount | A#Salary |
| D# | Date | D#StartDate |
| T# | DateTime | T#Created |
| Y# | Yes/No | Y#IsActive |
| L# | List | L#Status |
| I# | Integer | I#SeqNo |
| X# | Text (long) | X#Description |

## Generated 2Pack Structure

```
BundleName_Model/
└── dict/
    └── PackOut.xml
```

XML Header format:
```xml
<idempiere Name="HRMIS_Model" Version="1.0.0"
           Client="0-SYSTEM-System" ...>
```

## Database Connections

### PostgreSQL (for import-silent, apply-data)
Uses `idempiere.properties` or specify via system property:
```bash
java -DPropertyFile=/path/to/idempiere.properties -jar SilentPiper.jar ...
```

### SQLite (always required)
First argument is always the SQLite database path:
```bash
java -jar SilentPiper.jar ninja.db <command> ...
```

## Examples

### Complete Offline Workflow
```bash
# Stage Excel
java -jar SilentPiper.jar ninja.db stage ./models/HRMIS.xlsx

# View staged model
java -jar SilentPiper.jar ninja.db show HRMIS

# Generate 2Pack (no DB needed)
java -jar SilentPiper.jar ninja.db packout-model HRMIS ./output

# Deploy to PostgreSQL
java -jar SilentPiper.jar ninja.db import-silent ./output/HRMIS_Model_1_0_0.zip
```

### Data Pack Workflow
```bash
# Stage SQL data
java -jar SilentPiper.jar ninja.db sql2pack ./data/sample_data.sql

# View staged data
java -jar SilentPiper.jar ninja.db packs

# Generate client-specific 2Pack
java -jar SilentPiper.jar ninja.db packout GW_SampleData ./output 11

# Or apply directly
java -jar SilentPiper.jar ninja.db apply-data GW_SampleData

# Cleanup if needed
java -jar SilentPiper.jar ninja.db clean-data GW_SampleData
```

### Export Existing AD Model
```bash
# Sync AD model to SQLite first
java -jar SilentPiper.jar ninja.db sync-ad

# View synced model
java -jar SilentPiper.jar ninja.db info

# Export MP_* tables to 2Pack
java -jar SilentPiper.jar ninja.db packout-ad MP_ ./output
```

## Version

SilentPiper v1.0 - Part of org.idempiere.ninja

## License

GPL v2
