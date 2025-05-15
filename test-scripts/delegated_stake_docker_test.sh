

echo "Waiting for docker containers to come online"

sleep 45

### CLUSTER SPECIFIC TESTING BELOW
echo "Starting cluster test"

export DAG_L0_URL="http://localhost:9000"
export DAG_L1_URL="http://localhost:9010"

# Create node update params for gl0 kp
cd ./nodes/0/
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
cd ../../

curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e '.[1].updateNodeParameters | length > 0' > /dev/null || \
{ echo "ERROR: updateNodeParameters is empty in snapshot combined"; exit_func; }



# # Create token lock for gl0 kp
cd ./nodes/0/

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
cd ../../

curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e '.[1].activeTokenLocks | length == 1' > /dev/null || \
{ echo "ERROR: activeTokenLocks is empty in snapshot combined"; exit_func; }


# Create delegated stake for gl0 kp

cd ./nodes/0/
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
cd ../../
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
cd ./nodes/1/
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
cd ../../

curl -s "$DAG_L0_URL"/global-snapshots/latest/combined | \
jq -e '.[1].updateNodeParameters | length > 1' > /dev/null || \
{ echo "ERROR: updateNodeParameters is empty in snapshot combined"; exit_func; }


# Now create a delegated stake with the second address
# Create delegated stake for gl0 kp

second=$(cat ./nodes/1/peer_id)
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
cd ../../
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
cd ./nodes/0/
out=$(
  source .envrc
  java -jar ../../wallet.jar withdraw-delegated-stake --stake-ref "$DELEGATED_STAKE_HASH"
)
echo "Withdraw delegated stake output $out"
cat event
cp event withdraw-delegated-stake.json
curl -i -X PUT --header 'Content-Type: application/json' --data @withdraw-delegated-stake.json "$DAG_L0_URL"/delegated-stakes
# Await accepted, may require adjustment
cd ../../

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
