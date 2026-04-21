#!/usr/bin/env bash
# Minimal ingest-tx benchmark for git bisect
# Runs ingestTxOverhead with small doc count, outputs elapsed ms
# Usage: ./bisect-bench.sh
# Exit codes: 0 = good (fast), 1 = bad (slow), 125 = skip (build failure)

set -euo pipefail

THRESHOLD_MS="${THRESHOLD_MS:-0}" # set externally for bisect
DOC_COUNT="${DOC_COUNT:-5000}"
BATCH_SIZE="${BATCH_SIZE:-100}"
LOG_FILE="bisect-bench-result.log"

echo "=== Building and running ingestTxOverhead (docs=$DOC_COUNT, batch=$BATCH_SIZE) ==="

# Run the benchmark, capturing output
if ! ./gradlew ingestTxOverhead \
    -PdocCount="$DOC_COUNT" \
    -PbatchSizes="$BATCH_SIZE" \
    --no-daemon \
    2>&1 | tee "$LOG_FILE"; then
    echo "BUILD/RUN FAILED - marking as skip"
    exit 125
fi

# Extract the time-taken-ms from the summary line
ELAPSED=$(grep -o '"time-taken-ms":[0-9]*' "$LOG_FILE" | tail -1 | grep -o '[0-9]*$')

if [ -z "$ELAPSED" ]; then
    echo "Could not extract elapsed time from output"
    exit 125
fi

echo ""
echo "========================================="
echo "RESULT: ${ELAPSED}ms (batch=$BATCH_SIZE, docs=$DOC_COUNT)"
echo "========================================="

if [ "$THRESHOLD_MS" -gt 0 ]; then
    if [ "$ELAPSED" -gt "$THRESHOLD_MS" ]; then
        echo "SLOW: ${ELAPSED}ms > threshold ${THRESHOLD_MS}ms -> bad"
        exit 1
    else
        echo "FAST: ${ELAPSED}ms <= threshold ${THRESHOLD_MS}ms -> good"
        exit 0
    fi
fi
