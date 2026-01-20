# Ninja Guide - iDempiere Plugin Generator

**Author:** red1 - red1org@gmail.com
**Version:** 2.0 (SQLite Staging Edition)
**Heritage:** Evolved from org.red1.ninja (2018-2019)

---

## Overview

Ninja generates complete iDempiere plugins from Excel definitions. No coding required for basic AD models.

**Key Principle:** Light and portable. Silent operation without launching iDempiere.

```
Excel → SQLite (stage) → Review → iDempiere (apply) → Rollback if needed
```

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         NINJA SUITE                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐ │
│  │  NinjaTestSuite │    │  SilentPiper    │    │  NinjaProcessor │ │
│  │  (Validation)   │    │  (Staging)      │    │  (Generation)   │ │
│  └────────┬────────┘    └────────┬────────┘    └────────┬────────┘ │
│           │                      │                      │          │
│           ▼                      ▼                      ▼          │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                     SQLite (ninja.db)                       │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │   │
│  │  │RO_ModelHeader│  │RO_ModelMaker│  │ piper_operation     │  │   │
│  │  │(bundle info) │  │(table defs) │  │ (history/audit)     │  │   │
│  │  └─────────────┘  └─────────────┘  └─────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                              │                                      │
│                              ▼                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                 iDempiere PostgreSQL                        │   │
│  │     AD_Table, AD_Column, AD_Window, AD_Tab, AD_Menu         │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Quick Start

```bash
# 1. Stage Excel to SQLite (no iDempiere needed)
./RUN_Piper.sh ninja.db stage templates/Ninja_HRMIS.xlsx

# 2. Review staged models
./RUN_Piper.sh ninja.db show

# 3. Apply to iDempiere (with dry run first)
./RUN_Piper.sh ninja.db apply Ninja_HRMIS dryrun
./RUN_Piper.sh ninja.db apply Ninja_HRMIS

# 4. Rollback if needed
./RUN_Piper.sh ninja.db rollback Ninja_HRMIS
```

---

## Model Structure

### Excel Format (ModelMakerSource Sheet)

Ninja uses a **transposed columnar format** where:
- Each **column** represents a table
- Each **row** represents column definitions

```
┌───────┬─────────────────────┬─────────────────┬─────────────────┐
│ Row   │ Column A            │ Column B        │ Column C        │
├───────┼─────────────────────┼─────────────────┼─────────────────┤
│ 0     │ (metadata/refs)     │ (metadata)      │ (metadata)      │
│ 1     │ HR_Employee         │ HR_Department   │ HR_Payroll      │ ← Table names
│ 2     │ Name                │ Name            │ Value           │ ← Column 1
│ 3     │ C_BPartner_ID       │ Description     │ C_BPartner_ID   │ ← Column 2
│ 4     │ Description         │ Code            │ C_Period_ID     │ ← Column 3
│ 5     │ D#HireDate          │                 │ A#Amount        │ ← Column 4
│ ...   │ ...                 │                 │ ...             │
└───────┴─────────────────────┴─────────────────┴─────────────────┘
```

### Column Type Prefixes

| Prefix | Type          | AD_Reference | Example        |
|--------|---------------|--------------|----------------|
| (none) | String        | 10           | Name           |
| S#     | String        | 10           | S#Description  |
| Q#     | Quantity      | 29           | Q#OrderQty     |
| A#     | Amount        | 12           | A#Salary       |
| Y#     | Yes/No        | 20           | Y#IsManager    |
| D#     | Date          | 15           | D#HireDate     |
| d#     | DateTime      | 16           | d#LoginTime    |
| T#     | Text          | 14           | T#Comments     |
| L#     | List          | 17           | L#Status       |

**Auto-detection:**
- Columns ending in `_ID` → TableDir reference (18)
- Columns named `IsXxx` → Yes/No (20)

---

## SQLite Staging Tables

### RO_ModelHeader
Bundle header information.

