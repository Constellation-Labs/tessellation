#!/usr/bin/env bash

set -e

# Guard against double-source: skip if already completed
if [ "${_SS_BUILD_DONE:-}" = "true" ]; then
  echo "snapshot-streaming build already completed, skipping"
  return 0 2>/dev/null || true
fi

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
SS_DIR="$SCRIPT_DIR"
SS_BRANCH="${SNAPSHOT_STREAMING_BRANCH:-develop}"
SS_REPO="${SNAPSHOT_STREAMING_REPO:-https://github.com/Constellation-Labs/snapshot-streaming.git}"

BE_BRANCH="${BLOCK_EXPLORER_BRANCH:-develop}"
BE_REPO="https://github.com/Constellation-Labs/block_explorer.git"

JAR_DEST="$SS_DIR/snapshot-streaming.jar"
BE_DIR="$SS_DIR/block-explorer"

# --- Obtain JAR ---

if [ "${SKIP_SS_JAR:-false}" = "true" ]; then
  echo "SKIP_SS_JAR=true — skipping snapshot-streaming jar build (image pulled from registry); block_explorer still cloned for DB migrations"
elif [ -n "$SNAPSHOT_STREAMING_JAR" ] && [ -f "$SNAPSHOT_STREAMING_JAR" ]; then
  echo "Using pre-built snapshot-streaming JAR: $SNAPSHOT_STREAMING_JAR"
  cp "$SNAPSHOT_STREAMING_JAR" "$JAR_DEST"
elif [ -f "$JAR_DEST" ] && [ -s "$JAR_DEST" ]; then
  echo "Reusing existing snapshot-streaming JAR: $JAR_DEST"
else
  echo "Building snapshot-streaming from source (branch: $SS_BRANCH)..."
  BUILD_DIR="$SS_DIR/.build"
  rm -rf "$BUILD_DIR"
  git clone --depth 1 --branch "$SS_BRANCH" "$SS_REPO" "$BUILD_DIR"

  # Override tessellation SDK version to match local build
  if [ -n "$TESSELLATION_VERSION" ]; then
    echo "Overriding tessellation version to $TESSELLATION_VERSION in snapshot-streaming build"
    sed -i.bak "s/val tessellation = \"[^\"]*\"/val tessellation = \"$TESSELLATION_VERSION\"/" \
      "$BUILD_DIR/project/Dependencies.scala"
  fi

  # Apply compatibility patch if present (breaks circular dependency with tessellation)
  # Uses --check first to skip gracefully if already applied upstream
  PATCH_FILE="$SS_DIR/snapshot-streaming.patch"
  if [ -f "$PATCH_FILE" ] && [ -s "$PATCH_FILE" ]; then
    cd "$BUILD_DIR"
    if git apply --check "$PATCH_FILE" 2>/dev/null; then
      echo "Applying snapshot-streaming compatibility patch..."
      git apply "$PATCH_FILE"
    else
      cd "$BUILD_DIR"
      if git apply --reverse --check "$PATCH_FILE" 2>/dev/null; then
        echo "snapshot-streaming patch already applied upstream — skipping."
      elif git apply --check "$PATCH_FILE" 2>/dev/null; then
        echo "Applying snapshot-streaming compatibility patch..."
        git apply "$PATCH_FILE"
      elif patch -p1 --fuzz=3 --forward --dry-run < "$PATCH_FILE" >/dev/null 2>&1; then
        echo "git apply rejected the patch (context drift); applying with fuzz via GNU patch..."
        patch -p1 --fuzz=3 --forward < "$PATCH_FILE"
      else
        # Either already applied upstream (harmless) or a stale/drifted patch that no longer
        # matches this snapshot-streaming branch. --forward stops GNU patch from REVERSE-applying
        # an already-applied patch (which would re-add forkInfoStorage and break compilation).
        echo "WARNING: snapshot-streaming.patch did not apply (already applied, or stale vs this SS branch)." >&2
        echo "         If stale, regenerate it (see testnet-hetzner-migration/snapshot-streaming-blocker.md)." >&2
      fi
      cd "$SCRIPT_DIR"
    fi
    cd "$SCRIPT_DIR"
  fi

  cd "$BUILD_DIR"
  sbt --error assembly
  # Try multiple JAR naming patterns (sbt-assembly varies by project config)
  JAR_PATH=$(ls -1tS target/scala-2.13/*-assembly*.jar 2>/dev/null | head -n1)
  if [ -z "$JAR_PATH" ]; then
    JAR_PATH=$(ls -1tS target/scala-2.13/*.jar 2>/dev/null | head -n1)
  fi
  if [ -z "$JAR_PATH" ]; then
    echo "ERROR: snapshot-streaming assembly JAR not found in target/scala-2.13/"
    ls -la target/scala-2.13/ 2>/dev/null || echo "  (directory does not exist)"
    exit 1
  fi
  echo "Found JAR: $JAR_PATH"
  cp "$JAR_PATH" "$JAR_DEST"
  cd "$SCRIPT_DIR"

  rm -rf "$BUILD_DIR"
  echo "snapshot-streaming built successfully"
fi

# --- Clone block_explorer for database migrations ---
# Not needed with an external SS database (SKIP_BE_CLONE=true): schema/migrations are
# owned by the existing block-explorer deployment, and the BE app isn't run on-cluster.
if [ "${SKIP_BE_CLONE:-false}" = "true" ]; then
  echo "SKIP_BE_CLONE=true — skipping block_explorer clone (external SS database)"
else
  if [ -d "$BE_DIR/.git" ]; then
    echo "Reusing existing block_explorer clone: $BE_DIR"
    git -C "$BE_DIR" fetch --depth 1 origin "$BE_BRANCH"
    git -C "$BE_DIR" checkout FETCH_HEAD --quiet
  else
    echo "Cloning block_explorer (branch: $BE_BRANCH)..."
    rm -rf "$BE_DIR"
    git clone --depth 1 --branch "$BE_BRANCH" "$BE_REPO" "$BE_DIR"
  fi

  if [ ! -d "$BE_DIR/prisma/migrations" ]; then
    echo "ERROR: block_explorer prisma/migrations not found"
    exit 1
  fi
  echo "block_explorer migrations ready: $BE_DIR/prisma/migrations"
fi

echo "snapshot-streaming JAR: $JAR_DEST"

export _SS_BUILD_DONE=true
