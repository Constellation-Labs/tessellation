

export BASH_DEBUG_MODE=${BASH_DEBUG_MODE:-false}
export DATA_ONLY_METAGRAPH=${DATA_ONLY_METAGRAPH:-false}

# Hypergraph release JAR support
# When set, downloads pre-built JARs from GitHub releases instead of building from source
export HYPERGRAPH_RELEASE=${HYPERGRAPH_RELEASE:-""}

# Release tag (set via --version flag or RELEASE_TAG env var)
export RELEASE_TAG=${RELEASE_TAG:-""}

export EXTRA_ENV_PATH=${EXTRA_ENV_PATH:-""}
export EXIT_CODE=${EXIT_CODE:-0}
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
export NET_PREFIX=${NET_PREFIX:-"172.32.0"}
export TESSELLATION_DOCKER_VERSION=${TESSELLATION_DOCKER_VERSION:-"test"}
export CLEANUP_DOCKER_AT_END=${CLEANUP_DOCKER_AT_END:-false}
export REGENERATE_TEST_KEYS=${REGENERATE_TEST_KEYS:-false}
export BUILD_ONLY=${BUILD_ONLY:-false}


export DAG_L0_PORT_PREFIX=${DAG_L0_PORT_PREFIX:-90}
export DAG_L1_PORT_PREFIX=${DAG_L1_PORT_PREFIX:-91}
export ML0_PORT_PREFIX=${ML0_PORT_PREFIX:-92}
export CL1_PORT_PREFIX=${CL1_PORT_PREFIX:-93}
export DL1_PORT_PREFIX=${DL1_PORT_PREFIX:-94}

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
      ;;
    --num-gl1=*)
      export NUM_GL1_NODES="${arg#*=}"
      ;;
    --num-ml0=*)
      export NUM_ML0_NODES="${arg#*=}"
      ;;
    --num-cl1=*)
      export NUM_CL1_NODES="${arg#*=}"
      ;;
    --num-dl1=*)
      export NUM_DL1_NODES="${arg#*=}"
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
# 4. 99.99.99-SNAPSHOT (default, build from source)
if [ -n "${EXPLICIT_TESSELLATION_VERSION:-}" ]; then
    export TESSELLATION_VERSION="$EXPLICIT_TESSELLATION_VERSION"
    echo "Setting TESSELLATION_VERSION=$TESSELLATION_VERSION (from environment)"
elif [ -n "$HYPERGRAPH_RELEASE" ]; then
    export TESSELLATION_VERSION="${HYPERGRAPH_RELEASE#v}"
    echo "Setting TESSELLATION_VERSION=$TESSELLATION_VERSION (from --hypergraph-release)"
elif [ -n "$RELEASE_TAG" ]; then
    export TESSELLATION_VERSION="${RELEASE_TAG#v}"
    echo "Setting TESSELLATION_VERSION=$TESSELLATION_VERSION (from --version/RELEASE_TAG)"
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

if [ -n "$METAGRAPH" ]; then
    if [ -z "$PUBLISH" ]; then
        export PUBLISH=true
    fi
fi
