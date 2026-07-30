

export BASH_DEBUG_MODE=${BASH_DEBUG_MODE:-false}
export DATA_ONLY_METAGRAPH=${DATA_ONLY_METAGRAPH:-false}

# Hypergraph release JAR support
# When set, downloads pre-built JARs from GitHub releases instead of building from source
export HYPERGRAPH_RELEASE=${HYPERGRAPH_RELEASE:-""}

# Release tag (set via --version flag or RELEASE_TAG env var)
# Note: only export if non-empty, as sbt's sys.env.get treats "" as Some("")
# which bypasses dynver version resolution
export RELEASE_TAG=${RELEASE_TAG:-""}

export EXTRA_ENV_PATH=${EXTRA_ENV_PATH:-""}
export EXIT_CODE=${EXIT_CODE:-0}
export SNAPSHOT_STREAMING_JAR=${SNAPSHOT_STREAMING_JAR:-""}
export SNAPSHOT_STREAMING_BRANCH=${SNAPSHOT_STREAMING_BRANCH:-"testing"}
export BLOCK_EXPLORER_BRANCH=${BLOCK_EXPLORER_BRANCH:-"increase_delegated_stakes"}
export CL_DOCKER_BIND_INTERFACE=${CL_DOCKER_BIND_INTERFACE:-""}
export CLEAN_ASSEMBLY=${CLEAN_ASSEMBLY:-false}
export DO_EXIT=${DO_EXIT:-false}
export INCLUDE_L0=${INCLUDE_L0:-true}
export INCLUDE_L1=${INCLUDE_L1:-false}
export INCLUDE_ALL=${INCLUDE_ALL:-false}
export PURGE_CONFIG=${PURGE_CONFIG:-true}
export ROLLBACK_MODE=${ROLLBACK_MODE:-false}
export ROLLBACK_HASH=${ROLLBACK_HASH:-""}
export SKIP_ASSEMBLY=${SKIP_ASSEMBLY:-false}
export SKIP_NODES=${SKIP_NODES:-false}
export NET_PREFIX=${NET_PREFIX:-"172.32.0"}
export TESSELLATION_DOCKER_VERSION=${TESSELLATION_DOCKER_VERSION:-"test"}
export CLEANUP_DOCKER_AT_END=${CLEANUP_DOCKER_AT_END:-false}
export REGENERATE_TEST_KEYS=${REGENERATE_TEST_KEYS:-false}
export BUILD_ONLY=${BUILD_ONLY:-false}

# Remote deployment settings
export REMOTE_NODES=${REMOTE_NODES:-""}
export REMOTE_CLEAN=${REMOTE_CLEAN:-false}
export REMOTE_DIR=${REMOTE_DIR:-"/opt/tessellation"}

export DAG_L0_PORT_PREFIX=${DAG_L0_PORT_PREFIX:-90}
export DAG_L1_PORT_PREFIX=${DAG_L1_PORT_PREFIX:-91}
export ML0_PORT_PREFIX=${ML0_PORT_PREFIX:-92}
export CL1_PORT_PREFIX=${CL1_PORT_PREFIX:-93}
export DL1_PORT_PREFIX=${DL1_PORT_PREFIX:-94}

# Configurable base host for test URLs (used with port prefixes)
export TEST_HOST=${TEST_HOST:-"http://localhost"}

# Metagraph specific settings
export METAGRAPH_ML0=${METAGRAPH_ML0:-true}
export METAGRAPH_CL1=${METAGRAPH_CL1:-false}
export METAGRAPH_DL1=${METAGRAPH_DL1:-false}
export METAGRAPH_ML0_RELATIVE_PATH=${METAGRAPH_ML0_RELATIVE_PATH:-"l0"}
export METAGRAPH_CL1_RELATIVE_PATH=${METAGRAPH_CL1_RELATIVE_PATH:-"l1"}
export METAGRAPH_DL1_RELATIVE_PATH=${METAGRAPH_DL1_RELATIVE_PATH:-"data_l1"}
export USE_TESSELLATION_VERSION=${USE_TESSELLATION_VERSION:-true}

# Common docker profile addons:
export DOCKER_PROFILES=${DOCKER_PROFILES:-""}

