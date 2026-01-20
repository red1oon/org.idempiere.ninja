#!/bin/bash
#
# RUN_Piper.sh - Run SilentPiper (SQLite Staging)
#
# Staging workflow for Excel → SQLite → iDempiere:
#   1. stage  - Parse Excel to SQLite (no iDempiere needed)
#   2. show   - Review staged models
#   3. apply  - Apply to iDempiere PostgreSQL
#   4. rollback - Deactivate applied records
#
# Usage:
#   ./RUN_Piper.sh <sqlite.db> <command> [args...]
#
# Examples:
#   ./RUN_Piper.sh ninja.db stage templates/Ninja_HRMIS.xlsx
#   ./RUN_Piper.sh ninja.db show
#   ./RUN_Piper.sh ninja.db show Ninja_HRMIS
#   ./RUN_Piper.sh ninja.db apply Ninja_HRMIS dryrun
#   ./RUN_Piper.sh ninja.db rollback Ninja_HRMIS
#   ./RUN_Piper.sh ninja.db history
#

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
M2="${HOME}/.m2/repository"

# Check args
if [ $# -lt 2 ]; then
    echo "SilentPiper - SQLite Staging for Ninja"
    echo "======================================"
    echo ""
    echo "Usage: $0 <sqlite.db> <command> [args...]"
    echo ""
    echo "STAGING WORKFLOW:"
    echo "  stage <excel.xlsx>       - Parse Excel to SQLite"
    echo "  show [bundle]            - Show staged models"
    echo "  apply <bundle> [dryrun]  - Apply to iDempiere"
    echo "  rollback <bundle>        - Rollback applied bundle"
    echo ""
    echo "2PACK COMMANDS:"
    echo "  import <2pack.zip>       - Import 2Pack to iDempiere"
    echo "  validate <2pack.zip>     - Validate 2Pack (dry run)"
    echo ""
    echo "HISTORY:"
    echo "  history [limit]          - Show operation history"
    echo ""
    echo "Examples:"
    echo "  $0 ninja.db stage templates/Ninja_HRMIS.xlsx"
    echo "  $0 ninja.db show"
    echo "  $0 ninja.db apply Ninja_HRMIS dryrun"
    exit 1
fi

# Build classpath
CP="$SCRIPT_DIR/bin"

# PostgreSQL driver
PGJAR="$M2/org/postgresql/postgresql/42.7.8/postgresql-42.7.8.jar"
if [ -f "$PGJAR" ]; then
    CP="$CP:$PGJAR"
else
    PGJAR=$(find "$M2" -name "postgresql*.jar" -not -name "*sources*" 2>/dev/null | head -1)
    [ -n "$PGJAR" ] && CP="$CP:$PGJAR"
fi

# SQLite driver
SQLITE_JAR="$M2/org/xerial/sqlite-jdbc/3.44.0.0/sqlite-jdbc-3.44.0.0.jar"
if [ ! -f "$SQLITE_JAR" ]; then
    # Try local lib
    SQLITE_JAR="$SCRIPT_DIR/lib/sqlite-jdbc-3.44.0.0.jar"
fi
[ -f "$SQLITE_JAR" ] && CP="$CP:$SQLITE_JAR"

# SLF4J (required by SQLite JDBC)
SLF4J_API="$SCRIPT_DIR/lib/slf4j-api-2.0.9.jar"
SLF4J_SIMPLE="$SCRIPT_DIR/lib/slf4j-simple-2.0.9.jar"
[ -f "$SLF4J_API" ] && CP="$CP:$SLF4J_API"
[ -f "$SLF4J_SIMPLE" ] && CP="$CP:$SLF4J_SIMPLE"

# POI libraries for Excel parsing
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
    [ -f "$jar" ] && CP="$CP:$jar"
done

# Local lib jars
if [ -d "$SCRIPT_DIR/lib" ]; then
    for jar in "$SCRIPT_DIR"/lib/*.jar; do
        [ -f "$jar" ] && CP="$CP:$jar"
    done
fi

# Source files
SRC_FILE="$SCRIPT_DIR/src/org/idempiere/ninja/piper/SilentPiper.java"
CLASS_FILE="$SCRIPT_DIR/bin/org/idempiere/ninja/piper/SilentPiper.class"

# Compile if needed
if [ ! -f "$CLASS_FILE" ] || [ "$SRC_FILE" -nt "$CLASS_FILE" ]; then
    echo "Compiling SilentPiper..."
    mkdir -p "$SCRIPT_DIR/bin"
    javac -cp "$CP" -d "$SCRIPT_DIR/bin" "$SRC_FILE"
    if [ $? -ne 0 ]; then
        echo "Compilation failed!"
        exit 1
    fi
fi

# Run SilentPiper
java -cp "$CP" \
    -DPropertyFile="${PROPERTY_FILE:-/home/red1/idempiere-dev-setup/idempiere/idempiere.properties}" \
    org.idempiere.ninja.piper.SilentPiper \
    "$@"

exit $?
