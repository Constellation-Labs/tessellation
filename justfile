# Tessellation Justfile

# Make sure dependencies are installed before running any recipe
_check_deps:
    @bash ./docker/install_dependencies.sh

# Default variables that can be overridden at runtime
export EXIT_CODE := "0"
export BIND_INTERFACE := "" # example to bind to loopback: 127.0.0.1:
export CLEAN_ASSEMBLY := "false"
export DO_EXIT := "false"
export INCLUDE_L0 := "true"
export INCLUDE_L1 := "false"
export INCLUDE_ALL := "false"
export PURGE_CONFIG := "true"
export SKIP_ASSEMBLY := "false"
export NET_PREFIX := "172.32.0"
export TESSELLATION_DOCKER_VERSION := "test"

# Main test command to run the Tessellation node setup
test: _check_deps
