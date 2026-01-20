#!/usr/bin/env python3
"""
Apply SQL UPDATE statements to PackOut.xml directly (no database needed).

Parses SQL files for UPDATE statements and applies Help/Name/Description
values to matching elements in PackOut.xml.

Usage:
    python3 sql2packout.py <packout.xml> <sql_file_or_dir> [output.xml]

Examples:
    python3 sql2packout.py PackOut.xml MP_Help_Update.sql
    python3 sql2packout.py PackOut.xml ./scripts/
    python3 sql2packout.py PackOut.xml ./scripts/ updated_PackOut.xml
"""
import xml.etree.ElementTree as ET
import re
import sys
import os
import glob

def print_usage():
    print(__doc__)
    sys.exit(1)

if len(sys.argv) < 3:
    print_usage()

input_xml = sys.argv[1]
sql_input = sys.argv[2]
output_xml = sys.argv[3] if len(sys.argv) > 3 else input_xml

if not os.path.exists(input_xml):
    print(f"Error: PackOut.xml not found: {input_xml}")
    sys.exit(1)

# Collect SQL files
sql_files = []
if os.path.isdir(sql_input):
    sql_files = sorted(glob.glob(os.path.join(sql_input, "*.sql")))
elif os.path.isfile(sql_input):
    sql_files = [sql_input]
else:
    print(f"Error: SQL input not found: {sql_input}")
    sys.exit(1)

print(f"PackOut: {input_xml}")
print(f"SQL files: {len(sql_files)}")

# Parse SQL files to extract UPDATE mappings
updates = {
    'AD_Element': {},    # ColumnName -> {Name, PrintName, Help, Description}
    'AD_Window': {},     # Name or ID -> {Name, Help, Description}
    'AD_Tab': {},        # ID -> {Name, Help, Description}
    'AD_Field': {},      # ID -> {Name, Help, Description, IsCentrallyMaintained}
    'AD_Process': {},    # ID -> {Name, Help, Description}
    'AD_Form': {},       # ID -> {Name, Help, Description}
    'AD_Menu': {},       # ID -> {Name, Description}
}

def extract_string(match):
    """Extract string value, handling quotes"""
    if match:
        val = match.strip()
        if val.startswith("'") and val.endswith("'"):
            val = val[1:-1]
        return val.replace("''", "'")  # SQL escape
    return None

def parse_sql_file(filepath):
    """Parse SQL file for UPDATE statements"""
    with open(filepath, 'r') as f:
        content = f.read()

    # Remove SQL comments
    content = re.sub(r'--.*$', '', content, flags=re.MULTILINE)

    # Split into statements
    statements = re.split(r';\s*\n', content)

    for stmt in statements:
        stmt = stmt.strip()
        if not stmt.upper().startswith('UPDATE'):
            continue

        # Parse UPDATE table SET ... WHERE ...
        match = re.match(r'UPDATE\s+(\w+)\s+SET\s+(.+?)\s+WHERE\s+(.+)', stmt, re.IGNORECASE | re.DOTALL)
        if not match:
            continue

        table = match.group(1).upper()
        set_clause = match.group(2)
        where_clause = match.group(3)

        # Extract SET values
        values = {}
        # Match field = 'value' or field = value patterns
        set_patterns = re.findall(r"(\w+)\s*=\s*(?:'((?:[^']|'')*)'|(\w+))", set_clause)
        for field, str_val, other_val in set_patterns:
            field_upper = field.upper()
            val = str_val if str_val else other_val
            if val:
                val = val.replace("''", "'")
            values[field_upper] = val

        # Extract WHERE key
        key = None

        # Try ID-based WHERE
        id_match = re.search(r'(\w+_ID)\s*=\s*(\d+)', where_clause, re.IGNORECASE)
        if id_match:
            key = ('ID', id_match.group(2))

        # Try ColumnName WHERE (for AD_Element)
        col_match = re.search(r"ColumnName\s*=\s*'([^']+)'", where_clause, re.IGNORECASE)
        if col_match:
            key = ('ColumnName', col_match.group(1))

        # Try Name WHERE (for AD_Window)
        name_match = re.search(r"(?<!\w)Name\s*=\s*'([^']+)'", where_clause, re.IGNORECASE)
        if name_match and not key:
            key = ('Name', name_match.group(1))

        if key and values:
            if table == 'AD_ELEMENT':
                if key[0] == 'ColumnName':
                    updates['AD_Element'][key[1]] = values
            elif table == 'AD_WINDOW':
                if key[0] == 'Name':
                    updates['AD_Window'][('Name', key[1])] = values
                elif key[0] == 'ID':
                    updates['AD_Window'][('ID', key[1])] = values
            elif table == 'AD_TAB':
                if key[0] == 'ID':
                    updates['AD_Tab'][key[1]] = values
                elif key[0] == 'Name':
                    # Tab by name - need window context, store for matching
                    updates['AD_Tab'][('Name', key[1])] = values
            elif table == 'AD_FIELD':
                if key[0] == 'ID':
                    updates['AD_Field'][key[1]] = values
            elif table == 'AD_PROCESS':
                if key[0] == 'ID':
                    updates['AD_Process'][key[1]] = values
            elif table == 'AD_FORM':
                if key[0] == 'ID':
                    updates['AD_Form'][key[1]] = values
            elif table == 'AD_MENU':
                if key[0] == 'ID':
                    updates['AD_Menu'][key[1]] = values

