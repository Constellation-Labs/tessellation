#!/usr/bin/env bash
#
# Downloads tessellation JARs from a GitHub release with integrity verification
# Usage: ./download-release-jars.sh <release-tag> <output-dir>
# Example: ./download-release-jars.sh v3.5.11 ./docker/jars
#

set -eo pipefail

RELEASE_TAG="${1:-}"
OUTPUT_DIR="${2:-./docker/jars}"

if [ -z "$RELEASE_TAG" ]; then
    echo "Error: Release tag required"
    echo "Usage: $0 <release-tag> [output-dir]"
    echo "Example: $0 v3.5.11 ./docker/jars"
    exit 1
fi

# Validate release tag format
if ! [[ "$RELEASE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$ ]]; then
    echo "Error: Invalid release tag format: $RELEASE_TAG"
    echo "Expected format: v3.5.11 or v3.5.11-rc1"
    exit 1
fi

REPO="Constellation-Labs/tessellation"
BASE_URL="https://github.com/${REPO}/releases/download/${RELEASE_TAG}"

# JAR mappings: release-name -> local-name
declare -A JAR_MAP=(
    ["cl-node.jar"]="gl0.jar"
    ["cl-dag-l1.jar"]="gl1.jar"
    ["cl-keytool.jar"]="keytool.jar"
    ["cl-wallet.jar"]="wallet.jar"
)

mkdir -p "$OUTPUT_DIR"

echo "Downloading tessellation JARs from release ${RELEASE_TAG}..."

for release_name in "${!JAR_MAP[@]}"; do
    local_name="${JAR_MAP[$release_name]}"
    url="${BASE_URL}/${release_name}"
    checksum_url="${BASE_URL}/${release_name%.jar}.sha256"
    dest="${OUTPUT_DIR}/${local_name}"
    
    echo "  Downloading ${release_name} -> ${local_name}"
    
    # Download JAR
    if ! curl -fsSL -o "$dest" "$url"; then
        echo "Error: Failed to download ${url}"
        echo "Check that release ${RELEASE_TAG} exists and contains ${release_name}"
        echo ""
        echo "Expected assets for release ${RELEASE_TAG}:"
        for name in "${!JAR_MAP[@]}"; do
            echo "  - ${name}"
        done
        echo ""
        echo "To check available assets, run:"
        echo "  gh release view ${RELEASE_TAG} --repo ${REPO} --json assets --jq '.assets[].name'"
        exit 1
    fi
    
    # Download and verify checksum
    echo "    Verifying checksum..."
    if ! expected_checksum=$(curl -fsSL "$checksum_url" | awk '{print $1}'); then
        echo "Error: Failed to download checksum from ${checksum_url}"
        rm -f "$dest"
        exit 1
    fi
    
    actual_checksum=$( (shasum -a 256 "$dest" 2>/dev/null || sha256sum "$dest") | awk '{print $1}')
    
    if [ "$expected_checksum" != "$actual_checksum" ]; then
        echo "Error: Checksum verification failed for ${local_name}!"
        echo "  Expected: ${expected_checksum}"
        echo "  Actual:   ${actual_checksum}"
        echo "The file may have been tampered with or corrupted during download."
        rm -f "$dest"
        exit 1
    fi
    
    size=$(stat -f%z "$dest" 2>/dev/null || stat -c%s "$dest" 2>/dev/null)
    echo "    ✓ ${local_name} (${size} bytes, checksum verified)"
done

echo "Successfully downloaded and verified all JARs to ${OUTPUT_DIR}"
