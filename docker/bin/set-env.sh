
# Set default values if environment variables are not set
if [ -z "$EXIT_CODE" ]; then
    export EXIT_CODE=0
fi

if [ -z "$CL_DOCKER_BIND_INTERFACE" ]; then
    # Example
    # 127.0.0.1:
    export CL_DOCKER_BIND_INTERFACE=""
fi

if [ -z "$CLEAN_ASSEMBLY" ]; then
    export CLEAN_ASSEMBLY=false
fi

if [ -z "$DO_EXIT" ]; then
    export DO_EXIT=false
fi

if [ -z "$INCLUDE_L0" ]; then
    export INCLUDE_L0=true
fi

if [ -z "$INCLUDE_L1" ]; then
    export INCLUDE_L1=false
fi

if [ -z "$INCLUDE_ALL" ]; then
    export INCLUDE_ALL=false
fi

if [ -z "$PURGE_CONFIG" ]; then
    export PURGE_CONFIG=true
fi

if [ -z "$SKIP_ASSEMBLY" ]; then
    export SKIP_ASSEMBLY=false
fi

if [ -z "$NET_PREFIX" ]; then
    export NET_PREFIX="172.32.0"
fi

if [ -z "$TESSELLATION_DOCKER_VERSION" ]; then
    export TESSELLATION_DOCKER_VERSION=test
fi

if [ -z "$CLEANUP_DOCKER_AT_END" ]; then
    export CLEANUP_DOCKER_AT_END=false
fi

if [ -z "$DAG_L0_PORT_PREFIX" ]; then
    export DAG_L0_PORT_PREFIX=90
fi

if [ -z "$DAG_L1_PORT_PREFIX" ]; then
    export DAG_L1_PORT_PREFIX=91
fi

if [ -z "$ML0_PORT_PREFIX" ]; then
    export ML0_PORT_PREFIX=92
fi

if [ -z "$CL1_PORT_PREFIX" ]; then
    export CL1_PORT_PREFIX=93
fi

if [ -z "$DL1_PORT_PREFIX" ]; then
    export DL1_PORT_PREFIX=94
fi

if [ -z "$REGENERATE_TEST_KEYS" ]; then
    export REGENERATE_TEST_KEYS=false
fi

if [ -z "$BUILD_ONLY" ]; then
    export BUILD_ONLY=false
fi


# if [ -z "$METAGRAPH" ]; then
#     export METAGRAPH=$PROJECT_ROOT/.github/templates/metagraphs/project_template
# fi

if [ -z "$METAGRAPH_ML0" ]; then
    export METAGRAPH_ML0=true
fi

if [ -z "$METAGRAPH_CL1" ]; then
    export METAGRAPH_CL1=false
fi

if [ -z "$METAGRAPH_DL1" ]; then
    export METAGRAPH_DL1=false
fi

if [ -z $METAGRAPH_ML0_RELATIVE_PATH ]; then
    export METAGRAPH_ML0_RELATIVE_PATH="l0"
fi

if [ -z $METAGRAPH_CL1_RELATIVE_PATH ]; then
    export METAGRAPH_CL1_RELATIVE_PATH="l1"
fi

if [ -z $METAGRAPH_DL1_RELATIVE_PATH ]; then
    export METAGRAPH_DL1_RELATIVE_PATH="data_l1"
fi

if [ -z $USE_TESSELLATION_VERSION ]; then
    export USE_TESSELLATION_VERSION=true
fi

if [ -z $USE_TEST_METAGRAPH ]; then
    export USE_TEST_METAGRAPH=false
fi

# Explicitly set TESSELLATION_VERSION based on the project's version
if [ -z "$TESSELLATION_VERSION" ]; then
    if [ -n "$RELEASE_TAG" ]; then
        export TESSELLATION_VERSION="${RELEASE_TAG#v}"
    else
        export TESSELLATION_VERSION="99.99.99-SNAPSHOT"
    fi
    echo "Setting TESSELLATION_VERSION=$TESSELLATION_VERSION"
fi


echo "processing args: $@"

# Process command-line arguments
for arg in "$@"; do
  case "$arg" in
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
    --cleanup-docker-at-end=*)
      export CLEANUP_DOCKER_AT_END="${arg#*=}"
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
    --ml0-path=*)
      export METAGRAPH_ML0_RELATIVE_PATH="${arg#*=}"
      ;;
    --cl1-path=*)
      export METAGRAPH_CL1_RELATIVE_PATH="${arg#*=}"
      ;;
    --dl1-path=*)
      export METAGRAPH_DL1_RELATIVE_PATH="${arg#*=}"
      ;;
    --ml0=*)
      export METAGRAPH_ML0=true
      ;;
    --cl1=*)
      export METAGRAPH_CL1=true
      ;;
    --dl1=*)
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