# Test specific settings
export USE_TEST_METAGRAPH=${USE_TEST_METAGRAPH:-false}
export SELECTED_TESTS=${SELECTED_TESTS:-""}
# Staggered gl0 join: node indices >= NUM_GL0_EARLY delay their self-join by
# GL0_LATE_JOIN_DELAY seconds, so they enter a cluster that has already advanced. Used by the
# committee-rewards test to produce a genuine Core/Tier-1 split (a cluster whose peers all join
# at genesis derives every peer as Core, since the Core floor is a minimum, not a cap).
# Empty NUM_GL0_EARLY = every gl0 node joins immediately (unchanged default for all other tests).
# Keep delay + join time under the ~800s cluster-health-check budget (200 retries x 4s).
export NUM_GL0_EARLY=${NUM_GL0_EARLY:-""}
export GL0_LATE_JOIN_DELAY=${GL0_LATE_JOIN_DELAY:-240}
export SKIP_STREAMING=${SKIP_STREAMING:-false}
export LIST_TESTS=${LIST_TESTS:-false}


# Store any explicitly-set TESSELLATION_VERSION from environment
# This will be used for precedence after args are parsed
if [ -n "${TESSELLATION_VERSION:-}" ]; then
    export EXPLICIT_TESSELLATION_VERSION="$TESSELLATION_VERSION"
fi


echo "processing args: $@"

