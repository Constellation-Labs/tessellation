#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ -d "$DOCKER_DIR/nodes" ]; then
    # Use Docker to remove root-owned files (avoids sudo requirement)
    docker run --rm -v "$DOCKER_DIR/nodes:/nodes" alpine rm -rf /nodes/* 2>/dev/null || true
    rm -rf "$DOCKER_DIR/nodes" 2>/dev/null || true
fi