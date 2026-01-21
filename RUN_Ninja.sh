#!/bin/sh
#
# RUN_Ninja.sh - iDempiere Plugin Generator
# @author red1 - red1org@gmail.com
#
# Generate iDempiere plugins from Excel definitions
#

print_usage() {
    echo ""
    echo "Ninja Plugin Generator"
    echo "======================"
    echo ""
    echo "Usage: $0 <ExcelFile> [options]"
    echo ""
    echo "Modes (combine as needed):"
    echo "  -a    Inject AD models into database (requires iDempiere)"
    echo "  -b    Generate 2Pack.zip from Excel"
    echo "  -c    Create AD_Package_Exp records (requires iDempiere)"
    echo "  -d    Generate OSGI plugin structure"
    echo "  -t    TEST MODE: Inject with automatic rollback"
    echo ""
    echo "Options:"
    echo "  -o <dir>   Output directory (default: same as Excel)"
    echo "  -v         Verbose logging"
    echo ""
    echo "Examples:"
    echo "  $0 MyModule.xls              # All modes (default)"
    echo "  $0 MyModule.xls -b           # 2Pack only (no DB needed)"
    echo "  $0 MyModule.xls -bd          # 2Pack + Plugin structure"
    echo "  $0 MyModule.xls -a           # DB inject only"
    echo "  $0 MyModule.xls -abcd -v     # Everything, verbose"
    echo "  $0 MyModule.xls -b -o /tmp   # 2Pack to /tmp"
    echo "  $0 MyModule.xls -t           # TEST: inject + rollback"
    echo "  $0 MyModule.xls -tv          # TEST: verbose output"
    echo ""
    echo "Test Mode (-t) runs comprehensive tests:"
    echo "  - Verifies AD models created (Tables, Columns, Windows)"
    echo "  - Tests CRUD operations (Create, Read, Update, Delete)"
    echo "  - Tests Master-Detail relationships"
    echo "  - Tests 2Pack generation"
    echo "  - ALL CHANGES ARE ROLLED BACK (safe for production DB)"
    echo "  NOTE: AD_PInstance audit records persist after rollback"
    echo ""
}

if [ $# -lt 1 ]; then
    print_usage
    exit 1
fi

# Check if help requested
case "$1" in
    -h|--help|-?)
        print_usage
        exit 0
        ;;
esac

echo "Ninja Plugin Generator"
echo "Processing: $1"

./idempiere -application org.idempiere.ninja.NinjaApplication "$@"

exit $?
