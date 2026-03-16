#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TAG_DATE="$(date +%Y%m%d)"

echo "Building mcd-build-agent image..."
docker build \
    -t mcd-build-agent:latest \
    -t "mcd-build-agent:${TAG_DATE}" \
    "$SCRIPT_DIR"

echo ""
echo "Image built and tagged:"
echo "  mcd-build-agent:latest"
echo "  mcd-build-agent:${TAG_DATE}"
