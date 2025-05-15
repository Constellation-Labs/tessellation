# Tessellation Justfile

# Make sure dependencies are installed before running any recipe
_check_deps:
    @bash ./docker/install_dependencies.sh
    @bash ./docker/bin/set-default-env.sh

# Main test command to run the Tessellation node setup
test *ARGS: _check_deps
    @bash ./docker/bin/set-default-env.sh
    # Process command-line arguments
    for arg in "$@"; do
    case "$arg" in
      --exit-code=*)
        export EXIT_CODE="${arg#*=}"
        ;;
      --bind-interface=*)
        export BIND_INTERFACE="${arg#*=}"
        ;;
      --clean-assembly=*)
        export CLEAN_ASSEMBLY="${arg#*=}"
        ;;
      --do-exit=*)
        export DO_EXIT="${arg#*=}"
        ;;
      --l1=*)
        export INCLUDE_L1="${arg#*=}"
        ;;
      --include-all=*)
        export INCLUDE_ALL="${arg#*=}"
        ;;
      --purge-config=*)
        export PURGE_CONFIG="${arg#*=}"
        ;;
      --skip-assembly=*)
        export SKIP_ASSEMBLY="${arg#*=}"
        ;;
      --net-prefix=*)
        export NET_PREFIX="${arg#*=}"
        ;;
      --tessellation-docker-version=*)
        export TESSELLATION_DOCKER_VERSION="${arg#*=}"
        ;;
      *)
        echo "Unknown argument: $arg"
        exit 1
        ;;
    esac
    done

    # Map justfile variables to compose-runner.sh variables
    export L0_ONLY=$INCLUDE_L0
    export CLEAN_BUILD=$CLEAN_ASSEMBLY
    export REMOVE_EXISTING_CONFIGS=$PURGE_CONFIG

    # Check if this is a dry-run
    if [ "$1" = "--dry-run" ]; then
      echo "Dry run mode: Environment variables that would be passed to compose-runner.sh:"
      echo "EXIT_CODE=$EXIT_CODE"
      echo "BIND_INTERFACE=$BIND_INTERFACE"
      echo "CLEAN_BUILD=$CLEAN_BUILD (from CLEAN_ASSEMBLY=$CLEAN_ASSEMBLY)"
      echo "DO_EXIT=$DO_EXIT"
      echo "L0_ONLY=$L0_ONLY (from INCLUDE_L0=$INCLUDE_L0)"
      echo "INCLUDE_L1=$INCLUDE_L1"
      echo "INCLUDE_ALL=$INCLUDE_ALL"
      echo "REMOVE_EXISTING_CONFIGS=$REMOVE_EXISTING_CONFIGS (from PURGE_CONFIG=$PURGE_CONFIG)"
      echo "SKIP_ASSEMBLY=$SKIP_ASSEMBLY"
      echo "NET_PREFIX=$NET_PREFIX"
      echo "TESSELLATION_DOCKER_VERSION=$TESSELLATION_DOCKER_VERSION"
    else
      # Call the compose-runner.sh script
      ./docker/compose-runner.sh
    fi

# Test command with dry-run mode for testing argument handling
test-dry-run *ARGS: _check_deps
    @just test --dry-run {{ARGS}}
