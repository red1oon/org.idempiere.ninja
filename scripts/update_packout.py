#!/usr/bin/env python3
"""
Update PackOut.xml with current database Help/Name values.

Usage:
    python3 update_packout.py <packout.xml> [output.xml]

If output is not specified, updates in-place.

Examples:
    python3 update_packout.py PreventiveMaintenance/dict/PackOut.xml
    python3 update_packout.py input.xml output.xml
"""
import xml.etree.ElementTree as ET
import psycopg2
import sys
import os

def print_usage():
    print(__doc__)
    sys.exit(1)

# Check args
if len(sys.argv) < 2:
    print_usage()

input_file = sys.argv[1]
output_file = sys.argv[2] if len(sys.argv) > 2 else input_file

if not os.path.exists(input_file):
    print(f"Error: File not found: {input_file}")
    sys.exit(1)

# Connect to database
print(f"Connecting to database...")
conn = psycopg2.connect(
    host="localhost",
    database="idempiere",
    user="adempiere",
    password="adempiere"
)
cur = conn.cursor()

# Parse PackOut.xml
print(f"Reading: {input_file}")
tree = ET.parse(input_file)
root = tree.getroot()

def update_text(elem, tag, value):
    """Update or create a child element's text"""
    child = elem.find(tag)
    if child is not None:
        child.text = value if value else None
    return child is not None

counts = {'element': 0, 'window': 0, 'tab': 0, 'process': 0, 'form': 0, 'menu': 0, 'field': 0}

# Update AD_Element entries
print("Updating AD_Elements...")
for elem in root.iter('AD_Element'):
    col_elem = elem.find('ColumnName')
    if col_elem is not None and col_elem.text:
        colname = col_elem.text
        cur.execute("""
            SELECT Name, PrintName, Help, Description
            FROM AD_Element
            WHERE ColumnName = %s AND AD_Client_ID = 0
        """, (colname,))
        row = cur.fetchone()
        if row:
            name, printname, help_text, desc = row
            update_text(elem, 'Name', name)
            update_text(elem, 'PrintName', printname)
            update_text(elem, 'Help', help_text)
            update_text(elem, 'Description', desc)
            counts['element'] += 1

# Update AD_Window entries
print("Updating AD_Windows...")
for elem in root.iter('AD_Window'):
    win_id_elem = elem.find('AD_Window_ID')
    if win_id_elem is not None and win_id_elem.text:
        try:
            win_id = int(win_id_elem.text)
            cur.execute("""
                SELECT Name, Help, Description
                FROM AD_Window
                WHERE AD_Window_ID = %s
            """, (win_id,))
            row = cur.fetchone()
            if row:
                name, help_text, desc = row
                update_text(elem, 'Name', name)
                update_text(elem, 'Help', help_text)
                update_text(elem, 'Description', desc)
                counts['window'] += 1
        except:
            pass

# Update AD_Tab entries
print("Updating AD_Tabs...")
for elem in root.iter('AD_Tab'):
    tab_id_elem = elem.find('AD_Tab_ID')
    if tab_id_elem is not None and tab_id_elem.text:
        try:
            tab_id = int(tab_id_elem.text)
            cur.execute("""
                SELECT Name, Help, Description
                FROM AD_Tab
                WHERE AD_Tab_ID = %s
            """, (tab_id,))
            row = cur.fetchone()
            if row:
                name, help_text, desc = row
                update_text(elem, 'Name', name)
                update_text(elem, 'Help', help_text)
                update_text(elem, 'Description', desc)
                counts['tab'] += 1
        except:
            pass

# Update AD_Process entries
print("Updating AD_Processes...")
for elem in root.iter('AD_Process'):
    proc_id_elem = elem.find('AD_Process_ID')
    if proc_id_elem is not None and proc_id_elem.text:
        try:
            proc_id = int(proc_id_elem.text)
            cur.execute("""
                SELECT Name, Help, Description
                FROM AD_Process
                WHERE AD_Process_ID = %s
            """, (proc_id,))
            row = cur.fetchone()
            if row:
                name, help_text, desc = row
                update_text(elem, 'Name', name)
                update_text(elem, 'Help', help_text)
                update_text(elem, 'Description', desc)
                counts['process'] += 1
        except:
            pass

# Update AD_Form entries
print("Updating AD_Forms...")
for elem in root.iter('AD_Form'):
    form_id_elem = elem.find('AD_Form_ID')
    if form_id_elem is not None and form_id_elem.text:
        try:
            form_id = int(form_id_elem.text)
            cur.execute("""
                SELECT Name, Help, Description
                FROM AD_Form
                WHERE AD_Form_ID = %s
            """, (form_id,))
            row = cur.fetchone()
            if row:
                name, help_text, desc = row
                update_text(elem, 'Name', name)
                update_text(elem, 'Help', help_text)
                update_text(elem, 'Description', desc)
                counts['form'] += 1
        except:
            pass

# Update AD_Menu entries
print("Updating AD_Menus...")
for elem in root.iter('AD_Menu'):
    menu_id_elem = elem.find('AD_Menu_ID')
    if menu_id_elem is not None and menu_id_elem.text:
        try:
            menu_id = int(menu_id_elem.text)
            cur.execute("""
                SELECT Name, Description
                FROM AD_Menu
                WHERE AD_Menu_ID = %s
            """, (menu_id,))
            row = cur.fetchone()
            if row:
                name, desc = row
                update_text(elem, 'Name', name)
                update_text(elem, 'Description', desc)
                counts['menu'] += 1
        except:
            pass

# Update AD_Field entries (IsCentrallyMaintained and Help)
print("Updating AD_Fields...")
for elem in root.iter('AD_Field'):
    field_id_elem = elem.find('AD_Field_ID')
    if field_id_elem is not None and field_id_elem.text:
        try:
            field_id = int(field_id_elem.text)
            cur.execute("""
                SELECT Name, Help, Description, IsCentrallyMaintained
                FROM AD_Field
                WHERE AD_Field_ID = %s
            """, (field_id,))
            row = cur.fetchone()
            if row:
                name, help_text, desc, is_central = row
                update_text(elem, 'Name', name)
                update_text(elem, 'Help', help_text)
                update_text(elem, 'Description', desc)
                update_text(elem, 'IsCentrallyMaintained', is_central)
                counts['field'] += 1
        except:
            pass

# Write updated XML
print(f"Writing: {output_file}")
tree.write(output_file, encoding='UTF-8', xml_declaration=True)

cur.close()
conn.close()

print(f"\nUpdated: {sum(counts.values())} elements")
for k, v in counts.items():
    if v > 0:
        print(f"  {k}: {v}")
print("Done!")
