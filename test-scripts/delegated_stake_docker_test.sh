#!/bin/bash

# Break on any error
set -e

# For debugging locally use 0 for ci use 1
export EXIT_CODE=0
export CLEAN_BUILD=true

# Remove extra clean if needed
docker stop $(docker ps -q -f name=global-l0*) || true
docker stop $(docker ps -q -f name=dag-l1*) || true
docker rm $(docker ps -q -f name=global-l0*) || true
docker rm $(docker ps -q -f name=dag-l1*) || true

# Ensure clean setup
rm -rf ./nodes

mkdir -p ./nodes/global-l0/0
mkdir -p ./nodes/dag-l1/0
mkdir -p ./nodes/dag-l1/1
mkdir -p ./nodes/dag-l1/2

# Build jars, run clean only if CLEAN_BUILD is true
if [ "$CLEAN_BUILD" = "true" ]; then
  sbt clean
fi

sbt dagL0/assembly dagL1/assembly keytool/assembly wallet/assembly

# Note this copy command may fail if you recompile without clean due to the *dirty* suffix, fixable with env
cp modules/dag-l0/target/scala-2.13/tessellation-dag-l0-assembly-*.jar ./nodes/global-l0.jar
cp modules/dag-l1/target/scala-2.13/tessellation-dag-l1-assembly-*.jar ./nodes/dag-l1.jar
cp modules/keytool/target/scala-2.13/tessellation-keytool-assembly-*.jar ./nodes/keytool.jar
cp modules/wallet/target/scala-2.13/tessellation-wallet-assembly-*.jar ./nodes/wallet.jar

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
export CL_COLLATERAL=0
export CL_EXTERNAL_IP=127.0.0.1
EOF

cat << EOF > ./nodes/.env
CL_APP_ENV="dev"
CL_COLLATERAL=0
TESSELLATION_DOCKER_VERSION=test
CL_TEST_MODE=true
CL_LOCAL_MODE=true
EOF

cp ./nodes/.envrc ./nodes/global-l0/0/.envrc
cp ./nodes/.envrc ./nodes/dag-l1/1/.envrc
cp ./nodes/.envrc ./nodes/dag-l1/2/.envrc
cp ./nodes/.env ./nodes/global-l0/0/.env
cp ./nodes/.env ./nodes/dag-l1/1/.env
cp ./nodes/.env ./nodes/dag-l1/2/.env

cd ./nodes/global-l0/0/

# Unsafe source kept in subshell
out=$(
  source .envrc
  java -jar ../../keytool.jar generate
  mv key-0.p12 key.p12
  java -jar ../../wallet.jar show-id
)
export GL0_GENERATED_WALLET_PEER_ID=$out
echo "Generated GL0 wallet peer id $GL0_GENERATED_WALLET_PEER_ID"
echo $GL0_GENERATED_WALLET_PEER_ID > peer_id
echo "CL_L0_PEER_ID=$GL0_GENERATED_WALLET_PEER_ID" >> .env
echo "CL_L0_PEER_HTTP_HOST=172.19.0.3" >> .env


ret_addr=$(
  source .envrc
  java -jar ../../wallet.jar show-address
)
export ADDRESS=$ret_addr
echo "$ADDRESS" > address.txt
echo "Generated GL0 wallet address $ADDRESS"

# 1000000 * 1e8
echo "$ADDRESS,100000000000000" > genesis.csv

echo "Generated genesis file:"
cat genesis.csv
echo "CL_GENESIS_FILE=./genesis.csv" >> .env
echo "CL_DAG_L1_JOIN_ENABLED=false" >> .env
cd ../../../



export DAG_L1_PEER_ID_0=$GL0_GENERATED_WALLET_PEER_ID

# dag-l1 1

