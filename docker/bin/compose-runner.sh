#!/usr/bin/env bash
set -e


export START_TIME=$(date +%s)
# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cur_dir=$(pwd)
echo "Script started in $cur_dir with script directory $SCRIPT_DIR"

cd "$SCRIPT_DIR/../../"
cur_dir=$(pwd)
echo "Running in top level directory $cur_dir"

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

./docker/bin/tessellation-docker-cleanup.sh

if [ "$PURGE_CONFIG" = "true" ]; then
  rm -rf ./nodes
fi

for i in 0 1 2; do
  mkdir -p ./nodes/$i
done

assemble_all() {
  sbt dagL0/assembly dagL1/assembly keytool/assembly wallet/assembly
}


if [[ "$INCLUDE_L0" == "false" && "$INCLUDE_L1" == "false" && "$SKIP_ASSEMBLY" == "false" ]]; then
  assemble_all
else
  missing=false

  for module in dag-l0 dag-l1 keytool wallet; do
    jar_path=$(ls -1t modules/"$module"/target/scala-2.13/tessellation-"$module"-assembly*.jar 2>/dev/null | head -n1)
    if [ -z "$jar_path" ]; then
      echo "⚠️  Missing JAR for module: $module"
      missing=true
      break
    fi
  done

  if [ "$missing" = true ]; then
    echo "▶️  One or more modules is missing. Cannot skip assembly. Running full assembly"
    assemble_all
  else
    if [ "$SKIP_ASSEMBLY" == "false" ]; then
      override_set=false
      if [ "$INCLUDE_L0" == "true" ]; then
        echo "Assembling L0"
        sbt dagL0/assembly
        override_set=true
      fi
      if [ "$INCLUDE_L1" == "true" ]; then
        echo "Assembling L1"
        sbt dagL1/assembly
        override_set=true
      fi
      if [ "$override_set" == "false" ]; then
        echo "Assembling L0 according to default behavior"
        sbt dagL0/assembly
      fi
    else
      echo "Found existing assemblies, and skip assembly was set to true"
    fi
  fi
fi

mkdir -p ./docker/jars/

for module in "dag-l0" "dag-l1" "keytool" "wallet"
do
  path=$(ls -1t modules/${module}/target/scala-2.13/tessellation-${module}-assembly*.jar | head -n1)
  cp $path ./nodes/${module}.jar
  cp $path ./docker/jars/${module}.jar
done


export TESSELLATION_DOCKER_VERSION=test
docker build -t constellationnetwork/tessellation:$TESSELLATION_DOCKER_VERSION -f docker/Dockerfile .

source ./docker/bin/tessellation-docker-cleanup.sh

cat << EOF > ./nodes/.envrc
export CL_KEYSTORE="key.p12"
export CL_KEYALIAS="alias"
export CL_PASSWORD="password"
export CL_APP_ENV="dev"
EOF

cp ./nodes/.envrc ./nodes/0/.envrc
cd ./nodes/0/

# Unsafe source kept in subshell
out=$(
  source .envrc
  java -jar ../keytool.jar generate
  java -jar ../wallet.jar show-id
)
export GL0_GENERATED_WALLET_PEER_ID=$out
echo "Generated GL0 wallet peer id $GL0_GENERATED_WALLET_PEER_ID"
echo $GL0_GENERATED_WALLET_PEER_ID > peer_id

ret_addr=$(
  source .envrc
  java -jar ../wallet.jar show-address
)
export ADDRESS=$ret_addr
echo "$ADDRESS" > address.txt
echo "Generated GL0 wallet address $ADDRESS"

cd ../../

cat << EOF > ./nodes/.env
CL_APP_ENV="dev"
CL_COLLATERAL=0
TESSELLATION_DOCKER_VERSION=test
CL_TEST_MODE=true
CL_LOCAL_MODE=true
CL_L0_PEER_HTTP_HOST=${NET_PREFIX}.10
CL_DAG_L1_JOIN_IP=${NET_PREFIX}.20
CL_DAG_L0_JOIN_IP=${NET_PREFIX}.10
CL_L0_PEER_HTTP_PORT=9000
NET_PREFIX=${NET_PREFIX}
CL_DOCKER_BIND_INTERFACE=${CL_DOCKER_BIND_INTERFACE}
EOF

echo "CL_L0_PEER_ID=$GL0_GENERATED_WALLET_PEER_ID" >> ./nodes/.env
echo "CL_DAG_L1_JOIN_ID=$GL0_GENERATED_WALLET_PEER_ID" >> ./nodes/.env
echo "CL_DAG_L0_JOIN_ID=$GL0_GENERATED_WALLET_PEER_ID" >> ./nodes/.env

