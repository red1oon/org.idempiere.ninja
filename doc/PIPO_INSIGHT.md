# PIPO Insight - Pack In Pack Out Architecture

A deep-dive into iDempiere's 2Pack system for future developers.

## What is PIPO?

PIPO (Pack In Pack Out) is iDempiere's module distribution system. It allows:
- **Pack Out**: Export AD (Application Dictionary) definitions to XML
- **Pack In**: Import XML definitions into another iDempiere instance

Think of it as iDempiere's "package manager" for application metadata.

## Purpose

```
Developer Instance                    Production Instance
┌─────────────────┐                  ┌─────────────────┐
│  AD_Table       │                  │  AD_Table       │
│  AD_Column      │   2Pack.zip      │  AD_Column      │
│  AD_Window      │ ───────────────► │  AD_Window      │
│  AD_Tab         │   (portable)     │  AD_Tab         │
│  AD_Field       │                  │  AD_Field       │
│  AD_Menu        │                  │  AD_Menu        │
└─────────────────┘                  └─────────────────┘
```

**Key Use Cases:**
1. Distribute plugins/modules
2. Migrate customizations between environments
3. Version control for AD changes
4. Backup/restore application dictionary

## Core Architecture

### Package Structure

```
org.adempiere.pipo2/
├── handlers/           # Element-specific import/export handlers
│   ├── GenericPOElementHandler.java    # Fallback for any table
│   ├── TableElementHandler.java        # AD_Table specific
│   ├── ColumnElementHandler.java       # AD_Column specific
│   ├── WindowElementHandler.java       # AD_Window specific
│   └── ...
├── PackInHandler.java      # SAX handler for import
├── PackOut.java            # Export orchestrator
├── PoFiller.java           # XML → PO mapping
├── PoExporter.java         # PO → XML mapping
└── AbstractElementHandler.java  # Base class for handlers
```

### Key Classes

#### 1. PackInHandler.java
The SAX parser that reads PackOut.xml during import.

```java
// Simplified flow
public void startElement(String uri, String localName, String qName, Attributes atts) {
    // Find handler for this element type
    IElementHandler handler = factory.getHandler(qName);
    if (handler != null) {
        handler.startElement(ctx, element);
    }
    // If no handler found, element is SILENTLY SKIPPED!
}
```

**Critical Insight:** If no handler exists for an element, PIPO silently skips it. No error, no warning. This is why custom tables need GenericPOElementHandler.

#### 2. PoFiller.java
Maps XML elements to PO (Persistent Object) fields.

```java
// Key method - autoFill
public void autoFill(List<String> excludes) {
    // For each XML child element
    for (Element child : element.getChildren()) {
        String columnName = child.getName();

        // Skip excluded columns
        if (excludes.contains(columnName)) continue;

        // Set value on PO
        po.set_ValueOfColumn(columnName, parseValue(child));
    }
}
```

**Default Excludes (auto-managed by PIPO):**
- `Created`, `CreatedBy`, `Updated`, `UpdatedBy`
- `AD_Client_ID` (derived from header)

#### 3. PoExporter.java
Maps PO fields to XML elements.

```java
// Key method - export
public void export(List<String> excludes) {
    for (int i = 0; i < po.get_ColumnCount(); i++) {
        String columnName = po.get_ColumnName(i);

        // Skip excluded columns
        if (excludes.contains(columnName.toLowerCase())) continue;

        // Add to XML
        addElement(columnName, po.get_Value(i));
    }
}
```

#### 4. GenericPOElementHandler.java
The fallback handler for any table without a specific handler.

```java
public void startElement(Properties ctx, Element element) {
    String tableName = element.getElementValue();  // e.g., "MP_Maintain"

    // Create PO dynamically
    PO po = MTable.get(ctx, tableName).getPO(0, trxName);

    // Fill from XML
    PoFiller filler = new PoFiller(ctx, po, element);
    filler.autoFill(excludes);

    po.saveEx();
}
```

## PackOut.xml Structure

### Header Format

```xml
<?xml version="1.0" encoding="UTF-8"?>
<idempiere
    Name="ModuleName"
    Version="1.0.0"
    CompVer="11.0.0"
    DataBase="PostgreSQL"
    Description="Module description"
    Creator="Author Name"
    CreatorContact="email@example.com"
    Client="0-SYSTEM-System">
```

**Client Attribute Format:** `AD_Client_ID-Value-Name`
- `0-SYSTEM-System` = System level (all clients)
- `11-GardenWorld-GardenWorld` = Specific client

### Element Structure

```xml
<AD_Table type="table">
    <AD_Table_ID>1000000</AD_Table_ID>
    <AD_Table_UU>a1b2c3d4-...</AD_Table_UU>
    <AD_Client_ID>0</AD_Client_ID>
    <AD_Org_ID>0</AD_Org_ID>
    <IsActive>Y</IsActive>
    <TableName>MP_Maintain</TableName>
    <Name>Preventive Maintenance</Name>
    <Description>...</Description>
    <Help>...</Help>
    <EntityType>U</EntityType>
    ...
</AD_Table>
```

**Key Attributes:**
- `type="table"` - Indicates this is table data (not reference)
- Element name = Table name (AD_Table, AD_Window, etc.)
- Child elements = Column values

### Element Hierarchy (Typical Order)

```
1. AD_Element       (Column/field name definitions)
2. AD_Reference     (Reference types, lists)
3. AD_Val_Rule      (Validation rules)
4. AD_Table         (Table definitions)
5. AD_Column        (Column definitions)
6. AD_Window        (Window definitions)
7. AD_Tab           (Tab definitions - nested in Window)
8. AD_Field         (Field definitions - nested in Tab)
9. AD_Process       (Process/report definitions)
10. AD_Menu         (Menu structure)
11. Custom tables   (MP_Maintain, etc.)
```

