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


source ./docker/bin/set-env.sh

./docker/bin/tessellation-docker-cleanup.sh & 
CLEANUP_PID=$!


if [ "$PURGE_CONFIG" = "true" ]; then
  rm -rf ./nodes
fi

for i in 0 1 2; do
  mkdir -p ./nodes/$i
done

assemble_all() {
  sbt dagL0/assembly dagL1/assembly keytool/assembly wallet/assembly
}

export ASSEMBLY_START_TIME=$(date +%s)

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

export ASSEMBLY_END_TIME=$(date +%s)
export ASSEMBLY_DURATION=$((ASSEMBLY_END_TIME - ASSEMBLY_START_TIME))
echo "Assembly completed in $ASSEMBLY_DURATION seconds"

mkdir -p ./docker/jars/

for module in "dag-l0" "dag-l1" "keytool" "wallet"
do
  path=$(ls -1t modules/${module}/target/scala-2.13/tessellation-${module}-assembly*.jar | head -n1)
  cp $path ./nodes/${module}.jar
  cp $path ./docker/jars/${module}.jar
done


export TESSELLATION_DOCKER_VERSION=test
docker build -t constellationnetwork/tessellation:$TESSELLATION_DOCKER_VERSION -f docker/Dockerfile .



cat << EOF > ./nodes/.envrc
export CL_KEYSTORE="key.p12"
export CL_KEYALIAS="alias"
export CL_PASSWORD="password"
export CL_APP_ENV="dev"
EOF

generate_keys() {

  for i in 0 1 2; do
    mkdir -p ./nodes/$i
    cp ./nodes/.envrc ./nodes/$i/.envrc
    cd ./nodes/$i/

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
    mkdir -p ../../docker/config/local-test-keys/$i
    cp key.p12 ../../docker/config/local-test-keys/$i
    cp address ../../docker/config/local-test-keys/$i
    cp peer_id ../../docker/config/local-test-keys/$i
    cd ../../
  done

}

populate_test_keys() {
  for i in 0 1 2; do
    cp ./docker/config/local-test-keys/$i/key.p12 ./nodes/$i/key.p12
    cp ./docker/config/local-test-keys/$i/address ./nodes/$i/address
    cp ./docker/config/local-test-keys/$i/peer_id ./nodes/$i/peer_id
  done
}

# generate_keys
populate_test_keys

export GL0_GENERATED_WALLET_PEER_ID=$(cat ./nodes/0/peer_id)
echo "Generated GL0 wallet peer id $GL0_GENERATED_WALLET_PEER_ID"
export ADDRESS=$(cat ./nodes/0/address)
echo "Generated GL0 wallet address $ADDRESS"

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
# Ensure the file ends with a newline before appending
sed -i -e '$a\' genesis.csv
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
  echo "CL_DOCKER_L1_JOIN_DELAY=$((i*30))" >> .env

  cd ../../
done


echo "------------------------------------------------"
echo "All deployment configurations now generated, proceeding to run cluster"
echo "------------------------------------------------"

# Wait for cleanup PID to finish
wait $CLEANUP_PID

docker network create \
  --driver=bridge \
  --subnet=${NET_PREFIX}.0/24 \
  tessellation_common


for i in 0 1 2; do
  cd ./nodes/$i/
  docker compose down --remove-orphans --volumes > /dev/null 2>&1 || true; \
  cp ../../docker/docker-compose.yaml . ; \
  cp ../../docker/docker-compose.test.yaml . ; \
  docker compose -f docker-compose.test.yaml -f docker-compose.yaml --profile l0 up -d
  cd ../../
done

export DOCKER_STARTED_TIME=$(date +%s)

echo "Test setup started at $DOCKER_STARTED_TIME"
DELTA_SECONDS=$((DOCKER_STARTED_TIME - START_TIME))
DELTA_SECONDS_DOCKER_ONLY=$((DOCKER_STARTED_TIME - ASSEMBLY_END_TIME))
DELTA_SECONDS_ASSEMBLY_ONLY=$((ASSEMBLY_END_TIME - START_TIME))
echo "Assembly setup completed in $DELTA_SECONDS_ASSEMBLY_ONLY seconds"
echo "Docker setup completed in $DELTA_SECONDS_DOCKER_ONLY seconds"
echo "Test setup completed in $DELTA_SECONDS seconds"

echo "------------------------------------------------"
echo "Running end-to-end tests from .github/action_scripts"
echo "------------------------------------------------"

# Check if Node.js is installed
if ! command -v node &> /dev/null; then
    echo "Node.js is required to run the end-to-end tests but it's not installed. Skipping tests."
    exit_func
fi

# Install dependencies
cd .github/action_scripts
echo "Installing Node.js dependencies..."
npm i @stardust-collective/dag4 js-sha256 axios brotli zod

source ../../docker/bin/health-check.sh
verify_healthy

# # Run the transaction tests
# echo "Running transaction tests..."
# cd "$SCRIPT_DIR/../../.github/action_scripts"
# node send_transactions/currency.js 90 91 80 81 82

# # Run delegated staking tests if they exist
# if [ -f "$SCRIPT_DIR/../../.github/action_scripts/delegated_staking/test.js" ]; then
#     echo "Running delegated staking tests..."
#     node delegated_staking/test.js 90 91 80 81 82
# fi

# echo "------------------------------------------------"
# echo "End-to-end tests completed"
# echo "------------------------------------------------"

# # Return to the original directory
# cd "$SCRIPT_DIR/../../"