cp ./nodes/.env ./nodes/0/.env
cp ./nodes/.envrc ./nodes/1/.envrc
cp ./nodes/.envrc ./nodes/2/.envrc
cp ./nodes/.env ./nodes/1/.env
cp ./nodes/.env ./nodes/2/.env
cp ./.github/config/genesis.csv ./nodes/0/genesis.csv

cd ./nodes/0/
# 1000000 * 1e8
echo "$ADDRESS,100000000000000" >> genesis.csv
echo "Generated genesis file:"
cat genesis.csv
echo "CL_GENESIS_FILE=./genesis.csv" >> .env
echo "CL_DAG_L1_JOIN_ENABLED=false" >> .env
echo "CL_DAG_L0_JOIN_ENABLED=false" >> .env
echo "CONTAINER_NAME_SUFFIX=-0" >> .env
echo "CONTAINER_OFFSET=0" >> .env

# These are only required on systems that implement docker with a host networking bridge
# Port conflicts cause it to fail with external networks that re-use ports
# Internal ports
echo "L0_CL_PUBLIC_HTTP_PORT=9000" >> .env
echo "L0_CL_P2P_HTTP_PORT=9001" >> .env
echo "L0_CL_CLI_HTTP_PORT=9002" >> .env

# External ports
echo "CL_DOCKER_EXTERNAL_L0_PUBLIC=9000" >> .env
echo "CL_DOCKER_EXTERNAL_L0_P2P=9001" >> .env
echo "CL_DOCKER_EXTERNAL_L0_CLI=9002" >> .env

# Internal Ports
echo "L1_CL_PUBLIC_HTTP_PORT=9010" >> .env
echo "L1_CL_P2P_HTTP_PORT=9011" >> .env
echo "L1_CL_CLI_HTTP_PORT=9012" >> .env

# External ports
echo "CL_DAG_L1_PUBLIC_PORT=9010" >> .env
echo "CL_DAG_L1_P2P_PORT=9011" >> .env
echo "CL_DAG_L1_CLI_PORT=9012" >> .env

cd ../../

export DAG_L1_PEER_ID_0=$GL0_GENERATED_WALLET_PEER_ID

# dag-l1 1

for i in 1 2; do
  cd ./nodes/$i
  echo "CL_DAG_L1_JOIN_ENABLED=true" >> .env
  echo "CL_DAG_L0_JOIN_ENABLED=true" >> .env
  echo "CONTAINER_NAME_SUFFIX=-$i" >> .env
  echo "CONTAINER_OFFSET=$i" >> .env

  # External ports
  echo "CL_DOCKER_EXTERNAL_L0_PUBLIC=${i}9000" >> .env
  echo "CL_DAG_L1_PUBLIC_PORT=${i}9010" >> .env
  echo "CL_DOCKER_EXTERNAL_L0_P2P=${i}9001" >> .env
  echo "CL_DAG_L1_P2P_PORT=${i}9011" >> .env
  echo "CL_DOCKER_EXTERNAL_L0_CLI=${i}9002" >> .env
  echo "CL_DAG_L1_CLI_PORT=${i}9012" >> .env

  # These are only required on systems that implement docker with a host networking bridge
  # Port conflicts cause it to fail with external networks that re-use ports
  # internal ports
  echo "L0_CL_PUBLIC_HTTP_PORT=${i}9000" >> .env
  echo "L0_CL_P2P_HTTP_PORT=${i}9001" >> .env
  echo "L0_CL_CLI_HTTP_PORT=${i}9002" >> .env
  echo "L1_CL_PUBLIC_HTTP_PORT=${i}9010" >> .env
  echo "L1_CL_P2P_HTTP_PORT=${i}9011" >> .env
  echo "L1_CL_CLI_HTTP_PORT=${i}9012" >> .env

  out=$(
    source .envrc
    java -jar ../keytool.jar generate
  )

  ret_addr=$(
    source .envrc
    java -jar ../wallet.jar show-address
  )
  echo "$ret_addr" > address
  id=$(
    source .envrc
    java -jar ../wallet.jar show-id
  )
  echo "$id" > peer_id

  cd ../../
done


echo "------------------------------------------------"
echo "All deployment configurations now generated, proceeding to run cluster"
echo "------------------------------------------------"

docker network create \
  --driver=bridge \
  --subnet=${NET_PREFIX}.0/24 \
  tessellation_common


for i in 0 1 2; do
  cd ./nodes/$i/
  docker compose down --remove-orphans --volumes || true > /dev/null 2>&1; \
  cp ../../docker/docker-compose.yaml . ; \
  cp ../../docker/docker-compose.test.yaml . ; \
  docker compose -f docker-compose.test.yaml -f docker-compose.yaml --profile l0 up -d
  cd ../../
done

DOCKER_STARTED_TIME=$(date +%s)

echo "Docker started at $DOCKER_STARTED_TIME"

DELTA_SECONDS=$((DOCKER_STARTED_TIME - START_TIME))
echo "Docker started in $DELTA_SECONDS seconds"
