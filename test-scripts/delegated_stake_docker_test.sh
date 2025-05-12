#!/bin/bash

# Break on any error
set -e

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


# For debugging locally use 0 for ci use 1
if [ -z "$EXIT_CODE" ]; then
  export EXIT_CODE=0
fi

# if not found in environment
if [ -z "$CLEAN_BUILD" ]; then
  export CLEAN_BUILD=false
fi

if [ -z "$DO_EXIT" ]; then
  export DO_EXIT=false
fi

if [ -z "$L0_ONLY" ]; then
  export L0_ONLY=true
fi

if [ -z "$REMOVE_EXISTING_CONFIGS" ]; then
  export REMOVE_EXISTING_CONFIGS=true
fi

if [ -z "$SKIP_ASSEMBLY" ]; then
  export SKIP_ASSEMBLY=false
fi

if [ -z "$NET_PREFIX" ]; then
  export NET_PREFIX="172.32.0"
fi

exit_func() {
  if [ "$DO_EXIT" = "true" ]; then
    exit $EXIT_CODE
  fi
  return 0
}


# Cleanup pre-existing docker related containers
# only run this if you really messed up
# docker stop $(docker ps -a -q) && docker rm $(docker ps -a -q) && docker volume rm $(docker volume ls -q) && docker network rm $(docker network ls -q)



# 2. More thorough container cleanup with proper error handling
echo "Stopping and removing global-l0 containers..."
docker ps -a --filter name=global-l0 --format "{{.ID}}" | while read -r container_id; do
    docker stop "$container_id" 2>/dev/null || true
    docker rm -f "$container_id" 2>/dev/null || true
done

echo "Stopping and removing dag-l1 containers..."
docker ps -a --filter name=dag-l1 --format "{{.ID}}" | while read -r container_id; do
    docker stop "$container_id" 2>/dev/null || true
    docker rm -f "$container_id" 2>/dev/null || true
done

# 3. Find and kill any lingering processes binding to tessellation ports
echo "Checking for lingering processes on common ports..."
for base_port in 8999 9000 9001 9002 9010 9011 9012; do
    for prefix in "" "1" "2"; do
        port="${prefix}${base_port}"
        pid=$(lsof -i:$port -t 2>/dev/null || true)
        if [ -n "$pid" ]; then
            echo "Killing process $pid on port $port"
            kill -9 $pid 2>/dev/null || true
        fi
    done
done

# 4. Clean containers on the tessellation network with proper error handling
echo "Removing containers on tessellation_common network..."
containers=$(docker ps -a --filter network=tessellation_common --format "{{.ID}}" 2>/dev/null || echo "")
if [ -n "$containers" ]; then
    echo "$containers" | while read -r container_id; do
        docker stop "$container_id" 2>/dev/null || true
        docker rm -f "$container_id" 2>/dev/null || true
    done
fi
#
## 5. Unmount volumes before removing them (helps with stubborn volumes)
#echo "Properly unmounting tessellation volumes..."
#for vol in gl0-data dag-l1-data; do
#    for suffix in "-0" "-1" "-2"; do
#        vol="${vol}${suffix}"
#        port="${prefix}${base_port}"
#        vol_path=$(docker volume inspect --format '{{ .Mountpoint }}' $vol 2>/dev/null || echo "")
#        if [ -n "$vol_path" ]; then
#            echo "Unmounting volume path: $vol_path"
#            umount "$vol_path" 2>/dev/null || true
#        fi
#done

# 6. Remove volumes with better error handling
echo "Removing tessellation volumes..."
for vol in gl0-data dag-l1-data; do
    for suffix in "-0" "-1" "-2"; do
        vol="${vol}${suffix}"
        docker volume rm $vol 2>/dev/null || true
    done
done

# 7. Force cleanup any dangling volumes that match our pattern
echo "Cleaning up any dangling volumes..."
docker volume ls -qf dangling=true | grep -E 'gl0-data|dag-l1-data' | xargs -r docker volume rm 2>/dev/null || true

# 8. Remove the network with better error handling
echo "Removing tessellation_common network..."
docker network rm tessellation_common 2>/dev/null || true

# 9. Docker system prune - removes unused data
echo "Performing final cleanup of unused Docker resources..."
docker system prune -f 2>/dev/null || true



if [ "$REMOVE_EXISTING_CONFIGS" = "true" ]; then
  rm -rf ./nodes
fi

mkdir -p ./nodes/global-l0/0

for i in 1 2; do
  mkdir -p ./nodes/dag-l1/$i
done


if [[ "$L0_ONLY" == "false" && "$SKIP_ASSEMBLY" == "false" ]]; then
  sbt dagL0/assembly dagL1/assembly keytool/assembly wallet/assembly
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
    sbt dagL0/assembly dagL1/assembly keytool/assembly wallet/assembly
  else
    if [ "$SKIP_ASSEMBLY" == "false" ]; then
      echo "Assembling only L0"
      sbt dagL0/assembly
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

