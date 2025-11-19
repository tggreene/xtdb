#!/usr/bin/env bash
#
# Download crash artifacts from Azure Blob Storage
# Intended to be run from GitHub Actions to collect crash artifacts and upload as GitHub artifacts
#
# Usage: ./download-crash-artifacts.sh [output-dir]
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  cat << EOF
Usage: $0 [output-dir]

Download all crash artifacts from Azure Blob Storage.

ARGUMENTS:
  output-dir    Output directory (default: ./crash-artifacts)

ENVIRONMENT VARIABLES:
  AZURE_STORAGE_ACCOUNT      Azure storage account name
  AZURE_STORAGE_CONTAINER    Azure storage container name

EXAMPLES:
  # Download crash artifacts
  $0

  # Download to custom directory
  $0 /tmp/crashes
EOF
  exit 1
}

OUTPUT_DIR="${1:-./crash-artifacts}"

# Check required environment variables
if [ -z "${AZURE_STORAGE_ACCOUNT:-}" ]; then
  echo "Error: AZURE_STORAGE_ACCOUNT environment variable not set" >&2
  exit 1
fi

if [ -z "${AZURE_STORAGE_CONTAINER:-}" ]; then
  echo "Error: AZURE_STORAGE_CONTAINER environment variable not set" >&2
  exit 1
fi

PREFIX="crash-artifacts/"

echo "Downloading crash artifacts from Azure Blob Storage"
echo "Storage account: ${AZURE_STORAGE_ACCOUNT}"
echo "Container: ${AZURE_STORAGE_CONTAINER}"
echo "Prefix: ${PREFIX}"
echo "Output directory: ${OUTPUT_DIR}"
echo

# Check if any artifacts exist
ARTIFACT_COUNT=$(az storage blob list \
  --account-name "${AZURE_STORAGE_ACCOUNT}" \
  --container-name "${AZURE_STORAGE_CONTAINER}" \
  --prefix "${PREFIX}" \
  --auth-mode login \
  --query "length(@)" \
  -o tsv 2>/dev/null || echo "0")

if [ "$ARTIFACT_COUNT" -eq 0 ]; then
  echo "No crash artifacts found"
  exit 0
fi

echo "Found ${ARTIFACT_COUNT} blob(s)"
echo

# Create output directory
mkdir -p "${OUTPUT_DIR}"

# Download all blobs with the prefix
echo "Downloading artifacts..."
az storage blob download-batch \
  --account-name "${AZURE_STORAGE_ACCOUNT}" \
  --source "${AZURE_STORAGE_CONTAINER}" \
  --destination "${OUTPUT_DIR}" \
  --pattern "${PREFIX}*" \
  --auth-mode login

echo
echo "═══════════════════════════════════════════════════════════"
echo "✓ Crash artifacts downloaded successfully"
echo
echo "Output directory: ${OUTPUT_DIR}"
echo
echo "Contents:"
find "${OUTPUT_DIR}" -type f -exec ls -lh {} \; | sed 's/^/  /'
echo
