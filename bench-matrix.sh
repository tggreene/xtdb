#!/usr/bin/env bash
set -euo pipefail

# Run the linger.ms × latency × concurrency matrix.
# Each cell runs ./bench-kafka.sh and records ingest-batch-1 time-taken-ms.

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
RESULTS_FILE="${1:-/tmp/bench-matrix-$(date +%s).csv}"

DOCS=2000            # keep each cell short; scale down from 5000 to save time
BATCH=1
COMMIT="${COMMIT:-HEAD}"

LATENCIES=(0 10 25)
CONCURRENCIES=(1 10 50)
LINGERS=(0 5)

echo "latency_ms,concurrency,linger_ms,ingest_ms" > "$RESULTS_FILE"
echo "Results will be written to: $RESULTS_FILE"
echo

for latency in "${LATENCIES[@]}"; do
  for conc in "${CONCURRENCIES[@]}"; do
    for linger in "${LINGERS[@]}"; do
      echo ""
      echo "========================================================"
      echo "RUN: latency=${latency}ms, concurrency=${conc}, linger=${linger}ms"
      echo "========================================================"
      LOG="/tmp/bench-matrix-l${latency}-c${conc}-li${linger}.log"
      "$REPO_ROOT/bench-kafka.sh" "$COMMIT" "$DOCS" "$BATCH" "$conc" "" "$latency" "$linger" > "$LOG" 2>&1 || {
        echo "FAILED; skipping"
        echo "${latency},${conc},${linger},FAIL" >> "$RESULTS_FILE"
        continue
      }

      # Extract ingest-batch-1 time
      MS=$(grep -oE '"stage":"ingest-batch-1","time-taken-ms":[0-9]+' "$LOG" | head -1 | grep -oE '[0-9]+$' || echo "FAIL")
      echo "result: ${MS}ms"
      echo "${latency},${conc},${linger},${MS}" >> "$RESULTS_FILE"
    done
  done
done

echo ""
echo "========================================================"
echo "Matrix complete. Results:"
echo "========================================================"
cat "$RESULTS_FILE" | column -t -s,