# Process command-line arguments
for arg in "$@"; do
  case "$arg" in
    --data)
      export DATA_ONLY_METAGRAPH=true
      ;;
    --env=*)
      export EXTRA_ENV_PATH="${arg#*=}"
      ;;
    --exit-code)
      export EXIT_CODE=1
      ;;
    --bind-interface)
      export CL_DOCKER_BIND_INTERFACE=""
      ;;
    --clean-assembly)
      export CLEAN_ASSEMBLY=true
      ;;
    --do-exit)
      export DO_EXIT=true
      ;;
    --l1)
      export INCLUDE_L1=true
      ;;
    --include-all)
      export INCLUDE_ALL=true
      ;;
    --purge-config)
      export PURGE_CONFIG=true
      ;;
    --rollback)
      export ROLLBACK_MODE=true
      export PURGE_CONFIG=false
      ;;
    --rollback-hash=*)
      export ROLLBACK_HASH="${arg#*=}"
      ;;
    --skip-assembly)
      export SKIP_ASSEMBLY=true
      ;;
    --skip-streaming)
      export SKIP_STREAMING=true
      ;;
    --skip-nodes)
      # Skip the node build/ship/start phases in remote-deploy.sh so snapshot-streaming
      # (or monitoring) can be (re)deployed without restarting node containers — which would
      # trip node-0's genesis guard on a live chain. Pair with a pre-built SNAPSHOT_STREAMING_JAR.
      export SKIP_NODES=true
      ;;
    --net-prefix=*)
      export NET_PREFIX="${arg#*=}"
      ;;
    --dag-l0-port-prefix=*)
      export DAG_L0_PORT_PREFIX="${arg#*=}"
      ;;
    --gl1-port-prefix=*)
      export DAG_L1_PORT_PREFIX="${arg#*=}"
      ;;
    --cleanup)
      export CLEANUP_DOCKER_AT_END=true
      ;;
    --tessellation-docker-version=*)
      export TESSELLATION_DOCKER_VERSION="${arg#*=}"
      ;;
    --regenerate-test-keys)
      export REGENERATE_TEST_KEYS=true
      ;;
    --build)
      export BUILD_ONLY=true
      ;;
    --publish)
      export PUBLISH=true
      ;;
    --version=*)
      export RELEASE_TAG="${arg#*=}"
      ;;
    --metagraph=*)
      export METAGRAPH="${arg#*=}"
      ;;
    --hypergraph-release=*)
      export HYPERGRAPH_RELEASE="${arg#*=}"
      ;;
    --snapshot-streaming-jar=*)
      export SNAPSHOT_STREAMING_JAR="${arg#*=}"
      ;;
    --snapshot-streaming-branch=*)
      export SNAPSHOT_STREAMING_BRANCH="${arg#*=}"
      ;;
    --block-explorer-branch=*)
      export BLOCK_EXPLORER_BRANCH="${arg#*=}"
      ;;
    --ml0-path=*)
      export METAGRAPH_ML0_RELATIVE_PATH="${arg#*=}"
      ;;
    --cl1-path=*)
      export METAGRAPH_CL1_RELATIVE_PATH="${arg#*=}"
      ;;
    --dl1-path=*)
      export METAGRAPH_DL1_RELATIVE_PATH="${arg#*=}"
      ;;
    --ml0)
      export METAGRAPH_ML0=true
      ;;
    --cl1)
      export METAGRAPH_CL1=true
      ;;
    --dl1)
      export METAGRAPH_DL1=true
      ;;  
    --num-gl0=*)
      export NUM_GL0_NODES="${arg#*=}"
      export NUM_GL0_NODES_EXPLICIT="${arg#*=}"
      ;;
    --num-gl0-early=*)
      export NUM_GL0_EARLY="${arg#*=}"
      # A non-numeric value would make the per-node `[ "$i" -ge "$NUM_GL0_EARLY" ]` test in
      # docker-env-setup.sh error out inside an `if`, where set -e does not fire: no delay would be
      # written and the rig would silently degrade to an all-genesis cluster.
      case "$NUM_GL0_EARLY" in
        ''|*[!0-9]*) echo "ERROR: --num-gl0-early must be a non-negative integer, got '$NUM_GL0_EARLY'"; exit 1 ;;
      esac
      [ "$NUM_GL0_EARLY" -lt 1 ] && { echo "ERROR: --num-gl0-early must be >= 1 (node 0 is the genesis node)"; exit 1; }
      ;;
    --gl0-late-delay=*)
      export GL0_LATE_JOIN_DELAY="${arg#*=}"
      # A non-numeric delay reaches `sleep` in the backgrounded join_process, which dies silently;
      # the node then never joins and the run fails minutes later in the cluster health check.
      case "$GL0_LATE_JOIN_DELAY" in
        ''|*[!0-9]*) echo "ERROR: --gl0-late-delay must be a non-negative integer (seconds), got '$GL0_LATE_JOIN_DELAY'"; exit 1 ;;
      esac
      ;;
    --num-gl1=*)
      export NUM_GL1_NODES="${arg#*=}"
      export NUM_GL1_NODES_EXPLICIT="${arg#*=}"
      ;;
    --num-ml0=*)
      export NUM_ML0_NODES="${arg#*=}"
      export NUM_ML0_NODES_EXPLICIT="${arg#*=}"
      ;;
    --num-cl1=*)
      export NUM_CL1_NODES="${arg#*=}"
      export NUM_CL1_NODES_EXPLICIT="${arg#*=}"
      ;;
    --num-dl1=*)
      export NUM_DL1_NODES="${arg#*=}"
      export NUM_DL1_NODES_EXPLICIT="${arg#*=}"
      ;;
    --skip-metagraph-assembly)
      export SKIP_METAGRAPH_ASSEMBLY=true
      ;;
    --use-test-metagraph)
      export USE_TEST_METAGRAPH=true
      ;;    
    --fail)
      export SET_FAILURE_BREAKPOINT_TIME=true
      ;;
    --up)
      export DOCKER_UP=true
      ;;
    --gl0-url=*)
      export GL0_URL="${arg#*=}"
      ;;
    --gl1-url=*)
      export GL1_URL="${arg#*=}"
      ;;
    --ml0-url=*)
      export ML0_URL="${arg#*=}"
      ;;
    --cl1-url=*)
      export CL1_URL="${arg#*=}"
      ;;
    --dl1-url=*)
      export DL1_URL="${arg#*=}"
      ;;
    --host=*)
      export TEST_HOST="${arg#*=}"
      ;;
    --test=*)
      test_val="${arg#*=}"
      if [ -n "$SELECTED_TESTS" ]; then
        export SELECTED_TESTS="$SELECTED_TESTS,$test_val"
      else
        export SELECTED_TESTS="$test_val"
      fi
      ;;
    --list-tests)
      export LIST_TESTS=true
      ;;
    --remote=*)
      export REMOTE_NODES="${arg#*=}"
      ;;
    --clean)
      export REMOTE_CLEAN=true
      ;;
    *)
      echo "Unknown argument: $arg"
      exit 1
      ;;
  esac
done

