#!/usr/bin/env bash
set -euo pipefail

# Reproduces the glibc malloc arena fragmentation OOM.
#
# glibc retains freed pages in per-thread arenas instead of returning
# them to the OS. Inside a cgroup (Docker --memory), this causes
# monotonic anon growth until OOM kill.
#
# This script runs the fusion benchmark in two phases:
#   1. Load phase: ingest data into a volume-mounted node dir (skipped if already loaded)
#   2. Query stress phase: repeatedly run queries that trigger the OOM
#
# To test fixes, add env vars to the query phase:
#   jemalloc:              -e LD_PRELOAD=libjemalloc.so.2
#   MALLOC_MMAP_THRESHOLD: -e MALLOC_MMAP_THRESHOLD_=65536
#   MALLOC_ARENA_MAX:      -e MALLOC_ARENA_MAX=2

IMAGE="xtdb-bench:fusion-oom"
VOLUME="fusion-oom-data"
MEMORY="10g"
DEVICES=10000
READINGS=1000

JDK_OPTS="-Xmx3000m -Xms3000m -XX:MaxDirectMemorySize=5000m -XX:MaxMetaspaceSize=500m -XX:+HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED"

echo "=== Building shadowJar ==="
./gradlew :modules:bench:shadowJar

echo "=== Building Docker image ==="
docker build -t "$IMAGE" -f modules/bench/Dockerfile .

echo "=== Creating volume ==="
docker volume create "$VOLUME" 2>/dev/null || true

# Check if data has already been loaded by looking for the node dir in the volume
DATA_EXISTS=$(docker run --rm --entrypoint bash -v "$VOLUME":/opt/xtdb/bench-data "$IMAGE" -c \
  'test -d /opt/xtdb/bench-data/node && echo "yes" || echo "no"' 2>/dev/null || echo "no")

if [ "$DATA_EXISTS" = "yes" ]; then
  echo "=== Phase 1: SKIPPED (data already loaded in volume '$VOLUME') ==="
  echo "    To force reload: docker volume rm $VOLUME"
else
  echo "=== Phase 1: Load (ingest data + short OLTP to sync/compact) ==="
  docker run --rm \
    --name fusion-oom-load \
    -v "$VOLUME":/opt/xtdb/bench-data \
    --memory="$MEMORY" \
    --memory-swap="$MEMORY" \
    -e JDK_JAVA_OPTIONS="$JDK_OPTS" \
    "$IMAGE" fusion \
    --devices "$DEVICES" \
    --readings "$READINGS" \
    --node-dir /opt/xtdb/bench-data/node \
    -d PT1S
fi

echo "=== Phase 2: Query stress (watch anon grow until OOM) ==="
echo "    Memory limit: $MEMORY"
echo "    To cancel: docker stop fusion-oom-stress"
echo ""

docker run --rm \
  --name fusion-oom-stress \
  -v "$VOLUME":/opt/xtdb/bench-data \
  --memory="$MEMORY" \
  --memory-swap="$MEMORY" \
  -e JDK_JAVA_OPTIONS="$JDK_OPTS" \
  "$IMAGE" fusion \
  --devices "$DEVICES" \
  --readings "$READINGS" \
  --no-load \
  --node-dir /opt/xtdb/bench-data/node \
  --stress-procs query-registration \
  -t 1 \
  -d PT10M
