
export GL0_GENERATED_WALLET_PEER_ID=$(cat ./nodes/0/peer_id)
echo "Generated GL0 wallet peer id $GL0_GENERATED_WALLET_PEER_ID"
export ADDRESS=$(cat ./nodes/0/address)
echo "Generated GL0 wallet address $ADDRESS"


#CL_GLOBAL_L0_PEER_ID", help = "Global L0 peer Id"))
#CL_GLOBAL_L0_PEER_HTTP_HOST", help = "Global L0 peer HTTP host"))
# CL_GLOBAL_L0_PEER_HTTP_PORT", help = "Global L0 peer HTTP port

cat << EOF > ./nodes/.env
CL_APP_ENV="dev"
CL_COLLATERAL=0
TESSELLATION_DOCKER_VERSION=test
CL_DOCKER_METAGRAPH_IMAGE=test
CL_TEST_MODE=true
CL_LOCAL_MODE=true
NET_PREFIX=${NET_PREFIX}
CL_DOCKER_BIND_INTERFACE=${CL_DOCKER_BIND_INTERFACE}

CL_GLOBAL_L0_PEER_ID=${GL0_GENERATED_WALLET_PEER_ID}
CL_GLOBAL_L0_PEER_HTTP_HOST=${NET_PREFIX}.10
CL_GLOBAL_L0_PEER_HTTP_PORT=${DAG_L0_PORT_PREFIX}00

# These must be unique for each service, this is used by GL1 for example to hit GL0
# CL_L0_PEER_ID=$GL0_GENERATED_WALLET_PEER_ID
CL_DOCKER_GL0_PEER_ID=$GL0_GENERATED_WALLET_PEER_ID
# CL_L0_PEER_HTTP_HOST=${NET_PREFIX}.10
CL_DOCKER_GL0_PEER_HTTP_HOST=${NET_PREFIX}.10
# CL_L0_PEER_HTTP_PORT=${DAG_L0_PORT_PREFIX}00
CL_DOCKER_GL0_PEER_HTTP_PORT=${DAG_L0_PORT_PREFIX}00

# This is used by CL1/DL1 to hit ML0
CL_DOCKER_ML0_PEER_ID=$GL0_GENERATED_WALLET_PEER_ID
CL_DOCKER_ML0_PEER_HTTP_HOST=${NET_PREFIX}.30
CL_DOCKER_ML0_PEER_HTTP_PORT=${ML0_PORT_PREFIX}00


CL_DOCKER_GL0_JOIN_ID=$GL0_GENERATED_WALLET_PEER_ID
CL_DOCKER_GL1_JOIN_ID=$GL0_GENERATED_WALLET_PEER_ID
CL_DOCKER_ML0_JOIN_ID=$GL0_GENERATED_WALLET_PEER_ID
CL_DOCKER_CL1_JOIN_ID=$GL0_GENERATED_WALLET_PEER_ID
CL_DOCKER_DL1_JOIN_ID=$GL0_GENERATED_WALLET_PEER_ID

CL_DOCKER_GL0_JOIN_IP=${NET_PREFIX}.10
CL_DOCKER_GL1_JOIN_IP=${NET_PREFIX}.20
CL_DOCKER_ML0_JOIN_IP=${NET_PREFIX}.30
CL_DOCKER_CL1_JOIN_IP=${NET_PREFIX}.40
CL_DOCKER_DL1_JOIN_IP=${NET_PREFIX}.50

CL_DOCKER_GL0_JOIN_PORT=${DAG_L0_PORT_PREFIX}01
CL_DOCKER_GL1_JOIN_PORT=${DAG_L1_PORT_PREFIX}01
CL_DOCKER_ML0_JOIN_PORT=${ML0_PORT_PREFIX}01
CL_DOCKER_CL1_JOIN_PORT=${CL1_PORT_PREFIX}01
CL_DOCKER_DL1_JOIN_PORT=${DL1_PORT_PREFIX}01

EOF


if [ -f "${EXTRA_ENV_PATH:-/dev/null}" ]; then
  EXTRA_ENV=$(cat $EXTRA_ENV_PATH)
  echo "Extra env: $EXTRA_ENV"
  echo "$EXTRA_ENV" >> ./nodes/.env
fi

# maybe re-enable later -- these are the current mainnet defaults
# CL_DOCKER_JAVA_OPTS="-Xms512M -Xss256K -Xmx8192M"