exit_func() {
  if [ "$DO_EXIT" = "true" ]; then
    exit $EXIT_CODE
  fi
  return 0
}

echo "BUILD_ONLY: $BUILD_ONLY"
echo "RELEASE_TAG: $RELEASE_TAG"

# Set TESSELLATION_VERSION with explicit precedence (after args are parsed):
# 1. TESSELLATION_VERSION env var (explicit override) - highest priority
# 2. --hypergraph-release flag
# 3. --version / RELEASE_TAG
# 4. Git describe (auto-derive, matches sbt dynver format)
# 5. 99.99.99-SNAPSHOT (default, build from source)
if [ -n "${EXPLICIT_TESSELLATION_VERSION:-}" ]; then
    export TESSELLATION_VERSION="$EXPLICIT_TESSELLATION_VERSION"
    echo "Setting TESSELLATION_VERSION=$TESSELLATION_VERSION (from environment)"
elif [ -n "$HYPERGRAPH_RELEASE" ]; then
    export TESSELLATION_VERSION="${HYPERGRAPH_RELEASE#v}"
    echo "Setting TESSELLATION_VERSION=$TESSELLATION_VERSION (from --hypergraph-release)"
elif [ -n "$RELEASE_TAG" ]; then
    export TESSELLATION_VERSION="${RELEASE_TAG#v}"
    echo "Setting TESSELLATION_VERSION=$TESSELLATION_VERSION (from --version/RELEASE_TAG)"
elif command -v git &> /dev/null && git rev-parse --git-dir &> /dev/null; then
    # Get version from git describe (fast, matches dynver format)
    # Use --match 'v*' to only consider version tags (consistent with dynver)
    # Output: v4.1.0 (on tag) or v4.1.0-3-gabc1234 (3 commits after tag)
    GIT_DESC=$(git describe --tags --match 'v*' --abbrev=7 2>/dev/null || echo "")
    if [ -n "$GIT_DESC" ]; then
        # Strip leading 'v' and convert to dynver-like format
        # v4.1.0-3-gabc1234 -> 4.1.0+3.abc1234.local (or .buildN in CI)
        BASE_VERSION=$(echo "$GIT_DESC" | sed 's/^v//; s/-\([0-9]*\)-g\([a-f0-9]*\)$/+\1.\2/')
        # Append buildId suffix to match sbt dynver format
        if [ -n "${GITHUB_RUN_NUMBER:-}" ]; then
            BUILD_ID="build${GITHUB_RUN_NUMBER}"
        else
            BUILD_ID="local"
        fi
        # Only append buildId if not on exact tag (version contains +)
        if [[ "$BASE_VERSION" == *"+"* ]]; then
            export TESSELLATION_VERSION="${BASE_VERSION}.${BUILD_ID}"
        else
            export TESSELLATION_VERSION="$BASE_VERSION"
        fi
        echo "Setting TESSELLATION_VERSION=$TESSELLATION_VERSION (from git describe)"
    else
        export TESSELLATION_VERSION="99.99.99-SNAPSHOT"
        echo "Setting TESSELLATION_VERSION=$TESSELLATION_VERSION (fallback - no git tags)"
    fi
else
    export TESSELLATION_VERSION="99.99.99-SNAPSHOT"
    echo "Setting TESSELLATION_VERSION=$TESSELLATION_VERSION (default snapshot)"
fi

if [ "$DATA_ONLY_METAGRAPH" = "true" ]; then
    export NUM_GL0_NODES=1
    export NUM_GL1_NODES=0
    export NUM_ML0_NODES=1
    export NUM_CL1_NODES=0
    export NUM_DL1_NODES=3

fi


# If a specific metagraph is provided, set sensible defaults
if [ -n "$METAGRAPH" ]; then
  if [ -z "$NUM_GL0_NODES" ]; then
    export NUM_GL0_NODES=1
  fi
  if [ -z "$NUM_GL1_NODES" ]; then
    export NUM_GL1_NODES=3
  fi
  if [ -z "$NUM_ML0_NODES" ]; then
    export NUM_ML0_NODES=1
  fi
  if [ -z "$NUM_CL1_NODES" ]; then
    export NUM_CL1_NODES=3
  fi
  if [ -z "$NUM_DL1_NODES" ]; then
    export NUM_DL1_NODES=3
  fi