cd ./nodes/dag-l1/1
cat << EOF >> .env
CL_DAG_L1_PUBLIC_PORT=19010
CL_DAG_L1_PEER_PORT=19011
CL_DAG_L1_CLI_PORT=19012
EOF
echo "CL_L0_PEER_ID=$GL0_GENERATED_WALLET_PEER_ID" >> .env
echo "CL_L0_PEER_HTTP_HOST=host.docker.internal" >> .env
echo "CL_DAG_L1_JOIN_ID=$DAG_L1_PEER_ID_0" >> .env
echo "CONTAINER_NAME_SUFFIX=-1" >> .env
out=$(
  source .envrc
  java -jar ../../keytool.jar generate
)
cd ../../../

# dag-l1 2

cd ./nodes/dag-l1/2
cat << EOF >> .env
CL_DAG_L1_PUBLIC_PORT=29010
CL_DAG_L1_PEER_PORT=29011
CL_DAG_L1_CLI_PORT=29012
EOF
echo "CL_L0_PEER_ID=$GL0_GENERATED_WALLET_PEER_ID" >> .env
echo "CL_DAG_L1_JOIN_ID=$DAG_L1_PEER_ID_0" >> .env
echo "CONTAINER_NAME_SUFFIX=-2" >> .env
out=$(
  source .envrc
  java -jar ../../keytool.jar generate
)
cd ../../../


echo "------------------------------------------------"
echo "All deployment configurations now generated, proceeding to run cluster"
echo "------------------------------------------------"

# Start GL0 and dag-l1 0
cp docker/docker-compose.yaml ./nodes/global-l0/0/docker-compose.yaml
cd ./nodes/global-l0/0


docker stop $(docker ps -a -q) || true; \
docker rm $(docker ps -a -q) || true; \
sudo rm -rf l0-data/ l1-data/; \
docker compose down; docker compose rm; \
cp ../../../docker/docker-compose.yaml . ; \
docker compose --profile l0 up -d

export DAG_L1_HOST_INTERNAL=$(docker exec -it dag-l1 /bin/bash -c "getent hosts dag-l1 | cut -d' ' -f1")
export DAG_L0_HOST_INTERNAL=$(docker exec -it global-l0 /bin/bash -c "getent hosts global-l0 | cut -d' ' -f1")
echo "DAG_L1_HOST_INTERNAL: $DAG_L1_HOST_INTERNAL"
echo "DAG_L0_HOST_INTERNAL: $DAG_L0_HOST_INTERNAL"

 docker exec -it dag-l1 /bin/bash -c 'apt install curl -y; curl global-l0:9000/cluster/info'
 docker exec -it dag-l1 /bin/bash -c 'curl 172.19.0.3:9000/cluster/info'
 docker exec -it dag-l1 /bin/bash -c 'getent hosts global-l0'
 docker exec -it dag-l1 /bin/bash -c 'getent hosts dag-l1'
 docker exec -it dag-l1 /bin/bash -c 'getent hosts dag-l1'
 docker exec -it dag-l1 env
docker exec -it dag-l1-1 /bin/bash

docker exec -it dag-l1 /bin/bash -c 'curl host.docker.internal:9000/cluster/info'
docker exec -it dag-l1 /bin/bash -c 'curl 172.19.0.1:9000/cluster/info'
docker exec -it dag-l1 grep host.docker.internal /etc/hosts


cd ../../..

# wait for GL0 to come online
sleep 30

# Start dag-l1 1
cd ./nodes/dag-l1/1


docker stop $(docker ps -a -q -f name=dag-l1-1*) || true; \
docker rm $(docker ps -a -q -f name=dag-l1-1*) || true; \
sudo rm -rf l0-data/ l1-data/; \
docker compose down --remove-orphans; docker compose rm; \
cp ../../../docker/docker-compose.yaml . ; \
docker compose up 

docker exec -it dag-l1-1 /bin/bash -c "cat /etc/hosts"

docker exec -it dag-l1 /bin/bash -c "cat /etc/hosts"
docker exec -it dag-l1-1 /bin/bash -c 'getent hosts dag-l1'

docker logs dag-l1-join-coordinator-1

cd ../../../