# Populate environment per node

### GL0 First

# To run this in IJ for breakpoint use:
# CL_KEYSTORE=key-0.p12;CL_KEYALIAS=alias;CL_PASSWORD=password;CL_APP_ENV=dev;CL_COLLATERAL=0

# Common environment variables

cat << EOF > ./nodes/.envrc
export CL_KEYSTORE="key.p12"
export CL_KEYALIAS="alias"
export CL_PASSWORD="password"
export CL_APP_ENV="dev"
EOF

cp ./nodes/.envrc ./nodes/global-l0/0/.envrc
cd ./nodes/global-l0/0/

# Unsafe source kept in subshell
out=$(
  source .envrc
  java -jar ../../keytool.jar generate
  java -jar ../../wallet.jar show-id
)
export GL0_GENERATED_WALLET_PEER_ID=$out
echo "Generated GL0 wallet peer id $GL0_GENERATED_WALLET_PEER_ID"
echo $GL0_GENERATED_WALLET_PEER_ID > peer_id

ret_addr=$(
  source .envrc
  java -jar ../../wallet.jar show-address
)
export ADDRESS=$ret_addr
echo "$ADDRESS" > address.txt
echo "Generated GL0 wallet address $ADDRESS"

cd ../../../

cat << EOF > ./nodes/.env
CL_APP_ENV="dev"
CL_COLLATERAL=0
TESSELLATION_DOCKER_VERSION=test
CL_TEST_MODE=true
CL_LOCAL_MODE=true
CL_L0_PEER_HTTP_HOST=${NET_PREFIX}.10
CL_DAG_L1_JOIN_IP=${NET_PREFIX}.20
CL_DAG_L0_JOIN_IP=${NET_PREFIX}.10
CL_L0_PEER_HTTP_PORT=8999
NET_PREFIX=${NET_PREFIX}
EOF

echo "CL_L0_PEER_ID=$GL0_GENERATED_WALLET_PEER_ID" >> ./nodes/.env
echo "CL_DAG_L1_JOIN_ID=$GL0_GENERATED_WALLET_PEER_ID" >> ./nodes/.env
echo "CL_DAG_L0_JOIN_ID=$GL0_GENERATED_WALLET_PEER_ID" >> ./nodes/.env



cp ./nodes/.env ./nodes/global-l0/0/.env
cp ./nodes/.envrc ./nodes/dag-l1/1/.envrc
cp ./nodes/.envrc ./nodes/dag-l1/2/.envrc
cp ./nodes/.env ./nodes/dag-l1/1/.env
cp ./nodes/.env ./nodes/dag-l1/2/.env

cd ./nodes/global-l0/0/
# 1000000 * 1e8
echo "$ADDRESS,100000000000000" > genesis.csv
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
echo "L0_CL_PUBLIC_HTTP_PORT=8999" >> .env
echo "L0_CL_P2P_HTTP_PORT=9001" >> .env
echo "L0_CL_CLI_HTTP_PORT=9002" >> .env

# External ports
echo "CL_DAG_L0_PUBLIC_PORT=8999" >> .env
echo "CL_DAG_L0_P2P_PORT=9001" >> .env
echo "CL_DAG_L0_CLI_PORT=9002" >> .env

# Internal Ports
echo "L1_CL_PUBLIC_HTTP_PORT=9010" >> .env
echo "L1_CL_P2P_HTTP_PORT=9011" >> .env
echo "L1_CL_CLI_HTTP_PORT=9012" >> .env

# External ports
echo "CL_DAG_L1_PUBLIC_PORT=9010" >> .env
echo "CL_DAG_L1_P2P_PORT=9011" >> .env
echo "CL_DAG_L1_CLI_PORT=9012" >> .env

cd ../../../

export DAG_L1_PEER_ID_0=$GL0_GENERATED_WALLET_PEER_ID

# dag-l1 1

for i in 1 2; do
  cd ./nodes/dag-l1/$i
  echo "CL_DAG_L1_JOIN_ENABLED=true" >> .env
  echo "CL_DAG_L0_JOIN_ENABLED=true" >> .env
  echo "CONTAINER_NAME_SUFFIX=-$i" >> .env
  echo "CONTAINER_OFFSET=$i" >> .env

  # External ports
  echo "CL_DAG_L0_PUBLIC_PORT=${i}9000" >> .env
  echo "CL_DAG_L1_PUBLIC_PORT=${i}9010" >> .env
  echo "CL_DAG_L0_P2P_PORT=${i}9001" >> .env
  echo "CL_DAG_L1_P2P_PORT=${i}9011" >> .env
  echo "CL_DAG_L0_CLI_PORT=${i}9002" >> .env
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
    java -jar ../../keytool.jar generate
  )

  ret_addr=$(
    source .envrc
    java -jar ../../wallet.jar show-address
  )
  echo "$ret_addr" > address
  id=$(
    source .envrc
    java -jar ../../wallet.jar show-id
  )
  echo "$id" > peer_id

  cd ../../../
