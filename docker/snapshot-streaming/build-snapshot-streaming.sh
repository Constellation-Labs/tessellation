#!/usr/bin/env bash

set -e

# Guard against double-source: skip if already completed
if [ "${_SS_BUILD_DONE:-}" = "true" ]; then
  echo "snapshot-streaming build already completed, skipping"
  return 0 2>/dev/null || true
fi

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
SS_DIR="$SCRIPT_DIR"
SS_BRANCH="${SNAPSHOT_STREAMING_BRANCH:-testing}"
SS_REPO="${SNAPSHOT_STREAMING_REPO:-https://github.com/Constellation-Labs/snapshot-streaming.git}"

BE_BRANCH="${BLOCK_EXPLORER_BRANCH:-increase_delegated_stakes}"
BE_REPO="https://github.com/Constellation-Labs/block_explorer.git"

JAR_DEST="$SS_DIR/snapshot-streaming.jar"
BE_DIR="$SS_DIR/block-explorer"

# --- Obtain JAR ---

if [ -n "$SNAPSHOT_STREAMING_JAR" ] && [ -f "$SNAPSHOT_STREAMING_JAR" ]; then
  echo "Using pre-built snapshot-streaming JAR: $SNAPSHOT_STREAMING_JAR"
  cp "$SNAPSHOT_STREAMING_JAR" "$JAR_DEST"
elif [ -f "$JAR_DEST" ] && [ -s "$JAR_DEST" ] && [ "$SKIP_ASSEMBLY" = "true" ]; then
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

  # Apply compatibility patches if present (break circular dependency with tessellation).
  # Each patch down-adapts the snapshot-streaming `testing` branch source — which is
  # written against the newest tessellation API — to an OLDER tessellation API. Whether
  # a given patch is needed depends on the LOCAL tessellation tree, not on the
  # snapshot-streaming source (which always carries the newer call), so each patch is
  # guarded by a grep against the local tree and applied only when the local API lacks
  # the corresponding symbol. `git apply --check` then skips gracefully if the source
  # already differs (e.g. the change landed upstream).
  TYPES_FILE="$SCRIPT_DIR/../../modules/node-shared/src/main/scala/io/constellationnetwork/node/shared/config/types.scala"

  # apply_ss_patch <patch_file> <skip_marker> <marker_file> <label>
  # Skips when <marker_file> contains <skip_marker> (local tessellation already has the
  # newer API, so the testing-branch source already matches and must not be down-adapted).
  apply_ss_patch() {
    local patch_file="$1" skip_marker="$2" marker_file="$3" label="$4"
    [ -f "$patch_file" ] && [ -s "$patch_file" ] || return 0
    if [ -f "$marker_file" ] && grep -q "$skip_marker" "$marker_file"; then
      echo "Local tessellation already has '$skip_marker' — skipping patch ($label)"
      return 0
    fi
    (
      cd "$BUILD_DIR"
      if git apply --check "$patch_file" 2>/dev/null; then
        echo "Applying snapshot-streaming compatibility patch ($label)..."
        git apply "$patch_file"
      else
        echo "Patch already applied or not needed, skipping ($label)..."
      fi
    )
  }

  # Strip c.forkInfoStorage so positional args line up against a SharedConfig where
  # forkInfoStorage has been removed (release/testnet). On branches that still carry it
  # (develop, feature branches) the source already matches, so skip.
  apply_ss_patch "$SS_DIR/snapshot-streaming.patch" \
    "forkInfoStorage: ForkInfoStorageConfig" "$TYPES_FILE" "forkInfoStorage"

  # Drop the scFeeBalanceFromContext arg from GlobalSnapshotStateChannelEventsProcessor.make
  # so the call lines up with tessellation versions whose make takes 5 args (no
  # state-channel-fee-from-context feature). Skip once that field lands in FieldsAddedOrdinals.
  apply_ss_patch "$SS_DIR/snapshot-streaming-scfee.patch" \
    "scFeeBalanceFromContext" "$TYPES_FILE" "scFeeBalanceFromContext"

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

echo "snapshot-streaming JAR: $JAR_DEST"

export _SS_BUILD_DONE=true