| Column             | Type    | Description                    |
|--------------------|---------|--------------------------------|
| RO_ModelHeader_ID  | INTEGER | Primary key                    |
| RO_ModelHeader_UU  | TEXT    | UUID                           |
| Name               | TEXT    | Bundle name (from Excel name)  |
| Description        | TEXT    | Source file path               |
| status             | TEXT    | STAGED, APPLIED, ROLLED_BACK   |
| created_at         | TEXT    | Timestamp                      |

### RO_ModelMaker
Table definitions.

| Column             | Type    | Description                    |
|--------------------|---------|--------------------------------|
| RO_ModelMaker_ID   | INTEGER | Primary key                    |
| RO_ModelMaker_UU   | TEXT    | UUID                           |
| RO_ModelHeader_UU  | TEXT    | FK to header                   |
| SeqNo              | INTEGER | Sequence number                |
| Name               | TEXT    | Table name (HR_Employee)       |
| Master             | TEXT    | Parent table (for detail tabs) |
| ColumnSet          | TEXT    | Comma-separated column defs    |
| WorkflowStructure  | TEXT    | Y/N                            |
| KanbanBoard        | TEXT    | Y/N                            |
| status             | TEXT    | STAGED, APPLIED, ROLLED_BACK   |
| ad_table_id        | INTEGER | ID in iDempiere after apply    |

### piper_operation
Operation history/audit log.

| Column         | Type    | Description                    |
|----------------|---------|--------------------------------|
| operation_id   | INTEGER | Primary key                    |
| operation_type | TEXT    | STAGE, APPLY, ROLLBACK         |
| file_path      | TEXT    | Source file                    |
| status         | TEXT    | STARTED, SUCCESS, FAILED       |
| tables_count   | INTEGER | Tables processed               |
| errors_count   | INTEGER | Errors encountered             |
| started_at     | TEXT    | Timestamp                      |
| completed_at   | TEXT    | Timestamp                      |

---

## SilentPiper Commands

### Stage (Excel → SQLite)
```bash
./RUN_Piper.sh ninja.db stage <excel.xlsx>
```
Parses Excel and stores RO_ModelHeader + RO_ModelMaker in SQLite.
No iDempiere connection required.

### Show (Review staged models)
```bash
./RUN_Piper.sh ninja.db show              # List all bundles
./RUN_Piper.sh ninja.db show Ninja_HRMIS  # Show bundle details
```

### Apply (SQLite → iDempiere)
```bash
./RUN_Piper.sh ninja.db apply Ninja_HRMIS dryrun  # Test (no changes)
./RUN_Piper.sh ninja.db apply Ninja_HRMIS         # Commit to DB
```
Creates AD_Table, AD_Column in iDempiere PostgreSQL.
Updates SQLite status to APPLIED and stores ad_table_id.

### Rollback (Deactivate applied records)
```bash
./RUN_Piper.sh ninja.db rollback Ninja_HRMIS
```
Sets IsActive='N' on AD_Table and AD_Column.
Updates SQLite status to ROLLED_BACK.

### History (Audit trail)
```bash
./RUN_Piper.sh ninja.db history [limit]
```

---

## Workflow

### 1. VALIDATE (Optional)
```bash
./RUN_Test.sh templates/Ninja_HRMIS.xlsx 3
```
- Level 1: File exists, JDBC connection
- Level 2: Excel parsing (POI)
- Level 3: Schema validation (AD_Table, AD_Window)

### 2. STAGE
```bash
./RUN_Piper.sh ninja.db stage templates/Ninja_HRMIS.xlsx
```
- Parse Excel ModelMakerSource sheet
- Store in SQLite (RO_ModelHeader, RO_ModelMaker)
- No iDempiere needed

### 3. REVIEW
```bash
./RUN_Piper.sh ninja.db show Ninja_HRMIS
```
- Inspect staged tables and columns
- Verify before applying

