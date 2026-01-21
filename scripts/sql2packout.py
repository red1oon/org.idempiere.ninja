#!/usr/bin/env python3
"""
Apply SQL UPDATE statements to PackOut.xml directly (no database needed).
Uses simple string replacement to preserve original XML formatting.

Usage:
    python3 sql2packout.py <packout.xml> <sql_file_or_dir> [output.xml]
"""
import re
import sys
import os
import glob

if len(sys.argv) < 3:
    print(__doc__)
    sys.exit(1)

input_xml = sys.argv[1]
sql_input = sys.argv[2]
output_xml = sys.argv[3] if len(sys.argv) > 3 else input_xml

# Collect SQL files
sql_files = []
if os.path.isdir(sql_input):
    sql_files = sorted(glob.glob(os.path.join(sql_input, "*.sql")))
else:
    sql_files = [sql_input]

print(f"PackOut: {input_xml}")
print(f"SQL files: {len(sql_files)}")

# Parse SQL to extract updates
# Structure: updates[table][key_type][key_value] = {field: value, ...}
updates = {}

def parse_sql(filepath):
    with open(filepath, 'r') as f:
        content = f.read()
    content = re.sub(r'--.*$', '', content, flags=re.MULTILINE)

    for stmt in re.split(r';\s*\n', content):
        stmt = stmt.strip()
        if not stmt.upper().startswith('UPDATE'):
            continue

        m = re.match(r'UPDATE\s+(\w+)\s+SET\s+(.+?)\s+WHERE\s+(.+)', stmt, re.I | re.DOTALL)
        if not m:
            continue

        table, set_clause, where_clause = m.groups()
        table = table.upper()

        # Get SET values
        vals = {}
        for field, val in re.findall(r"(\w+)\s*=\s*'((?:[^']|'')*)'", set_clause):
            vals[field] = val.replace("''", "'")

        if not vals:
            continue

        # Get WHERE key - check ColumnName FIRST before ID patterns
        # (AD_Client_ID=0 appears in WHERE but is not the lookup key)
        key_type = key_val = None
        if m2 := re.search(r"ColumnName\s*=\s*'([^']+)'", where_clause, re.I):
            key_type, key_val = 'ColumnName', m2.group(1)
        elif m2 := re.search(r"(AD_(?:Element|Window|Tab|Field|Process|Form|Menu)_ID)\s*=\s*(\d+)", where_clause, re.I):
            key_type, key_val = m2.group(1), m2.group(2)
        elif m2 := re.search(r"(?<![A-Za-z_])Name\s*=\s*'([^']+)'", where_clause, re.I):
            key_type, key_val = 'Name', m2.group(1)

        if key_type and key_val:
            if table not in updates:
                updates[table] = {}
            if key_type not in updates[table]:
                updates[table][key_type] = {}
            # Merge values (later files override earlier)
            if key_val not in updates[table][key_type]:
                updates[table][key_type][key_val] = {}
            updates[table][key_type][key_val].update(vals)

for f in sql_files:
    print(f"  Parsing: {os.path.basename(f)}")
    parse_sql(f)

# Count rules
total_rules = sum(len(keys) for table in updates.values() for keys in table.values())
print(f"\nExtracted {total_rules} update rules")

# Read file as lines
with open(input_xml, 'r') as f:
    lines = f.readlines()

def get_tag_value(line, tag):
    """Get tag value from a line"""
    m = re.search(f'<{tag}>([^<]*)</{tag}>', line)
    if m:
        return m.group(1)
    # Handle self-closing with optional whitespace: <tag/> or <tag />
    if re.search(f'<{tag}\\s*/>', line):
        return ''
    return None

def set_tag_value(line, tag, value):
    """Set tag value in a line, preserving format"""
    # Replace <tag>old</tag>
    new_line = re.sub(f'<{tag}>[^<]*</{tag}>', f'<{tag}>{value}</{tag}>', line)
    if new_line != line:
        return new_line
    # Replace <tag/> or <tag /> (with optional whitespace)
    new_line = re.sub(f'<{tag}\\s*/>', f'<{tag}>{value}</{tag}>', line)
    return new_line

counts = {}

# Process line by line, tracking element context
i = 0
while i < len(lines):
    line = lines[i]

    # Check for XML element start tags we care about
    # Must match '<AD_Element ' or '<AD_Element>' but NOT '<AD_Element_ID>'
    for xml_elem in ['AD_Element', 'AD_Window', 'AD_Tab', 'AD_Field', 'AD_Process', 'AD_Form', 'AD_Menu']:
        if not (f'<{xml_elem} ' in line or f'<{xml_elem}>' in line) or f'</{xml_elem}>' in line:
            continue

        table = xml_elem.upper()
        if table not in updates:
            continue

        # Find the end of this element (AD_Window can span 5000+ lines)
        elem_start = i
        elem_end = i
        depth = 1
        for j in range(i + 1, min(i + 50000, len(lines))):
            # Check for nested element start (must be proper start tag, not _ID etc)
            if (f'<{xml_elem} ' in lines[j] or f'<{xml_elem}>' in lines[j]) and f'</{xml_elem}>' not in lines[j]:
                depth += 1
            if f'</{xml_elem}>' in lines[j]:
                depth -= 1
                if depth == 0:
                    elem_end = j
                    break

        # Collect all tag values in this element
        elem_tags = {}
        entity_type = None
        for j in range(elem_start, elem_end + 1):
            # Check EntityType
            et = get_tag_value(lines[j], 'EntityType')
            if et is not None:
                entity_type = et
            for tag in ['ColumnName', 'Name', 'AD_Element_ID', 'AD_Window_ID', 'AD_Tab_ID',
                       'AD_Field_ID', 'AD_Process_ID', 'AD_Form_ID', 'AD_Menu_ID']:
                val = get_tag_value(lines[j], tag)
                if val is not None:
                    elem_tags[tag] = (j, val)

        # Skip core dictionary elements (EntityType='D')
        if entity_type == 'D':
            i = elem_end
            break

        # Try to match this element with ALL update rules (don't break after first)
        matched = False
        for key_type, key_updates in updates[table].items():
            if key_type in elem_tags:
                line_idx, key_val = elem_tags[key_type]
                if key_val in key_updates:
                    # Apply all field updates for this match
                    vals_to_apply = key_updates[key_val]
                    for field, new_val in vals_to_apply.items():
                        # Find the field line within this element
                        for j in range(elem_start, elem_end + 1):
                            old_val = get_tag_value(lines[j], field)
                            if old_val is not None:
                                new_line = set_tag_value(lines[j], field, new_val)
                                if new_line != lines[j]:
                                    lines[j] = new_line
                                    counts[xml_elem] = counts.get(xml_elem, 0) + 1
                                break
                    matched = True
                    # Don't break - continue checking other key types

        if matched:
            i = elem_end  # Skip to end of this element
        break

    i += 1

# Write output
print(f"\nWriting: {output_xml}")
with open(output_xml, 'w') as f:
    f.writelines(lines)

print(f"\nApplied field updates:")
total = sum(counts.values())
for t, c in sorted(counts.items()):
    print(f"  {t}: {c}")
print(f"Total: {total}")
print("Done!")