# ClickHouse logging (optional — enabled when CLICKHOUSE_HOST is set)
if [ -n "$CLICKHOUSE_HOST" ]; then
  cat >> ./nodes/.env <<CHEOF

CLICKHOUSE_HOST=${CLICKHOUSE_HOST}
CLICKHOUSE_USER=${CLICKHOUSE_USER:-default}
CLICKHOUSE_PASSWORD=${CLICKHOUSE_PASSWORD}
CLICKHOUSE_PORT=${CLICKHOUSE_PORT:-8443}
CLICKHOUSE_DATABASE=${CLICKHOUSE_DATABASE:-default}
CLICKHOUSE_PROTOCOL=${CLICKHOUSE_PROTOCOL:-https}
CLICKHOUSE_LOGS_TABLE_NAME=${CLICKHOUSE_LOGS_TABLE_NAME:-nightly_logs}
CHEOF
fi

# Append any CL_TEST_* environment variables from the current bash environment
echo "" >> ./nodes/.env
echo "# Test environment variables from host" >> ./nodes/.env
if env | grep -q "^CL_TEST_"; then
  env | grep "^CL_TEST_" | while IFS= read -r line; do
    echo "$line" >> ./nodes/.env
  done
fi

for i in $(seq 0 $((MAX_NODES - 1))); do
  cp ./nodes/.env ./nodes/$i/.env
  cp ./nodes/.envrc ./nodes/$i/.envrc
done

if [ "$SET_FAILURE_BREAKPOINT_TIME" == "true" ]; then
  # Default failure time +100 seconds, adjust if needed.
  FAILURE_TIME=$(($(date +%s) + 100))
  echo "CL_TEST_SIMULATE_GOSSIP_FAIL_TIME=$FAILURE_TIME" >> ./nodes/1/.env
  echo "Setting gossip failure simulation to trigger at $(date -d @$FAILURE_TIME 2>/dev/null || date -r $FAILURE_TIME) (100 seconds from now) $FAILURE_TIME"
fi


cp ./.github/config/genesis.csv ./nodes/0/genesis.csv

cd ./nodes/0/
# 1000000 * 1e8
# Ensure the file ends with a newline before appending
echo "" >> genesis.csv
echo "$ADDRESS,100000000000000" >> genesis.csv
echo "Generated genesis file:"
cat genesis.csv
# sed -i.bak '${\n/^$/d\n}' genesis.csv && rm -f genesis.csv.bak
echo "CL_GENESIS_FILE=./genesis.csv" >> .env

cd ../../