done


echo "------------------------------------------------"
echo "All deployment configurations now generated, proceeding to run cluster"
echo "------------------------------------------------"

docker network create \
  --driver=bridge \
  --subnet=${NET_PREFIX}.0/24 \
  tessellation_common


cd ./nodes/global-l0/0/
docker compose down --remove-orphans --volumes || true; \
cp ../../../docker/docker-compose.yaml . ; \
cp ../../../docker/docker-compose.test.yaml . ; \
cp ../../../docker/docker-compose.profile-l0.yaml . ; \
docker compose -f docker-compose.test.yaml -f docker-compose.yaml -f docker-compose.profile-l0.yaml --profile l0 up -d
cd ../../..

# wait for GL0 to come online
sleep 30

# Start dag-l1 1
cd ./nodes/dag-l1/1

docker compose down --remove-orphans --volumes || true; \
cp ../../../docker/docker-compose.yaml . ; \
cp ../../../docker/docker-compose.test.yaml . ; \
cp ../../../docker/docker-compose.profile-l0.yaml . ; \
docker compose -f docker-compose.test.yaml -f docker-compose.yaml -f docker-compose.profile-l0.yaml --profile l0 up -d

cd ../../../

# Start dag-l1 2
cd ./nodes/dag-l1/2
docker compose down --remove-orphans --volumes || true; \
cp ../../../docker/docker-compose.yaml . ; \
cp ../../../docker/docker-compose.test.yaml . ; \
cp ../../../docker/docker-compose.profile-l0.yaml . ; \
docker compose -f docker-compose.test.yaml -f docker-compose.yaml -f docker-compose.profile-l0.yaml --profile l0 up -d
cd ../../../

# wait for dag-l1 to come online

echo "Waiting for docker containers to come online"

sleep 45

echo "Sending cluster poll health request for cluster info to check joined."
for i in 0 1 2; do
    port="${i}9010"
    res=$(curl -s http://localhost:${port}/cluster/info | \
    jq -e 'length > 2')
    echo "res: $res"
    if [ "$res" != "true" ]; then
      echo "ERROR: dag-l1 $i doesn't have 3 nodes"
      exit_func
    fi
done

### CLUSTER SPECIFIC TESTING BELOW
echo "Starting cluster test"

export DAG_L0_URL="http://localhost:8999"
export DAG_L1_URL="http://localhost:9010"

# Create node update params for gl0 kp
cd ./nodes/global-l0/0/
# 6000 * 1e8
out=$(
  source .envrc
  java -jar ../../wallet.jar create-node-params
)
echo "Create node params output $out"
cat event
cp event initial-node-params.json
curl -i -X POST --header 'Content-Type: application/json' --data @initial-node-params.json "$DAG_L0_URL"/node-params
# Await accepted
sleep 30
cd ../../..

curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e '.[1].updateNodeParameters | length > 0' > /dev/null || \
{ echo "ERROR: updateNodeParameters is empty in snapshot combined"; exit_func; }



# # Create token lock for gl0 kp
cd ./nodes/global-l0/0/

out=$(
  source .envrc
  java -jar ../../wallet.jar create-token-lock --amount 6000
)
echo "Create token lock output hash $out"
cat event
cp event initial-token-lock.json
export TOKEN_LOCK_HASH=$out
echo "Initial token lock hash reference $TOKEN_LOCK_HASH"
curl -i -X POST --header 'Content-Type: application/json' --data @initial-token-lock.json "$DAG_L1_URL"/token-locks
# Await accepted, may require adjustment
sleep 40
cd ../../..

curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e '.[1].activeTokenLocks | length == 1' > /dev/null || \
{ echo "ERROR: activeTokenLocks is empty in snapshot combined"; exit_func; }


# Create delegated stake for gl0 kp

cd ./nodes/global-l0/0/
out=$(
  source .envrc
  java -jar ../../wallet.jar create-delegated-stake --amount 6000 --token-lock $TOKEN_LOCK_HASH
)
echo "Create delegated stake hash $out"
export DELEGATED_STAKE_HASH=$out
cat event
cp event initial-delegated-stake.json
curl -i -X POST --header 'Content-Type: application/json' --data @initial-delegated-stake.json "$DAG_L0_URL"/delegated-stakes
# Await accepted, may require adjustment
cd ../../..
sleep 30

curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e '.[1].activeDelegatedStakes | length == 1' > /dev/null || \
{ echo "ERROR: activeDelegatedStakes is empty in snapshot combined"; exit_func; }

sleep 30

curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e '.[0].delegateRewards != {}' > /dev/null || \
{ echo "ERROR: delegateRewards is empty in snapshot combined"; exit_func; }

curl -s "$DAG_L0_URL/delegated-stakes/$ADDRESS/info" | \
jq -e '.activeDelegatedStakes | length == 1' > /dev/null || \
{ echo "ERROR: activeDelegatedStakes is empty in DS info endpoint"; exit_func; }



### UPDATE NODE ID test, requires a second id for node
# Change node params, first register them for second node.
# Create node update params for container 1 kp
cd ./nodes/dag-l1/1/
# 6000 * 1e8
out=$(
  source .envrc
  java -jar ../../wallet.jar create-node-params
)
echo "Create node params output $out"
cat event
cp event initial-node-params.json
curl -i -X POST --header 'Content-Type: application/json' --data @initial-node-params.json "$DAG_L0_URL"/node-params
# Await accepted
sleep 30
cd ../../..

curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e '.[1].updateNodeParameters | length > 1' > /dev/null || \
{ echo "ERROR: updateNodeParameters is empty in snapshot combined"; exit_func; }


# Now create a delegated stake with the second address
# Create delegated stake for gl0 kp

second=$(cat ./nodes/dag-l1/1/peer_id)
echo "Second node id $second"
export SECOND_NODE="$second"

cd ./nodes/global-l0/0/



wget $DAG_L0_URL/delegated-stakes/last-reference/$ADDRESS \
-O ds-last-ref.json

out=$(
  source .envrc
  java -jar ../../wallet.jar create-delegated-stake --amount 6000 --token-lock $TOKEN_LOCK_HASH --nodeId $SECOND_NODE --parent ds-last-ref.json
)
echo "Create delegated stake hash $out"
echo "$out" > delegated-stake-hash2
export DELEGATED_STAKE_HASH=$out
cat event
cp event second-delegated-stake.json

export REWARD_AMOUNT=$(curl -s "$DAG_L0_URL/delegated-stakes/$ADDRESS/info" | \
jq -e '.activeDelegatedStakes[0].rewardAmount')
echo "Current Reward amount before change $REWARD_AMOUNT"

curl -i -X POST --header 'Content-Type: application/json' --data @second-delegated-stake.json "$DAG_L0_URL"/delegated-stakes


# Await accepted, may require adjustment
cd ../../..
sleep 30

curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e '.[1].activeDelegatedStakes | length == 1' > /dev/null || \
{ echo "ERROR: activeDelegatedStakes is empty in snapshot combined"; exit_func; }



export REWARD_AMOUNT_AFTER_CHANGE=$(curl -s "$DAG_L0_URL/delegated-stakes/$ADDRESS/info" | \
jq -e '.activeDelegatedStakes[0].rewardAmount')
echo "Current Reward amount after change $REWARD_AMOUNT_AFTER_CHANGE"

# Assert that reward amount is greater than before change
if [ "$REWARD_AMOUNT_AFTER_CHANGE" -le "$REWARD_AMOUNT" ]; then
  echo "ERROR: Reward amount is not greater than before change"
  exit_func
else
  echo "Reward amount is greater than before change"
fi

# initiate withdraw
cd ./nodes/global-l0/0/
out=$(
  source .envrc
  java -jar ../../wallet.jar withdraw-delegated-stake --stake-ref "$DELEGATED_STAKE_HASH"
)
echo "Withdraw delegated stake output $out"
cat event
cp event withdraw-delegated-stake.json
curl -i -X PUT --header 'Content-Type: application/json' --data @withdraw-delegated-stake.json "$DAG_L0_URL"/delegated-stakes
# Await accepted, may require adjustment
cd ../../..

sleep 30

set -r

while true; do
  raw=$(
    curl -s "$DAG_L0_URL/delegated-stakes/$ADDRESS/info" \
      | jq -e ".pendingWithdrawals[0].withdrawalEndEpoch"
  ) || raw=null
  expected_end=$raw

  current_epoch=$(curl -s "$DAG_L0_URL/global-snapshots/latest/combined" | \
  jq -e '.[0].value.epochProgress')
  echo "Current epoch $current_epoch expected withdrawal $expected_end"

  if [ "$expected_end" == "null" ]; then
    echo "withdrawal complete"
    break
  fi

  if [ "$current_epoch" -ge "$expected_end" ]; then
    echo "Withdrawal complete"
    break
  fi

  sleep 10
done

set -e

sleep 60


export FINAL_BALANCE=$(curl -s "$DAG_L0_URL/dag/$ADDRESS/balance" | jq -e ".balance")
echo "Final balance $FINAL_BALANCE"


echo "success"
