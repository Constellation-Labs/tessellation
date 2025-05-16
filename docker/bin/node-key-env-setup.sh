

cat << EOF > ./nodes/.envrc
export CL_KEYSTORE="key.p12"
export CL_KEYALIAS="alias"
export CL_PASSWORD="password"
export CL_APP_ENV="dev"
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
    echo "$id" > peer_id
    mkdir -p ../../docker/config/local-test-keys/$i
    cp key.p12 ../../docker/config/local-test-keys/$i
    cp address ../../docker/config/local-test-keys/$i
    cp peer_id ../../docker/config/local-test-keys/$i
    cd ../../
  done

}

populate_test_keys() {
  for i in 0 1 2; do
    cp ./docker/config/local-test-keys/$i/key.p12 ./nodes/$i/key.p12
    cp ./docker/config/local-test-keys/$i/address ./nodes/$i/address
    cp ./docker/config/local-test-keys/$i/peer_id ./nodes/$i/peer_id
  done
}


if [ "$REGENERATE_TEST_KEYS" = true ]; then
  generate_keys
fi

populate_test_keys
