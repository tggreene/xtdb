#!/usr/bin/env bash
set -euo pipefail

# Benchmark ingest-tx with Kafka, matching cloud config
# Usage: ./bench-kafka.sh <commit> [doc-count] [batch-sizes] [concurrency] [async] [latency-ms]

COMMIT="${1:?Usage: ./bench-kafka.sh <commit> [doc-count] [batch-sizes] [concurrency] [async] [latency-ms] [linger-ms]}"
DOC_COUNT="${2:-10000}"
BATCH_SIZES="${3:-1}"
CONCURRENCY="${4:-1}"
ASYNC="${5:-}"
LATENCY_MS="${6:-0}"
LINGER_MS="${7:-0}"
REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"

COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"
WORKTREE="/tmp/xtdb-bench-kafka-$$"
NETEM_CONTAINER="xtdb-bench-netem-$$"

cleanup() {
    echo "=== Cleaning up ==="
    docker stop "$NETEM_CONTAINER" 2>/dev/null || true
    docker compose -f "$COMPOSE_FILE" down -v --remove-orphans 2>/dev/null || true
    git -C "$REPO_ROOT" worktree remove "$WORKTREE" --force 2>/dev/null || true
}
trap cleanup EXIT

echo "=== Setting up worktree at $COMMIT ==="
git -C "$REPO_ROOT" worktree add "$WORKTREE" "$COMMIT" --detach

echo "=== Starting Kafka ==="
docker compose -f "$COMPOSE_FILE" up -d kafka
echo "Waiting for Kafka..."
for i in $(seq 1 30); do
    if docker compose -f "$COMPOSE_FILE" exec -T kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1; then
        echo "Kafka ready."
        break
    fi
    sleep 2
done

# Apply network latency via an alpine sidecar sharing kafka's net namespace
if [ "$LATENCY_MS" -gt 0 ]; then
    echo "=== Injecting ${LATENCY_MS}ms latency on kafka eth0 ==="
    docker run -d --rm \
        --name "$NETEM_CONTAINER" \
        --net=container:xtdb-kafka-1 \
        --cap-add=NET_ADMIN \
        alpine:latest sh -c "apk add --no-cache iproute2 >/dev/null && tc qdisc add dev eth0 root netem delay ${LATENCY_MS}ms && sleep 3600" >/dev/null
    # Wait for the tc rule to be applied (apk install takes a moment)
    for i in $(seq 1 20); do
        if docker logs "$NETEM_CONTAINER" 2>&1 | grep -q "^$" || [ $i -eq 20 ]; then
            sleep 1
            break
        fi
        sleep 0.5
    done
    sleep 2
    echo "Latency injection ready."
fi

# Write a Kafka node config (linger.ms set via propertiesMap to override XTDB default)
KAFKA_CONFIG="$WORKTREE/bench-kafka-config.yaml"
cat > "$KAFKA_CONFIG" <<YAML
server:
  host: '*'
  port: 5432

logClusters:
  kafkaCluster: !Kafka
    bootstrapServers: localhost:9092
    propertiesMap:
      max.request.size: "5242880"
      linger.ms: "${LINGER_MS}"

log: !Kafka
  cluster: kafkaCluster
  topic: bench-ingest-tx

storage: !Local
  path: /tmp/xtdb-bench-storage
YAML

echo "=== Building $COMMIT ==="
cd "$WORKTREE"
./gradlew :modules:bench:assemble --no-daemon -q 2>&1 | tail -5

echo ""
ASYNC_FLAG=""
if [ -n "$ASYNC" ]; then
    ASYNC_FLAG="-Pasync=true"
fi

echo "=== Running benchmark (docs=$DOC_COUNT, batch=$BATCH_SIZES, concurrency=$CONCURRENCY, async=$ASYNC, latency=${LATENCY_MS}ms, linger=${LINGER_MS}ms) ==="
./gradlew ingestTxOverhead \
    -PdocCount="$DOC_COUNT" \
    -PbatchSizes="$BATCH_SIZES" \
    -Pconcurrency="$CONCURRENCY" \
    $ASYNC_FLAG \
    -PconfigFile="$KAFKA_CONFIG" \
    --no-daemon 2>&1 | tee "/tmp/bench-kafka-${COMMIT:0:10}.log"

echo ""
echo "=== Extracting results ==="
grep '"time-taken-ms"' "/tmp/bench-kafka-${COMMIT:0:10}.log" | python3 -c "
import json, sys
for line in sys.stdin:
    data = json.loads(line)
    if 'stage' in data and data['stage'] != 'init':
        stage = data.get('stage', '?')
        ms = data.get('time-taken-ms', 0)
        print(f'{stage:25s}  {ms:>8.0f} ms  ({ms/1000:.1f}s)')
"