**Important:** Order matters! Parent records must exist before children.

## EntityType - The Safety Mechanism

```
EntityType = 'D'  → Dictionary (Core iDempiere)
EntityType = 'U'  → User (Custom/Module)
EntityType = 'C'  → Customization
EntityType = 'A'  → Application
```

**Rule:** Never modify EntityType='D' records via 2Pack. They belong to core iDempiere.

```java
// In handlers, always check:
if ("D".equals(entityType)) {
    // Skip or warn - don't modify core
    return;
}
```

## Import Flow

```
2Pack.zip
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│                    PackInHandler                         │
│  (SAX Parser)                                           │
│                                                         │
│  1. Parse header → Extract client, version              │
│  2. For each element:                                   │
│     a. Find handler (TableElementHandler, etc.)         │
│     b. If no handler → GenericPOElementHandler          │
│     c. If still no handler → SKIP (silent!)             │
│  3. Handler creates/updates PO                          │
│  4. PoFiller maps XML → PO fields                       │
│  5. po.saveEx() persists to DB                          │
└─────────────────────────────────────────────────────────┘
    │
    ▼
Database (AD_Table, AD_Column, etc.)
```

## Export Flow

```
Database
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│                      PackOut                             │
│                                                         │
│  1. Query records to export (by EntityType, etc.)       │
│  2. For each record:                                    │
│     a. Find handler for table                           │
│     b. PoExporter maps PO → XML                         │
│     c. Add to document                                  │
│  3. Write XML to file                                   │
│  4. Create ZIP structure                                │
└─────────────────────────────────────────────────────────┘
    │
    ▼
2Pack.zip
└── ModuleName/
    └── dict/
        └── PackOut.xml
```

## ZIP Structure Requirements

```
# CORRECT - Will import
ModuleName/
└── dict/
    └── PackOut.xml

# ALSO CORRECT
dict/
└── PackOut.xml

# WRONG - "File does not exist" error
PackOut.xml  (at root)
```

PIPO looks for `*/dict/PackOut.xml` or `dict/PackOut.xml` pattern.

## Common Pitfalls

### 1. Silent Handler Skip
```java
// If element name doesn't match any handler
// PIPO silently skips - no error!
// Solution: Use GenericPOElementHandler for custom tables
```

### 2. Missing UUID
```xml
<!-- WRONG - literal function -->
<MP_Maintain_UU>uuid_generate_v4()</MP_Maintain_UU>

<!-- CORRECT - actual UUID -->
<MP_Maintain_UU>550e8400-e29b-41d4-a716-446655440000</MP_Maintain_UU>
```

### 3. Timestamp Format
```xml
<!-- WRONG -->
<Created>2024-01-15</Created>

<!-- CORRECT -->
<Created>2024-01-15 00:00:00</Created>
```

### 4. Client Attribute Missing
```xml
<!-- WRONG - will cause NPE -->
<idempiere Name="Test" Version="1.0">

<!-- CORRECT -->
<idempiere Name="Test" Version="1.0" Client="11-GardenWorld-GardenWorld">
```

### 5. Large Window Elements
AD_Window can contain nested AD_Tab and AD_Field elements, spanning 5000+ lines.
```xml
<AD_Window type="table">
    <AD_Window_ID>...</AD_Window_ID>
    ...
    <AD_Tab type="table">        <!-- Line 100 -->
        ...
        <AD_Field>...</AD_Field>  <!-- Line 500 -->
        <AD_Field>...</AD_Field>  <!-- Line 600 -->
        ...
    </AD_Tab>                     <!-- Line 5000 -->
</AD_Window>                      <!-- Line 5100 -->
```

## Version2PackActivator

For OSGi plugins, iDempiere uses `Version2PackActivator` to auto-import 2Packs.

```java
// In plugin's Activator
public class Activator extends AbstractActivator {
    // Looks for META-INF/2Pack_*.zip files
    // Version pattern: 2Pack_1.0.0.zip, 2Pack_1.0.1.zip
    // Imports newer versions automatically on bundle start
}
```

**File naming:** `2Pack_<version>.zip` where version is `major.minor.patch`

## SilentPiper vs PIPO

| Aspect | PIPO (iDempiere) | SilentPiper |
|--------|------------------|-------------|
| Runtime | Requires iDempiere | Standalone Java |
| Connection | Via iDempiere context | Direct JDBC |
| Handlers | Specific per table | Generic + SAX |
| Validation | Full AD validation | Basic type checking |
| Rollback | Transaction-based | Manual tracking |
| Use Case | Production deploy | Development, testing |

## Key Takeaways

1. **PIPO is SAX-based** - Streams XML, memory efficient
2. **Handlers are pluggable** - OSGi services for custom tables
3. **Silent failures** - Missing handlers = skipped elements
4. **Order matters** - Parent records before children
5. **EntityType is sacred** - Never touch 'D' (Dictionary)
6. **UUIDs required** - Can't use SQL functions in XML
7. **Client attribute** - Required in header, format is `ID-Value-Name`

## References

- Source: `org.adempiere.pipo2` bundle
- Key files:
  - `PackInHandler.java` - Import logic
  - `PackOut.java` - Export logic
  - `PoFiller.java` - XML → PO
  - `PoExporter.java` - PO → XML
  - `GenericPOElementHandler.java` - Fallback handler

---
*Document created from reverse-engineering PIPO for SilentPiper development*
