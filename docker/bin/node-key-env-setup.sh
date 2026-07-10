

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


for i in $(seq 0 $((MAX_NODES - 1))); do
  cp ./nodes/.envrc ./nodes/$i/.envrc
done

generate_keys() {

  for i in $(seq 0 $((MAX_NODES - 1))); do
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

generate_missing_keys() {
  # Generate keys for any node index that doesn't already have pre-generated keys
  for i in $(seq 0 $((MAX_NODES - 1))); do
    if [ ! -f "./docker/config/local-test-keys/$i/key.p12" ]; then
      echo "Generating keys for node $i (not found in local-test-keys)"
      mkdir -p ./nodes/$i
      cd ./nodes/$i/
      cp ../0/.envrc .envrc 2>/dev/null || cp ../../nodes/.envrc .envrc

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
    fi
  done
}

populate_test_keys() {
  for i in $(seq 0 $((MAX_NODES - 1))); do
    cp ./docker/config/local-test-keys/$i/key.p12 ./nodes/$i/key.p12
    cp ./docker/config/local-test-keys/$i/address ./nodes/$i/address
    cp ./docker/config/local-test-keys/$i/peer_id ./nodes/$i/peer_id
    cp ./docker/config/local-test-keys/$i/id_ecdsa.hex ./nodes/$i/id_ecdsa.hex
  done
  GENESIS_DIR=$PROJECT_ROOT/.github/code/hypergraph/dag-l0/genesis-node
  mkdir -p $GENESIS_DIR
  cp ./nodes/0/id_ecdsa.hex $GENESIS_DIR/id_ecdsa.hex

  # Copy validator keys for all non-genesis nodes
  for i in $(seq 1 $((MAX_NODES - 1))); do
    VALIDATOR_DIR=$PROJECT_ROOT/.github/code/hypergraph/dag-l0/validator-$i
    mkdir -p $VALIDATOR_DIR
    cp ./nodes/$i/id_ecdsa.hex $VALIDATOR_DIR/id_ecdsa.hex
  done
}


if [ "$REGENERATE_TEST_KEYS" = true ]; then
  generate_keys
fi

# Generate keys for any nodes beyond the pre-generated set (0-2)
generate_missing_keys

populate_test_keys
