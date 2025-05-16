
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

# Process command-line arguments
for arg in "$@"; do
  case "$arg" in
    --exit-code=*)
      export EXIT_CODE="${arg#*=}"
      ;;
    --bind-interface=*)
      export CL_DOCKER_BIND_INTERFACE="${arg#*=}"
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

exit_func() {
  if [ "$DO_EXIT" = "true" ]; then
    exit $EXIT_CODE
  fi
  return 0
}
