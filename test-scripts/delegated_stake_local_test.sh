#!/bin/bash

# Break on any error
set -e

export KEEP_ALIVE=true

# Remove extra clean if needed
pkill -f dag-l1 || true
pkill -f global-l0 || true
# Function to clean up processes
cleanup() {
  echo "------------------------------------------------"
  echo "Shutting down all processes"
  echo "------------------------------------------------"

  # Kill DAG-L1 processes
  if [ -n "$DAGL1_NODE_PID_2" ]; then
    echo "Killing DAG-L1 node 2 (PID: $DAGL1_NODE_PID_2)"
    kill "$DAGL1_NODE_PID_2" || echo "Process already terminated"
  fi

  if [ -n "$DAGL1_NODE_PID_1" ]; then
    echo "Killing DAG-L1 node 1 (PID: $DAGL1_NODE_PID_1)"
    kill "$DAGL1_NODE_PID_1" || echo "Process already terminated"
  fi

  if [ -n "$DAGL1_NODE_PID_0" ]; then
    echo "Killing DAG-L1 node 0 (PID: $DAGL1_NODE_PID_0)"
    kill "$DAGL1_NODE_PID_0" || echo "Process already terminated"
  fi

  # Kill GL0 process last
  if [ -n "$GL0_NODE_PID" ]; then
    echo "Killing GL0 node (PID: $GL0_NODE_PID)"
    kill "$GL0_NODE_PID" || echo "Process already terminated"
  fi

  echo "All processes terminated"

  # Only exit if this was triggered by a signal
  if [ "$1" = "signal" ]; then
    exit 0
  fi
}

# Register the cleanup function to be called on interrupt (SIGINT)
trap 'cleanup signal' INT TERM


# Ensure clean setup
rm -rf ./nodes

mkdir -p ./nodes/global-l0/0
mkdir -p ./nodes/dag-l1/0
mkdir -p ./nodes/dag-l1/1
mkdir -p ./nodes/dag-l1/2

# Build jars, disable clean if necessary (may be required for some changes)
sbt clean
sbt dagL0/assembly dagL1/assembly keytool/assembly wallet/assembly

cp modules/dag-l0/target/scala-2.13/tessellation-dag-l0-assembly-*.jar ./nodes/global-l0.jar
cp modules/dag-l1/target/scala-2.13/tessellation-dag-l1-assembly-*.jar ./nodes/dag-l1.jar
cp modules/keytool/target/scala-2.13/tessellation-keytool-assembly-*.jar ./nodes/keytool.jar
cp modules/wallet/target/scala-2.13/tessellation-wallet-assembly-*.jar ./nodes/wallet.jar

# Populate environment per node

### GL0 First

cat << EOF > ./nodes/global-l0/0/.envrc
export CL_KEYSTORE="key-0.p12"
export CL_KEYALIAS="alias"
export CL_PASSWORD="password"
export CL_APP_ENV="dev"
export CL_COLLATERAL=0
EOF

cd ./nodes/global-l0/0/

# Unsafe source kept in subshell
out=$(
  source .envrc
  java -jar ../../keytool.jar generate
  java -jar ../../wallet.jar show-id
)
export GL0_GENERATED_WALLET_PEER_ID=$out
echo "Generated GL0 wallet peer id $GL0_GENERATED_WALLET_PEER_ID"

export GL0_GENERATED_WALLET_ADDRESS=$(
  source .envrc
  java -jar ../../wallet.jar show-address
)
echo "Generated GL0 wallet address $GL0_GENERATED_WALLET_ADDRESS"

# 1000000 * 1e8
echo "$GL0_GENERATED_WALLET_ADDRESS,100000000000000" > genesis.csv

echo "Generated genesis file:"
cat genesis.csv
cd ../../../


# dag-l1 0

cd ./nodes/dag-l1/0
cat << EOF > .envrc
export CL_KEYSTORE="key-0.p12"
export CL_KEYALIAS=alias
export CL_PASSWORD=password
export CL_APP_ENV=dev
export CL_EXTERNAL_IP=127.0.0.1
export CL_PUBLIC_HTTP_PORT=19990
export CL_P2P_HTTP_PORT=19991
export CL_CLI_HTTP_PORT=19992
export CL_L0_PEER_HTTP_HOST=localhost
export CL_L0_PEER_HTTP_PORT=9000
export CL_COLLATERAL=0
EOF
echo "export CL_L0_PEER_ID=$GL0_GENERATED_WALLET_PEER_ID" >> .envrc
export DAG_L1_PEER_ID_0=$(
  source .envrc
  java -jar ../../keytool.jar generate
  java -jar ../../wallet.jar show-id
)
echo "$DAG_L1_PEER_ID_0" > peer_id

cd ../../../


# dag-l1 1

cd ./nodes/dag-l1/1
cat << EOF > .envrc
export CL_KEYSTORE="key-0.p12"
export CL_KEYALIAS=alias
export CL_PASSWORD=password
export CL_APP_ENV=dev
export CL_EXTERNAL_IP=127.0.0.1
export CL_PUBLIC_HTTP_PORT=29990
export CL_P2P_HTTP_PORT=29991
export CL_CLI_HTTP_PORT=29992
export CL_L0_PEER_HTTP_HOST=localhost
export CL_L0_PEER_HTTP_PORT=9000
export CL_COLLATERAL=0
EOF
echo "export CL_L0_PEER_ID=$GL0_GENERATED_WALLET_PEER_ID" >> .envrc
out=$(
  source .envrc
  java -jar ../../keytool.jar generate
)
cd ../../../