fi

# Set more complex defaults below

if [ "$USE_TEST_METAGRAPH" = "true" ] && [ -z "$METAGRAPH" ]; then
    export METAGRAPH=".github/templates/metagraphs/project_template"
fi

if [ "$METAGRAPH" = ".github/templates/metagraphs/project_template" ] && [ -z "$SKIP_METAGRAPH_ASSEMBLY" ]; then
    export SKIP_METAGRAPH_ASSEMBLY=true
fi


# Defaults which must be declared after, such that the complex ones won't override them

if [ -z "$SKIP_METAGRAPH_ASSEMBLY" ]; then
    export SKIP_METAGRAPH_ASSEMBLY=false
fi


if [ -z $NUM_GL0_NODES ]; then
    if [ -z "$METAGRAPH" ]; then
        export NUM_GL0_NODES=3
    else
        export NUM_GL0_NODES=2
    fi
fi

if [ -z $NUM_GL1_NODES ]; then
    export NUM_GL1_NODES=3
fi

if [ -z $NUM_ML0_NODES ]; then
    export NUM_ML0_NODES=2
fi

if [ -z $NUM_CL1_NODES ]; then
    export NUM_CL1_NODES=3
fi

if [ -z $NUM_DL1_NODES ]; then
    export NUM_DL1_NODES=3
fi


if [ -z "$METAGRAPH" ]; then
    export NUM_ML0_NODES="0"
    export NUM_CL1_NODES="0"
    export NUM_DL1_NODES="0"
fi

# Remote host: default to 1 gl0 node, 1 gl1 node, 0 metagraph nodes for health check
# unless explicitly overridden via --num-* args
if [ "$TEST_HOST" != "http://localhost" ]; then
    export NUM_GL0_NODES=${NUM_GL0_NODES_EXPLICIT:-1}
    export NUM_GL1_NODES=${NUM_GL1_NODES_EXPLICIT:-1}
    export NUM_ML0_NODES=${NUM_ML0_NODES_EXPLICIT:-0}
    export NUM_CL1_NODES=${NUM_CL1_NODES_EXPLICIT:-0}
    export NUM_DL1_NODES=${NUM_DL1_NODES_EXPLICIT:-0}
fi

if [ -n "$METAGRAPH" ]; then
    if [ -z "$PUBLISH" ]; then
        export PUBLISH=true
    fi
fi

# Compute MAX_NODES as the maximum of all NUM_*_NODES values (capped at 10)
# This drives how many node directories, keys, and configs are created
_max_of() { [ "$1" -gt "$2" ] && echo "$1" || echo "$2"; }
MAX_NODES=$(_max_of ${NUM_GL0_NODES:-0} ${NUM_GL1_NODES:-0})
MAX_NODES=$(_max_of $MAX_NODES ${NUM_ML0_NODES:-0})
MAX_NODES=$(_max_of $MAX_NODES ${NUM_CL1_NODES:-0})
MAX_NODES=$(_max_of $MAX_NODES ${NUM_DL1_NODES:-0})
# Ensure at least 3 (legacy default) and at most 9 (single-digit IP/port offset limit)
MAX_NODES=$(_max_of $MAX_NODES 3)
[ "$MAX_NODES" -gt 9 ] && MAX_NODES=9
export MAX_NODES

# Layer URLs: explicit overrides take priority, otherwise built from TEST_HOST + port prefix
# When using a remote host, GL1 defaults to port 9010 instead of 9100
if [ "$TEST_HOST" != "http://localhost" ]; then
  GL1_DEFAULT_PORT=9010
else
  GL1_DEFAULT_PORT="${DAG_L1_PORT_PREFIX}00"
fi
export GL0_URL=${GL0_URL:-"${TEST_HOST}:${DAG_L0_PORT_PREFIX}00"}
export GL1_URL=${GL1_URL:-"${TEST_HOST}:${GL1_DEFAULT_PORT}"}
export ML0_URL=${ML0_URL:-"${TEST_HOST}:${ML0_PORT_PREFIX}00"}
export CL1_URL=${CL1_URL:-"${TEST_HOST}:${CL1_PORT_PREFIX}00"}
export DL1_URL=${DL1_URL:-"${TEST_HOST}:${DL1_PORT_PREFIX}00"}