# Start dag-l1 2
cd ./nodes/dag-l1/2
docker compose -f ../../../docker/docker-compose.yaml up -d
cd ../../../

# wait for dag-l1 to come online

sleep 30

# export PEER_ID=$(cat ./nodes/dag-l1/0/peer_id)

# echo "Starting join to $PEER_ID"
# # First join
# curl -i -X POST --header 'Content-Type: application/json' \
#   -d "{\"id\":\"$PEER_ID\",\"ip\":\"127.0.0.1\",\"p2pPort\":19991}" \
#   localhost:29992/cluster/join

# # Await joined.
# # Bug in L1 joining, latter two peers do not recognize / acknowledge each other.
# # 21:35:32.707 [io-compute-8] [31mWARN [0;39m [36mi.c.n.s.h.p.c.S.$anon[0;39m - Join request rejected due to: Node is not part of the cluster.
# # If this sleep is not present, two peers cannot join at the exact same time, despite
# # using the same coordinator node.
# sleep 10

# # Second join
# curl -i -X POST --header 'Content-Type: application/json' \
#   -d "{\"id\":\"$PEER_ID\",\"ip\":\"127.0.0.1\",\"p2pPort\":19991}" \
#   localhost:39992/cluster/join

# Await joined.
# sleep 10

for i in 1 2 3; do
    port="${i}9990"
    curl -s http://localhost:${port}/cluster/info | \
    jq -e 'length > 2' > /dev/null || { echo "ERROR: dag-l1 $i doesn't have 3 nodes"; exit $EXIT_CODE; }
done


# ### CLUSTER SPECIFIC TESTING BELOW
# echo "Starting cluster test"

# export DAG_L0_URL="http://localhost:9000"
# export DAG_L1_URL="http://localhost:29990"

# # Create node update params for gl0 kp
# cd ./nodes/global-l0/0/
# # 6000 * 1e8
# out=$(
#   source .envrc
#   java -jar ../../wallet.jar create-node-params
# )
# echo "Create node params output $out"
# cat event
# cp event initial-node-params.json
# curl -i -X POST --header 'Content-Type: application/json' --data @initial-node-params.json "$DAG_L0_URL"/node-params
# # Await accepted
# sleep 30
# cd ../../..

# curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
# jq -e '.[1].updateNodeParameters | length > 0' > /dev/null || \
# { echo "ERROR: updateNodeParameters is empty in snapshot combined"; exit $EXIT_CODE; }

# # Create token lock for gl0 kp
# cd ./nodes/global-l0/0/

# out=$(
#   source .envrc
#   java -jar ../../wallet.jar create-token-lock --amount 6000
# )
# echo "Create token lock output hash $out"
# cat event
# cp event initial-token-lock.json
# export TOKEN_LOCK_HASH=$out
# echo "Initial token lock hash reference $TOKEN_LOCK_HASH"
# curl -i -X POST --header 'Content-Type: application/json' --data @initial-token-lock.json "$DAG_L1_URL"/token-locks
# # Await accepted, may require adjustment
# sleep 40
# cd ../../..

# curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
# jq -e '.[1].activeTokenLocks | length == 1' > /dev/null || \
# { echo "ERROR: activeTokenLocks is empty in snapshot combined"; exit $EXIT_CODE; }


# # Create delegated stake for gl0 kp

# cd ./nodes/global-l0/0/
# out=$(
#   source .envrc
#   java -jar ../../wallet.jar create-delegated-stake --amount 6000 --token-lock $TOKEN_LOCK_HASH
# )
# echo "Create delegated stake hash $out"
# export DELEGATED_STAKE_HASH=$out
# cat event
# cp event initial-delegated-stake.json
# curl -i -X POST --header 'Content-Type: application/json' --data @initial-delegated-stake.json "$DAG_L0_URL"/delegated-stakes
# # Await accepted, may require adjustment
# cd ../../..
# sleep 30

# curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
# jq -e '.[1].activeDelegatedStakes | length == 1' > /dev/null || \
# { echo "ERROR: activeDelegatedStakes is empty in snapshot combined"; exit $EXIT_CODE; }

# sleep 30

# curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
# jq -e '.[0].delegateRewards != {}' > /dev/null || \
# { echo "ERROR: delegateRewards is empty in snapshot combined"; exit $EXIT_CODE; }

# curl -s "$DAG_L0_URL/delegated-stakes/$ADDRESS/info" | \
# jq -e '.activeDelegatedStakes | length == 1' > /dev/null || \
# { echo "ERROR: activeDelegatedStakes is empty in DS info endpoint"; exit $EXIT_CODE; }



# # initiate withdraw
# cd ./nodes/global-l0/0/
# out=$(
#   source .envrc
#   java -jar ../../wallet.jar withdraw-delegated-stake --stake-ref "$DELEGATED_STAKE_HASH"
# )
# echo "Withdraw delegated stake output $out"
# cat event
# cp event withdraw-delegated-stake.json
# curl -i -X PUT --header 'Content-Type: application/json' --data @withdraw-delegated-stake.json "$DAG_L0_URL"/delegated-stakes
# # Await accepted, may require adjustment
# cd ../../..


# sleep 30

# #export DELEGATED_STAKE_HASH=1882ba3eea3d26576eb5e15e35b50f25271e78bec8583a5df2353a625f68cbd7
# export DAG_L0_URL="http://localhost:9000"
# export ADDRESS="DAG3BrJT6tU7qFUBNbk29WLnyq78djT17amQ1YvX"

# # Check if this below is equal to current.
# expected_end=$(curl -s "$DAG_L0_URL/delegated-stakes/$ADDRESS/info" | \
# jq -e ".pendingWithdrawals[0].withdrawalEndEpoch")

# current_epoch=$(curl -s "$DAG_L0_URL/global-snapshots/latest/combined" | \
# jq -e '.[0].value.epochProgress')
# echo "Current epoch $current_epoch expected withdrawal $expected_end"



# First error, after withdrawal, not removing empty list for address in snapshot info
# activeDelegatedStakes":{"DAG1vmb6wbdKgMRite7nTmp5Di8mT5ZqjRw6KNTc":[]}
# uncomment to reproduce error
#
#echo "Verifying activeDelegatedStakes output"
#
#curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
#jq -e '.[1].activeDelegatedStakes'
#
## Active token locks has same issue, looks like above
#
##curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
##jq -e '.[1].activeDelegatedStakes | length == 0' > /dev/null || \
##{ echo "ERROR: activeDelegatedStakes is not empty in snapshot combined"; exit $EXIT_CODE; }
#
#echo "Verifying delegateRewards output"
#
#curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
#jq -e '.[0].delegateRewards'
#
#echo "Verifying delegateRewards null"
#
#curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
#jq -e '.[0].delegateRewards == null' > /dev/null || \
#{ echo "ERROR: delegateRewards is not empty in snapshot combined"; exit $EXIT_CODE; }
#
#echo "Verifying delegated stake info"
#
#curl -s "$DAG_L0_URL/delegated-stakes/$ADDRESS/info" | \
#jq -e '.activeDelegatedStakes | length == 0' > /dev/null || \
#{ echo "ERROR: activeDelegatedStakes is not empty in DS info endpoint"; exit $EXIT_CODE; }
#

#active_token_locks=$(curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
#jq -e '.[1].activeTokenLocks | length')
#
#if [ "$active_token_locks" -eq 1 ]; then
#  echo "Test passed: activeTokenLocks length is 1"
#else
#  echo "Test failed: activeTokenLocks length is not 1"
##  exit 1
#fi


echo "success"
# Notes:

# Manual kill commands in case of script error:
# ps aux | grep global-l0
# pkill -f dag-l1
# pkill -f global-l0

# Manual data removal commands in case of quick re-run:
# rm -rf ./nodes/global-l0/0/data