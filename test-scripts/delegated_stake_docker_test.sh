#!/bin/bash

# Break on any error
set -e

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

exit_func() {
  if [ "$DO_EXIT" = "true" ]; then
    exit $EXIT_CODE
  fi
  return 0
}

# Ensure clean setup
rm -rf ./nodes

mkdir -p ./nodes/global-l0/0

for i in 1 2; do
  mkdir -p ./nodes/dag-l1/$i
done

# Build jars, run clean only if CLEAN_BUILD is true
if [ "$CLEAN_BUILD" = "true" ]; then
  sbt clean
  DIRTY_SUFFIX=""
else
  DIRTY_SUFFIX="-dirty"
fi

sbt dagL0/assembly dagL1/assembly keytool/assembly wallet/assembly


# Note this copy command may fail if you recompile without clean due to the *dirty* suffix, fixable with env
# Duplicate copy overwrites with dirty version if only compiling one module
# Order is deliberate here for reruns
cp modules/dag-l0/target/scala-2.13/tessellation-dag-l0-assembly*.jar ./nodes/global-l0.jar
cp modules/dag-l0/target/scala-2.13/tessellation-dag-l0-assembly*${DIRTY_SUFFIX}*.jar ./nodes/global-l0.jar
cp modules/dag-l1/target/scala-2.13/tessellation-dag-l1-assembly*.jar ./nodes/dag-l1.jar
cp modules/dag-l1/target/scala-2.13/tessellation-dag-l1-assembly*${DIRTY_SUFFIX}*.jar ./nodes/dag-l1.jar
cp modules/keytool/target/scala-2.13/tessellation-keytool-assembly*.jar ./nodes/keytool.jar
cp modules/keytool/target/scala-2.13/tessellation-keytool-assembly*${DIRTY_SUFFIX}-*.jar ./nodes/keytool.jar
cp modules/wallet/target/scala-2.13/tessellation-wallet-assembly*.jar ./nodes/wallet.jar
cp modules/wallet/target/scala-2.13/tessellation-wallet-assembly*${DIRTY_SUFFIX}-*.jar ./nodes/wallet.jar

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
CL_L0_PEER_HTTP_HOST=192.168.100.10
CL_DAG_L1_JOIN_IP=192.168.100.20
EOF

echo "CL_L0_PEER_ID=$GL0_GENERATED_WALLET_PEER_ID" >> ./nodes/.env
echo "CL_DAG_L1_JOIN_ID=$GL0_GENERATED_WALLET_PEER_ID" >> ./nodes/.env

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
echo "CONTAINER_NAME_SUFFIX=-0" >> .env
echo "CONTAINER_OFFSET=0" >> .env
cd ../../../

export DAG_L1_PEER_ID_0=$GL0_GENERATED_WALLET_PEER_ID

# dag-l1 1

for i in 1 2; do
  cd ./nodes/dag-l1/$i
  echo "CONTAINER_NAME_SUFFIX=-$i" >> .env
  echo "CONTAINER_OFFSET=$i" >> .env
  echo "CL_DAG_L1_PUBLIC_PORT=${i}9010" >> .env
  echo "CL_DAG_L1_PEER_PORT=${i}9011" >> .env
  echo "CL_DAG_L1_CLI_PORT=${i}9012" >> .env
  out=$(
    source .envrc
    java -jar ../../keytool.jar generate
  )
  cd ../../../
done


echo "------------------------------------------------"
echo "All deployment configurations now generated, proceeding to run cluster"
echo "------------------------------------------------"

# Start GL0 and dag-l1 0

# only run this if you really messed up
# docker stop $(docker ps -a -q) && docker rm $(docker ps -a -q) && docker volume rm $(docker volume ls -q) && docker network rm $(docker network ls -q)
# 1. Stop & remove all containers on the tessellation_common network
docker ps -aq --filter network=tessellation_common \
  | xargs -r docker rm -f

# 2. Remove volumes named gl0-data or dag-l1-data
docker volume ls -q \
  | grep -E 'gl0-data|dag-l1-data' \
  | xargs -r docker volume rm


# 3. Now remove the network
docker network rm tessellation_common

docker network create \
  --driver=bridge \
  --subnet=192.168.100.0/24 \
  tessellation_common


cd ./nodes/global-l0/0/
docker compose down --remove-orphans --volumes || true; \
cp ../../../docker/docker-compose.yaml . ; \
cp ../../../docker/docker-compose.test.yaml . ; \
docker compose -f docker-compose.test.yaml -f docker-compose.yaml --profile l0 up -d
cd ../../..

# wait for GL0 to come online
sleep 30

# Start dag-l1 1
cd ./nodes/dag-l1/1


docker compose down --remove-orphans --volumes || true; \
cp ../../../docker/docker-compose.yaml . ; \
cp ../../../docker/docker-compose.test.yaml . ; \
docker compose -f docker-compose.test.yaml -f docker-compose.yaml up -d

cd ../../../

# Start dag-l1 2
cd ./nodes/dag-l1/2
docker compose down --remove-orphans --volumes || true; \
cp ../../../docker/docker-compose.yaml . ; \
cp ../../../docker/docker-compose.test.yaml . ; \
docker compose -f docker-compose.test.yaml -f docker-compose.yaml up -d
cd ../../../

# wait for dag-l1 to come online

sleep 45


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

export DAG_L0_URL="http://localhost:9000"
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


while true; do
  expected_end=$(curl -s "$DAG_L0_URL/delegated-stakes/$ADDRESS/info" | \
  jq -e ".pendingWithdrawals[0].withdrawalEndEpoch")

  current_epoch=$(curl -s "$DAG_L0_URL/global-snapshots/latest/combined" | \
  jq -e '.[0].value.epochProgress')
  echo "Current epoch $current_epoch expected withdrawal $expected_end"

  if [ "$expected_end" == "null" ]; then
    echo "withdrawal complete"
    break
  fi

  if [ "$current_epoch" -ge "$expected_end" ]; then
    break
  fi

  sleep 10
done

sleep 30

curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e '.[1].activeDelegatedStakes | length == 0' > /dev/null || \
{ echo "ERROR: activeDelegatedStakes is not empty in snapshot combined"; exit_func; }

curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e '.[0].delegateRewards == null' > /dev/null || \
{ echo "ERROR: delegateRewards is not empty in snapshot combined"; exit_func; }

echo "Verifying delegated stake info"

curl -s "$DAG_L0_URL/delegated-stakes/$ADDRESS/info" | \
jq -e '.activeDelegatedStakes | length == 0' > /dev/null || \
{ echo "ERROR: activeDelegatedStakes is not empty in DS info endpoint"; exit_func; }


active_token_locks=$(curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e '.[1].activeTokenLocks | length == 0') > /dev/null || \
{ echo "ERROR: activeTokenLocks is not empty in snapshot combined"; exit_func; }

echo "success"