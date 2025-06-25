

cat << EOF > ./nodes/.envrc
export CL_KEYSTORE="key.p12"
export CL_KEYALIAS="alias"
export CL_PASSWORD="password"
export CL_APP_ENV="dev"
# These ones below are dummy values used to trick the jar into starting
export CL_COLLATERAL=0
export CL_GLOBAL_L0_PEER_ID=1b4b9f98190ede0d26ec1a2ce736638ffa556b08135403256d47d3c405e08e3bb272fb9172c7bd9f96008dd380ceb6201e965498bc9115bbdf905ad9781dbf18
export CL_GLOBAL_L0_PEER_HTTP_HOST=172.32.0.10
export CL_GLOBAL_L0_PEER_HTTP_PORT=9000
export CL_L0_HTTP_PORT=9000
export CL_L0_PEER_ID=1b4b9f98190ede0d26ec1a2ce736638ffa556b08135403256d47d3c405e08e3bb272fb9172c7bd9f96008dd380ceb6201e965498bc9115bbdf905ad9781dbf18
export CL_L0_PEER_HTTP_HOST=172.32.0.10
export CL_PUBLIC_HTTP_PORT=9000
export CL_P2P_HTTP_PORT=9000
export CL_CLI_HTTP_PORT=9000
EOF


for i in 0 1 2; do
  cp ./nodes/.envrc ./nodes/$i/.envrc
done

generate_keys() {

  for i in 0 1 2; do
    mkdir -p ./nodes/$i
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
    export=$(
      source .envrc
      java -jar ../keytool.jar export
    )

    echo "$id" > peer_id
    mkdir -p ../../docker/config/local-test-keys/$i
    cp key.p12 ../../docker/config/local-test-keys/$i
    cp address ../../docker/config/local-test-keys/$i
    cp peer_id ../../docker/config/local-test-keys/$i
    cp id_ecdsa.hex ../../docker/config/local-test-keys/$i
    cd ../../
  done

}

populate_test_keys() {
  for i in 0 1 2; do
    cp ./docker/config/local-test-keys/$i/key.p12 ./nodes/$i/key.p12
    cp ./docker/config/local-test-keys/$i/address ./nodes/$i/address
    cp ./docker/config/local-test-keys/$i/peer_id ./nodes/$i/peer_id
    cp ./docker/config/local-test-keys/$i/id_ecdsa.hex ./nodes/$i/id_ecdsa.hex
  done
  GENESIS_DIR=$PROJECT_ROOT/.github/code/hypergraph/dag-l0/genesis-node
  mkdir -p $GENESIS_DIR
  VALIDATOR_1_DIR=$PROJECT_ROOT/.github/code/hypergraph/dag-l0/validator-1
  VALIDATOR_2_DIR=$PROJECT_ROOT/.github/code/hypergraph/dag-l0/validator-2
  mkdir -p $VALIDATOR_1_DIR
  mkdir -p $VALIDATOR_2_DIR
  cp ./nodes/0/id_ecdsa.hex $GENESIS_DIR/id_ecdsa.hex
  cp ./nodes/1/id_ecdsa.hex $VALIDATOR_1_DIR/id_ecdsa.hex
  cp ./nodes/2/id_ecdsa.hex $VALIDATOR_2_DIR/id_ecdsa.hex

  # Alternative for overriding github checked in keys if needed
  # DELEGATED_STAKING_KEYS_DIR=$PROJECT_ROOT/.github/action_scripts/delegated_staking/keys
  # mkdir -p $DELEGATED_STAKING_KEYS_DIR
  # cp ./nodes/0/id_ecdsa.hex $DELEGATED_STAKING_KEYS_DIR/genesis-node.hex
  # cp ./nodes/1/id_ecdsa.hex $DELEGATED_STAKING_KEYS_DIR/validator-1-node.hex
  # cp ./nodes/2/id_ecdsa.hex $DELEGATED_STAKING_KEYS_DIR/validator-2-node.hex

}


if [ "$REGENERATE_TEST_KEYS" = true ]; then
  generate_keys
fi

populate_test_keys
