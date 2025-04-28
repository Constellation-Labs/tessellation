
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


curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e '.[1].activeTokenLocks'

curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e '.[1].activeTokenLocks["DAG4J6gixVGKYmcZs9Wmkyrv8ERp39vxtjwbjV5Q"]'

# verified here
# http://52.8.132.193:9000/delegated-stakes/last-reference/DAG4J6gixVGKYmcZs9Wmkyrv8ERp39vxtjwbjV5Q

out=$(
  java -jar ./nodes/wallet.jar create-delegated-stake --amount 6000 --token-lock $TOKEN_LOCK_HASH
)
echo "Create delegated stake hash $out"
export DELEGATED_STAKE_HASH=$out
cat event
cp event initial-delegated-stake.json
curl -i -X POST --header 'Content-Type: application/json' --data @initial-delegated-stake.json "$DAG_L0_URL"/delegated-stakes


curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e '.[1].activeDelegatedStakes'

curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e '.[1].activeDelegatedStakes["DAG4J6gixVGKYmcZs9Wmkyrv8ERp39vxtjwbjV5Q"]'

curl -s "$DAG_L0_URL/delegated-stakes/$ADDRESS/info" | \
jq -e '.activeDelegatedStakes'



# initiate withdraw
out=$(
  java -jar ./nodes/wallet.jar withdraw-delegated-stake --stake-ref "$DELEGATED_STAKE_HASH"
)
echo "Withdraw delegated stake output $out"
cat event
cp event withdraw-delegated-stake.json

curl -i -X PUT --header 'Content-Type: application/json' --data @withdraw-delegated-stake.json "$DAG_L0_URL"/delegated-stakes


curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e '.[1].delegatedStakesWithdrawals["DAG4J6gixVGKYmcZs9Wmkyrv8ERp39vxtjwbjV5Q"]'


curl -s "$DAG_L0_URL/delegated-stakes/$ADDRESS/info"


curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e '.[0].value.epochProgress'


curl -s "$DAG_L0_URL/delegated-stakes/$ADDRESS/info" | \
jq -e '.pendingWithdrawals[0].withdrawalEndEpoch'

# original on BE 1,016,802.13854941

curl -s "$DAG_L0_URL/dag/$ADDRESS/balance" | \
jq -e ".balance"



export ADDRESS=DAG4J6gixVGKYmcZs9Wmkyrv8ERp39vxtjwbjV5Q


# 28310824066204 balance oas of 1:41pm

cat app.log | grep DAG4J6gixVGKYmcZs9Wmkyrv8ERp39vxtjwbjV5Q


export ADDRESS=DAG1BcstMG5z71VbsjDGqR2u6P7fi5ee41b6ve2t

curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e ".[1].activeDelegatedStakes[\"$ADDRESS\"]"

curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e ".[1].activeTokenLocks[\"$ADDRESS\"]"

curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e ".[1].delegatedStakesWithdrawals[\"$ADDRESS\"]"


curl -s "$DAG_L1_URL/token-locks/20395c5ff83b707e40a7b22590cbc09e7d164fcb8586a5221ba02bfb8f746f1a"
curl -s "$DAG_L1_URL/token-locks/last-reference/$ADDRESS"

