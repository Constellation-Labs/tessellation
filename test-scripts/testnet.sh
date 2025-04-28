
export CL_KEYSTORE=$HOME/key-2.p12
export CL_PASSWORD=password
export CL_KEYALIAS=keyalias
export CL_APP_ENV=testnet

export CLUSTER=52.8.132.193

export DAG_L0_URL="http://$CLUSTER:9000"
export DAG_L1_URL="http://$CLUSTER:9010"


ret_addr=$(
  java -jar ./nodes/wallet.jar show-address
)
export ADDRESS=$ret_addr
echo $ADDRESS

out=$(
  java -jar ./nodes/wallet.jar show-id
)
export DAG_L1_PEER_ID_0="$out"
echo $out

# http://52.8.132.193:9010/token-locks/last-reference/DAG4J6gixVGKYmcZs9Wmkyrv8ERp39vxtjwbjV5Q

out=$(
  java -jar ./nodes/wallet.jar create-token-lock --amount 6000
)
echo "Create token lock output hash $out"
cat event
cp event initial-token-lock.json
export TOKEN_LOCK_HASH=$out

echo "Initial token lock hash reference $TOKEN_LOCK_HASH"
curl -i -X POST --header 'Content-Type: application/json' --data @initial-token-lock.json "$DAG_L1_URL"/token-locks
