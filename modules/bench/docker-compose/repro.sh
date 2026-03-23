#!/usr/bin/env bash
set -euo pipefail

# Reproduces: leader transition with outdated resume offset
# When nodes join a Kafka consumer group staggered, rebalances cause the
# leader to resume from an outdated source offset, replaying transactions
# the Watchers have already seen.
#
# Requires: docker, gradle wrapper (run from repo root)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yaml"

cleanup() {
  echo ""
  echo "=== Cleaning up ==="
  docker compose -f "$COMPOSE_FILE" down -v --remove-orphans 2>/dev/null || true
}
trap cleanup EXIT

echo "=== Building bench jar ==="
cd "$REPO_ROOT"
java -jar gradle/wrapper/gradle-wrapper.jar :modules:bench:shadowJar -q

echo "=== Building Docker image ==="
docker build -t xtdb-bench:local -f modules/bench/Dockerfile . -q

echo "=== Starting infrastructure (Kafka + MinIO) ==="
docker compose -f "$COMPOSE_FILE" up -d kafka minio minio-setup
echo "Waiting for Kafka and MinIO..."
for i in $(seq 1 60); do
  if docker compose -f "$COMPOSE_FILE" exec -T kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1; then
    echo "Kafka ready."
    break
  fi
  sleep 2
done
sleep 5  # let minio-setup finish

echo ""
echo "=== Starting node-1 (leader) ==="
docker compose -f "$COMPOSE_FILE" up -d xtdb-node-1 2>/dev/null

echo "Letting node-1 become leader and start loading data (15s)..."
sleep 15

echo ""
echo "=== Starting node-2 (triggers rebalance) ==="
docker compose -f "$COMPOSE_FILE" up -d xtdb-node-2 2>/dev/null

echo "Waiting for rebalance to cause the crash (30s)..."
sleep 30

echo ""
echo "=== Starting node-3 (triggers another rebalance) ==="
docker compose -f "$COMPOSE_FILE" up -d xtdb-node-3 2>/dev/null

echo "Waiting for things to settle (60s)..."
sleep 60

echo ""
echo "============================================================"
echo "=== RESULTS ==="
echo "============================================================"
echo ""

for node in xtdb-node-1 xtdb-node-2 xtdb-node-3; do
  echo "--- $node ---"
  docker compose -f "$COMPOSE_FILE" logs "$node" 2>&1 \
    | grep -E "starting follower|partitions (assigned|revoked)|leader startup|IllegalState|srcMsgId|latestTxId|Benchmark failed|Timed out" \
    | sed "s/^[^ ]* | //" \
    | head -15
  echo ""
done

echo "============================================================"
echo ""

# Check if any node hit the bug
if docker compose -f "$COMPOSE_FILE" logs 2>&1 | grep -qE "<=.*latestTxId|<.*latestSourceMsgId"; then
  echo "BUG REPRODUCED: leader transition resumed from outdated offset"
  echo ""
  docker compose -f "$COMPOSE_FILE" logs 2>&1 | grep -E "<=.*latestTxId|<.*latestSourceMsgId" | sed "s/^[^ ]* | //"
  exit 1
else
  echo "Bug did not reproduce this run (race condition — try again)"
  exit 0
fi
