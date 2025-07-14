#!/usr/bin/env bash
set -e

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/../.." && pwd )"

echo "Generating test user keys..."

# Create output directory
OUTPUT_DIR="$PROJECT_ROOT/docker/config/user-test-keys"
mkdir -p "$OUTPUT_DIR"

# Create temporary working directory
TEMP_DIR=$(mktemp -d)
cd "$TEMP_DIR"

# Create environment file for key generation
cat << EOF > .envrc
export CL_KEYSTORE="key.p12"
export CL_KEYALIAS="alias"
export CL_PASSWORD="password"
export CL_APP_ENV="dev"
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

# Number of test keys to generate
NUM_KEYS=${1:-10}

echo "Generating $NUM_KEYS test keys..."

# Copy necessary jars from docker/jars directory
if [ -f "$PROJECT_ROOT/docker/jars/keytool.jar" ]; then
    cp "$PROJECT_ROOT/docker/jars/keytool.jar" .
    cp "$PROJECT_ROOT/docker/jars/wallet.jar" .
else
    echo "Error: keytool.jar and wallet.jar not found in docker/jars directory"
    exit 1
fi

# Generate keys
for i in $(seq 0 $((NUM_KEYS - 1))); do
    echo "Generating key $i..."
    
    # Generate the key
    source .envrc
    java -jar keytool.jar generate > /dev/null 2>&1
    
    # Get address
    address=$(java -jar wallet.jar show-address)
    
    # Export private key
    java -jar keytool.jar export > /dev/null 2>&1
    
    # Copy only hex files and addresses to output directory
    cp id_ecdsa.hex "$OUTPUT_DIR/private_key_${i}.hex"
    echo "$address" > "$OUTPUT_DIR/address_${i}.txt"
    
    # Clean up for next iteration
    rm -f key.p12 id_ecdsa.hex
done

# Append addresses to genesis.csv
GENESIS_CSV="$PROJECT_ROOT/.github/config/genesis.csv"
echo ""
echo "Appending addresses to genesis.csv..."

for i in $(seq 0 $((NUM_KEYS - 1))); do
    address=$(cat "$OUTPUT_DIR/address_${i}.txt")
    # Add each address with 10000000000000 balance (same as other test addresses)
    echo "${address},10000000000000" >> "$GENESIS_CSV"
    echo "Added ${address} to genesis.csv"
done

# Cleanup
cd "$PROJECT_ROOT"
rm -rf "$TEMP_DIR"

echo ""
echo "Successfully generated $NUM_KEYS test keys in $OUTPUT_DIR"
echo "Updated genesis.csv with new addresses"
echo ""
echo "Generated files:"
ls -la "$OUTPUT_DIR"