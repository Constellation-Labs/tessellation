# Tessellation Justfile

# Make sure dependencies are installed before running any recipe
_check_deps:
    @bash ./docker/install_dependencies.sh

# Default variables that can be overridden at runtime
export EXIT_CODE := "0"
export BIND_INTERFACE := "" # example to bind to loopback: 127.0.0.1:
export CLEAN_BUILD := "false"
export DO_EXIT := "false"
export L0_ONLY := "true"
export INCLUDE_L1 := "false"
export REMOVE_EXISTING_CONFIGS := "true"
export SKIP_ASSEMBLY := "false"
export NET_PREFIX := "172.32.0"
export TESSELLATION_DOCKER_VERSION := "test"

# Main test command to run the Tessellation node setup
test: _check_deps
    #!/bin/bash
    # Break on any error
    set -e

    # Check for jq dependency
    if ! command -v jq >/dev/null 2>&1; then
      echo "jq missing, please install if following command fails"
      case "$(uname)" in
        Linux)
          sudo apt install -y jq
          ;;
        Darwin)
          brew install jq
          ;;
        *)
          echo "Unsupported OS: $(uname). Please install jq manually."
          exit 1
          ;;
      esac
    else
      echo "jq is already installed."
    fi

    # YOUR CODE HERE: Clean up existing containers
    # Example:
    # echo "Stopping and removing global-l0 containers..."
    # docker ps -a --filter name=global-l0 --format "{{.ID}}" | while read -r container_id; do
    #     docker stop "$container_id" 2>/dev/null || true
    #     docker rm -f "$container_id" 2>/dev/null || true
    # done

    # YOUR CODE HERE: Set up directories
    # Example:
    # if [ "$REMOVE_EXISTING_CONFIGS" = "true" ]; then
    #   rm -rf ./nodes
    # fi
    # mkdir -p ./nodes/global-l0/0
    # for i in 1 2; do
    #   mkdir -p ./nodes/dag-l1/$i
    # done

    # YOUR CODE HERE: Build assemblies if needed
    # Example:
    # if [[ "$L0_ONLY" == "false" && "$SKIP_ASSEMBLY" == "false" ]]; then
    #   sbt dagL0/assembly dagL1/assembly keytool/assembly wallet/assembly
    # fi

    # YOUR CODE HERE: Copy JAR files
    # Example:
    # mkdir -p ./docker/jars/
    # for module in "dag-l0" "dag-l1" "keytool" "wallet"; do
    #   path=$(ls -1t modules/${module}/target/scala-2.13/tessellation-${module}-assembly*.jar | head -n1)
    #   cp $path ./nodes/${module}.jar
    #   cp $path ./docker/jars/${module}.jar
    # done

    # YOUR CODE HERE: Build Docker image
    # Example:
    # docker build -t constellationnetwork/tessellation:$TESSELLATION_DOCKER_VERSION -f docker/Dockerfile .

    # YOUR CODE HERE: Generate configurations
    # Example:
    # cat << EOF > ./nodes/.envrc
    # export CL_KEYSTORE="key.p12"
    # export CL_KEYALIAS="alias"
    # export CL_PASSWORD="password"
    # export CL_APP_ENV="dev"
    # EOF

    # YOUR CODE HERE: Start containers
    # Example:
    # docker network create \
    #   --driver=bridge \
    #   --subnet=${NET_PREFIX}.0/24 \
    #   tessellation_common || true
    # 
    # cd ./nodes/global-l0/0/
    # docker compose -f docker-compose.test.yaml -f docker-compose.yaml -f docker-compose.profile-l0.yaml --profile l0 up -d

    # YOUR CODE HERE: Exit function if needed
    if [ "$DO_EXIT" = "true" ]; then
      exit $EXIT_CODE
    fi