# Parse all SQL files
for sql_file in sql_files:
    print(f"  Parsing: {os.path.basename(sql_file)}")
    parse_sql_file(sql_file)

print(f"\nExtracted updates:")
for table, items in updates.items():
    if items:
        print(f"  {table}: {len(items)}")

# Load PackOut.xml
print(f"\nReading PackOut.xml...")
tree = ET.parse(input_xml)
root = tree.getroot()

def update_element(elem, values):
    """Update element's child tags with values"""
    updated = False
    for field, value in values.items():
        child = elem.find(field.capitalize())
        if child is None:
            child = elem.find(field)
        if child is None:
            # Try common field name variations
            for variant in [field, field.capitalize(), field.lower()]:
                child = elem.find(variant)
                if child is not None:
                    break
        if child is not None and value is not None:
            child.text = value
            updated = True
    return updated

counts = {k: 0 for k in updates.keys()}

# Apply updates to AD_Element
print("Applying to AD_Elements...")
for elem in root.iter('AD_Element'):
    col_elem = elem.find('ColumnName')
    if col_elem is not None and col_elem.text in updates['AD_Element']:
        if update_element(elem, updates['AD_Element'][col_elem.text]):
            counts['AD_Element'] += 1

# Apply updates to AD_Window
print("Applying to AD_Windows...")
for elem in root.iter('AD_Window'):
    # Try by ID first
    id_elem = elem.find('AD_Window_ID')
    if id_elem is not None and ('ID', id_elem.text) in updates['AD_Window']:
        if update_element(elem, updates['AD_Window'][('ID', id_elem.text)]):
            counts['AD_Window'] += 1
            continue
    # Try by Name
    name_elem = elem.find('Name')
    if name_elem is not None and ('Name', name_elem.text) in updates['AD_Window']:
        if update_element(elem, updates['AD_Window'][('Name', name_elem.text)]):
            counts['AD_Window'] += 1

# Apply updates to AD_Tab
print("Applying to AD_Tabs...")
for elem in root.iter('AD_Tab'):
    # Try by ID
    id_elem = elem.find('AD_Tab_ID')
    if id_elem is not None and id_elem.text in updates['AD_Tab']:
        if update_element(elem, updates['AD_Tab'][id_elem.text]):
            counts['AD_Tab'] += 1
            continue
    # Try by Name
    name_elem = elem.find('Name')
    if name_elem is not None and ('Name', name_elem.text) in updates['AD_Tab']:
        if update_element(elem, updates['AD_Tab'][('Name', name_elem.text)]):
            counts['AD_Tab'] += 1

# Apply updates to AD_Field
print("Applying to AD_Fields...")
for elem in root.iter('AD_Field'):
    id_elem = elem.find('AD_Field_ID')
    if id_elem is not None and id_elem.text in updates['AD_Field']:
        if update_element(elem, updates['AD_Field'][id_elem.text]):
            counts['AD_Field'] += 1

# Apply updates to AD_Process
print("Applying to AD_Processes...")
for elem in root.iter('AD_Process'):
    id_elem = elem.find('AD_Process_ID')
    if id_elem is not None and id_elem.text in updates['AD_Process']:
        if update_element(elem, updates['AD_Process'][id_elem.text]):
            counts['AD_Process'] += 1

# Apply updates to AD_Form
print("Applying to AD_Forms...")
for elem in root.iter('AD_Form'):
    id_elem = elem.find('AD_Form_ID')
    if id_elem is not None and id_elem.text in updates['AD_Form']:
        if update_element(elem, updates['AD_Form'][id_elem.text]):
            counts['AD_Form'] += 1

# Apply updates to AD_Menu
print("Applying to AD_Menus...")
for elem in root.iter('AD_Menu'):
    id_elem = elem.find('AD_Menu_ID')
    if id_elem is not None and id_elem.text in updates['AD_Menu']:
        if update_element(elem, updates['AD_Menu'][id_elem.text]):
            counts['AD_Menu'] += 1

# Write output
print(f"\nWriting: {output_xml}")
tree.write(output_xml, encoding='UTF-8', xml_declaration=True)

print(f"\nApplied updates:")
total = 0
for table, count in counts.items():
    if count > 0:
        print(f"  {table}: {count}")
        total += count
print(f"Total: {total}")
print("Done!")