for i in $(seq 0 $((MAX_NODES - 1))); do
  cd ./nodes/$i

  # if i != 0:
  if [ "$i" != "0" ]; then
    echo "CL_DOCKER_GL1_JOIN=true" >> .env
    echo "CL_DOCKER_GL0_JOIN=true" >> .env
    echo "CL_DOCKER_ML0_JOIN=true" >> .env
    echo "CL_DOCKER_CL1_JOIN=true" >> .env
    echo "CL_DOCKER_DL1_JOIN=true" >> .env
  else
    echo "CL_DOCKER_GL1_JOIN=false" >> .env
    echo "CL_DOCKER_GL0_JOIN=false" >> .env
    echo "CL_DOCKER_ML0_JOIN=false" >> .env
    echo "CL_DOCKER_CL1_JOIN=false" >> .env
    echo "CL_DOCKER_DL1_JOIN=false" >> .env
    echo "CL_DOCKER_GL0_GENESIS=true" >> .env
    echo "CL_DOCKER_GL1_GENESIS=true" >> .env
    echo "CL_DOCKER_ML0_GENESIS=true" >> .env
    echo "CL_DOCKER_CL1_GENESIS=true" >> .env
    echo "CL_DOCKER_DL1_GENESIS=true" >> .env
    # Rollback settings only for genesis node - it does run-rollback
    # Followers use run-validator and join/download from the leader
    if [ "$ROLLBACK_MODE" == "true" ]; then
      echo "CL_DOCKER_ROLLBACK=true" >> .env
      if [ -n "$ROLLBACK_HASH" ]; then
        echo "CL_DOCKER_ROLLBACK_HASH=$ROLLBACK_HASH" >> .env
      fi
    fi
  fi

  echo "CONTAINER_NAME_SUFFIX=-$i" >> .env
  echo "CONTAINER_OFFSET=$i" >> .env

  L0_PORT="$DAG_L0_PORT_PREFIX$i"
  L1_PORT="$DAG_L1_PORT_PREFIX$i"
  ML0_PORT="$ML0_PORT_PREFIX$i"
  CL1_PORT="$CL1_PORT_PREFIX$i"
  DL1_PORT="$DL1_PORT_PREFIX$i"

  # External ports
  echo "CL_DOCKER_EXTERNAL_GL0_PUBLIC=${L0_PORT}0" >> .env
  echo "CL_DOCKER_EXTERNAL_GL0_P2P=${L0_PORT}1" >> .env
  echo "CL_DOCKER_EXTERNAL_GL0_CLI=${L0_PORT}2" >> .env

  echo "CL_DOCKER_EXTERNAL_GL1_PUBLIC=${L1_PORT}0" >> .env
  echo "CL_DOCKER_EXTERNAL_GL1_P2P=${L1_PORT}1" >> .env
  echo "CL_DOCKER_EXTERNAL_GL1_CLI=${L1_PORT}2" >> .env

  echo "CL_DOCKER_EXTERNAL_ML0_PUBLIC=${ML0_PORT}0" >> .env
  echo "CL_DOCKER_EXTERNAL_ML0_P2P=${ML0_PORT}1" >> .env
  echo "CL_DOCKER_EXTERNAL_ML0_CLI=${ML0_PORT}2" >> .env

  echo "CL_DOCKER_EXTERNAL_CL1_PUBLIC=${CL1_PORT}0" >> .env
  echo "CL_DOCKER_EXTERNAL_CL1_P2P=${CL1_PORT}1" >> .env
  echo "CL_DOCKER_EXTERNAL_CL1_CLI=${CL1_PORT}2" >> .env

  echo "CL_DOCKER_EXTERNAL_DL1_PUBLIC=${DL1_PORT}0" >> .env
  echo "CL_DOCKER_EXTERNAL_DL1_P2P=${DL1_PORT}1" >> .env
  echo "CL_DOCKER_EXTERNAL_DL1_CLI=${DL1_PORT}2" >> .env

  # These are only required on systems that implement docker with a host networking bridge
  # Port conflicts cause it to fail with external networks that re-use ports
  # internal ports
  echo "CL_DOCKER_INTERNAL_GL0_PUBLIC=${L0_PORT}0" >> .env
  echo "CL_DOCKER_INTERNAL_GL0_P2P=${L0_PORT}1" >> .env
  echo "CL_DOCKER_INTERNAL_GL0_CLI=${L0_PORT}2" >> .env

  echo "CL_DOCKER_INTERNAL_GL1_PUBLIC=${L1_PORT}0" >> .env
  echo "CL_DOCKER_INTERNAL_GL1_P2P=${L1_PORT}1" >> .env
  echo "CL_DOCKER_INTERNAL_GL1_CLI=${L1_PORT}2" >> .env

  echo "CL_DOCKER_INTERNAL_ML0_PUBLIC=${ML0_PORT}0" >> .env
  echo "CL_DOCKER_INTERNAL_ML0_P2P=${ML0_PORT}1" >> .env
  echo "CL_DOCKER_INTERNAL_ML0_CLI=${ML0_PORT}2" >> .env

  echo "CL_DOCKER_INTERNAL_CL1_PUBLIC=${CL1_PORT}0" >> .env
  echo "CL_DOCKER_INTERNAL_CL1_P2P=${CL1_PORT}1" >> .env
  echo "CL_DOCKER_INTERNAL_CL1_CLI=${CL1_PORT}2" >> .env
  
  echo "CL_DOCKER_INTERNAL_DL1_PUBLIC=${DL1_PORT}0" >> .env
  echo "CL_DOCKER_INTERNAL_DL1_P2P=${DL1_PORT}1" >> .env
  echo "CL_DOCKER_INTERNAL_DL1_CLI=${DL1_PORT}2" >> .env

  echo "CL_DOCKER_GL1_JOIN_INITIAL_DELAY=$((i*12 + 30))" >> .env
  echo "CL_DOCKER_DL1_JOIN_INITIAL_DELAY=$((i*12 + 30))" >> .env
  echo "CL_DOCKER_CL1_JOIN_INITIAL_DELAY=$((i*12 + 30))" >> .env

  cd ../../
done