### 4. APPLY
```bash
./RUN_Piper.sh ninja.db apply Ninja_HRMIS dryrun  # Test first
./RUN_Piper.sh ninja.db apply Ninja_HRMIS         # Commit
```
- Transform RO_ → AD_ in PostgreSQL
- Track applied IDs in SQLite

### 5. ROLLBACK (if needed)
```bash
./RUN_Piper.sh ninja.db rollback Ninja_HRMIS
```
- Deactivate AD records (IsActive='N')
- Update SQLite status

---

## File Structure

```
org.idempiere.ninja/
├── src/org/idempiere/ninja/
│   ├── NinjaProcessor.java       # Main processor (OSGi mode)
│   ├── piper/
│   │   └── SilentPiper.java      # SQLite staging (silent mode)
│   └── test/
│       └── NinjaTestSuite.java   # Validation test suite
├── templates/
│   └── Ninja_HRMIS.xlsx          # Example Excel model
├── lib/                          # SQLite JDBC, SLF4J
├── RUN_Piper.sh                  # SilentPiper runner
├── RUN_Test.sh                   # Test suite runner
├── RUN_Ninja.sh                  # Full processor runner
├── ninja.db                      # SQLite database (created on first run)
└── NinjaGuide.md                 # This guide
```

---

## Configuration

### idempiere.properties
SilentPiper reads connection info from:
```
/home/red1/idempiere-dev-setup/idempiere/idempiere.properties
```

Override with:
```bash
PROPERTY_FILE=/path/to/idempiere.properties ./RUN_Piper.sh ninja.db apply Ninja_HRMIS
```

### CConnection Format
```properties
Connection=CConnection[name=idempiere,AppsHost=localhost,AppsPort=443,type=PostgreSQL,DBhost=localhost,DBport=5432,DBname=idempiere,UID=adempiere]
db_Pwd=adempiere
```

---

## Benefits of SQLite Staging

1. **Light and Portable** - No iDempiere runtime needed for staging
2. **Review Before Apply** - Inspect models before touching production
3. **Rollback Support** - Deactivate applied records if needed
4. **Audit Trail** - Complete history of all operations
5. **Offline Work** - Stage models without database connection
6. **Easy Maintenance** - Single SQLite file for all staging data

---

## Example Session

```bash
# Start fresh
rm -f ninja.db

# Stage the HRMIS model
./RUN_Piper.sh ninja.db stage templates/Ninja_HRMIS.xlsx
# Output: Staged 18 table definitions to SQLite

# Review what was staged
./RUN_Piper.sh ninja.db show
# Output: Lists all bundles and tables

# See details
./RUN_Piper.sh ninja.db show Ninja_HRMIS
# Output: Shows each table with columns

# Test apply (dry run)
./RUN_Piper.sh ninja.db apply Ninja_HRMIS dryrun

# Apply for real
./RUN_Piper.sh ninja.db apply Ninja_HRMIS

# Check history
./RUN_Piper.sh ninja.db history

# If something went wrong
./RUN_Piper.sh ninja.db rollback Ninja_HRMIS
```

---

## Troubleshooting

### "SQLite JDBC driver not found"
Add sqlite-jdbc.jar to lib/:
```bash
wget -O lib/sqlite-jdbc-3.44.0.0.jar \
  https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.44.0.0/sqlite-jdbc-3.44.0.0.jar
```

### "No ModelMakerSource or Model sheet found"
Excel must have a sheet named `ModelMakerSource` or `Model`.

### "Name column not found"
Ensure Row 1 has table names (not formulas or cell references).

### "POI classes not found"
Ensure POI 5.2.2 jars are in Maven repository or lib/.

---

## Requirements

- Java 17+
- PostgreSQL (for iDempiere connection)
- SQLite JDBC driver
- Apache POI 5.2.2 (for Excel parsing)

---

## License

GPL v2 - Free for commercial and non-commercial use.

---

## Contributing

Report issues and contribute at: https://github.com/red1oon/org.idempiere.ninja

---

*Ninja - Making iDempiere plugin development accessible to everyone*
