#!/bin/bash

# Run the Java CC Evaluator
# Usage: ./run_evaluator.sh [--correctness-only] [InitialProgram.java] [output.json]
#
# Arguments:
#   --correctness-only  - Optional flag to only output correctness metric (must be first)
#   InitialProgram.java - Path to the CC implementation file (default: InitialProgram.java)
#   output.json         - Path to output JSON file (default: output.json)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Parse arguments - check for --correctness-only flag first
CORRECTNESS_ONLY=""

if [ "$1" = "--correctness-only" ]; then
    CORRECTNESS_ONLY="--correctness-only"
    shift
fi

INPUT_FILE="${1:-InitialProgram.java}"
OUTPUT_FILE="${2:-output.json}"

# Check if input file exists
if [ ! -f "$INPUT_FILE" ]; then
    echo "Error: Input file '$INPUT_FILE' not found"
    exit 1
fi

# Use a temporary filename for the implementation source code
TEMP_CC_FILE="CCImplementationTemp.java"

# If input file is not 'CCImplementationTemp.java', copy it for compilation
INPUT_BASENAME=$(basename "$INPUT_FILE")
if [ "$INPUT_BASENAME" != "$TEMP_CC_FILE" ]; then
    cp "$INPUT_FILE" "$TEMP_CC_FILE"
else
    # Already the correct temp file
    :
fi

echo "Compiling Java files..."
javac -d . KVStore.java Concurrency.java "$TEMP_CC_FILE" Evaluator.java

if [ $? -ne 0 ]; then
    echo "Error: Compilation failed"
    rm -f "$TEMP_CC_FILE"
    exit 1
fi

echo "Running evaluator..."

# Run with 3-minute timeout (180 seconds) and kill process if it exceeds
TIMEOUT_SECONDS=180

# Disable set -e temporarily to capture exit code
set +e
timeout --kill-after=10 $TIMEOUT_SECONDS java -Xmx120g tests_java.Evaluator --output "$OUTPUT_FILE" $CORRECTNESS_ONLY
EXIT_CODE=$?
set -e

# Check if timeout killed the process (exit code 124 for timeout, 137 for SIGKILL)
if [ $EXIT_CODE -eq 124 ] || [ $EXIT_CODE -eq 137 ]; then
    echo "Error: Evaluator timed out after ${TIMEOUT_SECONDS} seconds and was killed"
    rm -f "$TEMP_CC_FILE"
    exit 1
fi

if [ $EXIT_CODE -eq 0 ]; then
    echo ""
    echo "Results:"
    cat "$OUTPUT_FILE"
else
    echo "Error: Evaluation failed"
    rm -f "$TEMP_CC_FILE"
    exit 1
fi

# Clean up the temporary copy if it was created here
if [ "$INPUT_BASENAME" != "$TEMP_CC_FILE" ]; then
    rm -f "$TEMP_CC_FILE"
fi


