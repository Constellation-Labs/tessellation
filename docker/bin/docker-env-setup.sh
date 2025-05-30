
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
CL_L0_PEER_HTTP_PORT=${DAG_L0_PORT_PREFIX}00
NET_PREFIX=${NET_PREFIX}
CL_DOCKER_BIND_INTERFACE=${CL_DOCKER_BIND_INTERFACE}
CL_DAG_L1_JOIN_PORT=${DAG_L1_PORT_PREFIX}01
CL_DAG_L0_JOIN_PORT=${DAG_L0_PORT_PREFIX}01
EOF

echo "CL_L0_PEER_ID=$GL0_GENERATED_WALLET_PEER_ID" >> ./nodes/.env
echo "CL_DAG_L1_JOIN_ID=$GL0_GENERATED_WALLET_PEER_ID" >> ./nodes/.env
echo "CL_DAG_L0_JOIN_ID=$GL0_GENERATED_WALLET_PEER_ID" >> ./nodes/.env

for i in 0 1 2; do
  cp ./nodes/.env ./nodes/$i/.env
  cp ./nodes/.envrc ./nodes/$i/.envrc
done


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



for i in 0 1 2; do
  cd ./nodes/$i

  # if i != 0:
  if [ "$i" != "0" ]; then
    echo "CL_DAG_L1_JOIN_ENABLED=true" >> .env
    echo "CL_DAG_L0_JOIN_ENABLED=true" >> .env
  else
    echo "CL_DAG_L1_JOIN_ENABLED=false" >> .env
    echo "CL_DAG_L0_JOIN_ENABLED=false" >> .env
  fi

  echo "CONTAINER_NAME_SUFFIX=-$i" >> .env
  echo "CONTAINER_OFFSET=$i" >> .env

  L0_PORT="$DAG_L0_PORT_PREFIX$i"
  L1_PORT="$DAG_L1_PORT_PREFIX$i"

  # External ports
  echo "CL_DOCKER_EXTERNAL_L0_PUBLIC=${L0_PORT}0" >> .env
  echo "CL_DOCKER_EXTERNAL_L1_PUBLIC=${L1_PORT}0" >> .env
  echo "CL_DOCKER_EXTERNAL_L0_P2P=${L0_PORT}1" >> .env
  echo "CL_DOCKER_EXTERNAL_L1_P2P=${L1_PORT}1" >> .env
  echo "CL_DOCKER_EXTERNAL_L0_CLI=${L0_PORT}2" >> .env
  echo "CL_DOCKER_EXTERNAL_L1_CLI=${L1_PORT}2" >> .env

  # These are only required on systems that implement docker with a host networking bridge
  # Port conflicts cause it to fail with external networks that re-use ports
  # internal ports
  echo "L0_CL_PUBLIC_HTTP_PORT=${L0_PORT}0" >> .env
  echo "L0_CL_P2P_HTTP_PORT=${L0_PORT}1" >> .env
  echo "L0_CL_CLI_HTTP_PORT=${L0_PORT}2" >> .env
  echo "L1_CL_PUBLIC_HTTP_PORT=${L1_PORT}0" >> .env
  echo "L1_CL_P2P_HTTP_PORT=${L1_PORT}1" >> .env
  echo "L1_CL_CLI_HTTP_PORT=${L1_PORT}2" >> .env
  echo "CL_DOCKER_L1_JOIN_DELAY=$((i*10 + 20))" >> .env

  cd ../../
done
