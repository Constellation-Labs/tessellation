#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# compose-runner uses the repo-root nodes/ ($PROJECT_ROOT/nodes). The previous target here was
# $SCRIPT_DIR/../nodes (docker/nodes), which does not exist -- so the purge was a no-op, root-owned
# container data accumulated, and the next run's `mkdir nodes/N` failed with Permission denied.
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

clean_nodes_dir() {
    local nodes_dir="$1"
    [ -d "$nodes_dir" ] || return 0
    local parent base
    parent="$(dirname "$nodes_dir")"
    base="$(basename "$nodes_dir")"
    # Node data dirs are created by the containers as root, so a user-level rm cannot remove them.
    # Remove the whole nodes/ dir (including root-owned contents) via a root container that mounts
    # the PARENT, so the directory itself is removed rather than just its contents.
    docker run --rm -v "$parent:/work" alpine rm -rf "/work/$base" 2>/dev/null || true
    rm -rf "$nodes_dir" 2>/dev/null || true
}

clean_nodes_dir "$PROJECT_ROOT/nodes"
clean_nodes_dir "$PROJECT_ROOT/docker/nodes"  # legacy location, if present

# Recreate an empty, user-owned nodes/ so config generation (mkdir nodes/N) succeeds.
mkdir -p "$PROJECT_ROOT/nodes"