# dag-l1 2

cd ./nodes/dag-l1/2
cat << EOF > .envrc
export CL_KEYSTORE="key-0.p12"
export CL_KEYALIAS=alias
export CL_PASSWORD=password
export CL_APP_ENV=dev
export CL_EXTERNAL_IP=127.0.0.1
export CL_PUBLIC_HTTP_PORT=39990
export CL_P2P_HTTP_PORT=39991
export CL_CLI_HTTP_PORT=39992
export CL_L0_PEER_HTTP_HOST=localhost
export CL_L0_PEER_HTTP_PORT=9000
export CL_COLLATERAL=0
EOF
echo "export CL_L0_PEER_ID=$GL0_GENERATED_WALLET_PEER_ID" >> .envrc
out=$(
  source .envrc
  java -jar ../../keytool.jar generate
)
cd ../../../



echo "------------------------------------------------"
echo "All deployment configurations now generated, proceeding to run cluster"
echo "------------------------------------------------"


# Start GL0
cd ./nodes/global-l0/0
export GL0_NODE_PID=$(
  source .envrc
  java -jar ../../global-l0.jar run-genesis genesis.csv >stdout_stderr.txt 2>&1 &
  echo $!
)
echo "GL0_NODE_PID=$GL0_NODE_PID"
cd ../../../

# wait for GL0 to come online
sleep 30

# Start dag-l1 0
cd ./nodes/dag-l1/0
export DAGL1_NODE_PID_0=$(
  source .envrc
  java -jar ../../dag-l1.jar run-initial-validator >stdout_stderr.txt 2>&1 &
  echo $!
)
echo "DAGL1_NODE_PID_0=$DAGL1_NODE_PID_0"
cd ../../../

# Start dag-l1 1
cd ./nodes/dag-l1/1
export DAGL1_NODE_PID_1=$(
  source .envrc
  java -jar ../../dag-l1.jar run-validator >stdout_stderr.txt 2>&1 &
  echo $!
)
echo "DAGL1_NODE_PID_1=$DAGL1_NODE_PID_1"
cd ../../../

# Start dag-l1 2
cd ./nodes/dag-l1/2
export DAGL1_NODE_PID_2=$(
  source .envrc
  java -jar ../../dag-l1.jar run-validator >stdout_stderr.txt 2>&1 &
  echo $!
)
echo "DAGL1_NODE_PID_2=$DAGL1_NODE_PID_2"
cd ../../../

# wait for dag-l1 to come online

sleep 30

export PEER_ID=$(cat ./nodes/dag-l1/0/peer_id)

echo "Starting join to $PEER_ID"
# First join
curl -i -X POST --header 'Content-Type: application/json' \
  -d "{\"id\":\"$PEER_ID\",\"ip\":\"127.0.0.1\",\"p2pPort\":19991}" \
  localhost:29992/cluster/join

# Second join
curl -i -X POST --header 'Content-Type: application/json' \
  -d "{\"id\":\"$PEER_ID\",\"ip\":\"127.0.0.1\",\"p2pPort\":19991}" \
  localhost:39992/cluster/join


# Await joined.
sleep 20


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
curl -i -X POST --header 'Content-Type: application/json' --data @initial-node-params.json localhost:9000/node-params
# Await accepted
# NEED ASSERTIONS HERE TO VERIFY ACCEPTED
sleep 30
cd ../../..

curl -s http://localhost:9000/global-snapshots/latest/combined | \
jq -e '.[1].updateNodeParameters | length > 0' > /dev/null || \
{ echo "ERROR: updateNodeParameters is empty in snapshot combined"; exit 1; }

# Create token lock for gl0 kp
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
curl -i -X POST --header 'Content-Type: application/json' --data @initial-token-lock.json localhost:29990/token-locks
# Await accepted, may require adjustment
# NEED ASSERTIONS HERE TO VERIFY ACCEPTED
sleep 30
cd ../../..

## Test is currently broken here with token-lock in stage: WAITING
#
## Create delegated stake for gl0 kp
#
#cd ./nodes/global-l0/0/
#out=$(
#  source .envrc
#  java -jar ../../wallet.jar create-delegated-stake --amount 6000 --token-lock $TOKEN_LOCK_HASH
#)
#echo "Create delegated stake output $out"
#cat event
#cp event initial-delegated-stake.json
#curl -i -X POST --header 'Content-Type: application/json' --data @initial-delegated-stake.json localhost:9000/delegated-stakes
## Await accepted, may require adjustment
## NEED ASSERTIONS HERE TO VERIFY ACCEPTED
#cd ../../..
#sleep 30

# Check if we should kill processes based on KEEP_ALIVE flag
if [ "$KEEP_ALIVE" = "false" ]; then
  cleanup "normal"
  echo "All processes terminated"
fi


# Notes:

# Manual kill commands in case of script error:
# ps aux | grep global-l0
# pkill -f dag-l1
# pkill -f global-l0

# Manual data removal commands in case of quick re-run:
# rm -rf ./nodes/global-l0/0/data