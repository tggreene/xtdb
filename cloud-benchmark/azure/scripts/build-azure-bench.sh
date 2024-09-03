#!/usr/bin/env bash

set -e

# Use the first argument passed in, or default to 'xtdb-azure-bench'
image_name=${1:-xtdb-azure-bench}

(
    ../../gradlew :cloud-benchmark:azure:shadowJar

    sha=$(git rev-parse --short HEAD)

    echo Building Docker image ...
    docker build -t "$image_name":latest --platform=linux/amd64 --build-arg GIT_SHA="$sha" --build-arg XTDB_VERSION="${XTDB_VERSION:-dev-SNAPSHOT}" .
    echo Done
)
