#!/usr/bin/env bash
# Dispatcher for `just down`. Routes to local cleanup or remote stop.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export REMOTE_NODES=""
export REMOTE_CLEAN=false
export REMOTE_DIR="${REMOTE_DIR:-/opt/tessellation}"

for arg in "$@"; do
  case "$arg" in
    --remote=*) export REMOTE_NODES="${arg#*=}" ;;
    --clean)    export REMOTE_CLEAN=true ;;
    *)          echo "Unknown argument: $arg"; exit 1 ;;
  esac
done

if [ -n "$REMOTE_NODES" ]; then
  source "$SCRIPT_DIR/remote-stop.sh"
else
  bash "$SCRIPT_DIR/tessellation-docker-cleanup.sh"
fi
