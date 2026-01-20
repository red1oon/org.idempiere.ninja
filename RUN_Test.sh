#!/bin/bash
#
# RUN_Test.sh - Run Ninja Test Suite
#
# Step-by-step testing from simple to complex:
#   Level 1: Basic - File and JDBC connection
#   Level 2: Excel - Parse XLS/XLSX model structure
#   Level 3: Schema - Validate against DB (AD_Table, references)
#   Level 4-6: Require OSGi (run from Eclipse)
#
# Usage:
#   ./RUN_Test.sh <xls-file> [level] [loglevel]
#
# Examples:
#   ./RUN_Test.sh templates/Ninja_HRMIS.xlsx
#   ./RUN_Test.sh templates/Ninja_HRMIS.xlsx 2
#   ./RUN_Test.sh templates/Ninja_HRMIS.xlsx 6 FINE
#

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
M2="${HOME}/.m2/repository"

# Check args
if [ $# -lt 1 ]; then
    echo "Ninja Test Suite"
    echo "================"
    echo ""
    echo "Usage: $0 <xls-file> [level] [loglevel]"
    echo ""
    echo "Test Levels:"
    echo "  1  Basic   - File and JDBC connection"
    echo "  2  Excel   - Parse XLS/XLSX structure"
    echo "  3  Model   - iDempiere model layer (requires OSGi)"
    echo "  4  Inject  - Full injection with rollback"
    echo "  5  CRUD    - Create/Read/Update/Delete"
    echo "  6  BlackBox- GardenWorld sample data validation"
    echo ""
    echo "Log Levels: SEVERE|WARNING|INFO|FINE|FINER|FINEST"
    echo ""
    echo "Examples:"
    echo "  $0 templates/Ninja_HRMIS.xlsx"
    echo "  $0 templates/Ninja_HRMIS.xlsx 2"
    echo "  $0 templates/Ninja_HRMIS.xlsx 6 FINE"
    exit 1
fi

# Build classpath
CP="$SCRIPT_DIR/bin"

# PostgreSQL driver
PGJAR="$M2/org/postgresql/postgresql/42.7.8/postgresql-42.7.8.jar"
if [ -f "$PGJAR" ]; then
    CP="$CP:$PGJAR"
else
    # Try to find any PostgreSQL driver
    PGJAR=$(find "$M2" -name "postgresql*.jar" -not -name "*sources*" 2>/dev/null | head -1)
    [ -n "$PGJAR" ] && CP="$CP:$PGJAR"
fi

# POI libraries for Excel parsing (with correct versions for POI 5.2.2)
POI_JARS=(
    "$M2/org/apache/poi/poi/5.2.2/poi-5.2.2.jar"
    "$M2/org/apache/poi/poi-ooxml/5.2.2/poi-ooxml-5.2.2.jar"
    "$M2/org/apache/poi/poi-ooxml-lite/5.2.2/bnd-fd0891a26b67203f2dde44003aef69db/poi-ooxml-lite-5.2.2.jar"
    "$M2/commons-io/commons-io/2.16.1/commons-io-2.16.1.jar"
    "$M2/org/apache/commons/commons-compress/1.26.1/commons-compress-1.26.1.jar"
    "$M2/org/apache/xmlbeans/xmlbeans/5.0.3/xmlbeans-5.0.3.jar"
    "$M2/org/apache/logging/log4j/log4j-api/2.17.2/log4j-api-2.17.2.jar"
    "$M2/org/apache/commons/commons-collections4/4.4/commons-collections4-4.4.jar"
)

for jar in "${POI_JARS[@]}"; do
    if [ -f "$jar" ]; then
        CP="$CP:$jar"
    fi
done

# Also check local lib folder
if [ -d "$SCRIPT_DIR/lib" ]; then
    for jar in "$SCRIPT_DIR"/lib/*.jar; do
        [ -f "$jar" ] && CP="$CP:$jar"
    done
fi

# Check if compiled
if [ ! -f "$SCRIPT_DIR/bin/org/idempiere/ninja/test/NinjaTestSuite.class" ]; then
    echo "Test classes not compiled. Compiling..."
    javac -d "$SCRIPT_DIR/bin" "$SCRIPT_DIR/src/org/idempiere/ninja/test/NinjaTestSuite.java"
    if [ $? -ne 0 ]; then
        echo "Compilation failed!"
        exit 1
    fi
fi

# Run test suite
java -cp "$CP" \
    org.idempiere.ninja.test.NinjaTestSuite \
    "$@"

exit $?
