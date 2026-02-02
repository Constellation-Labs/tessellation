#!/usr/bin/env bash
#
# Downloads tessellation JARs from a GitHub release
# Usage: ./download-release-jars.sh <release-tag> <output-dir>
# Example: ./download-release-jars.sh v3.5.11 ./docker/jars
#

set -e

RELEASE_TAG="${1:-}"
OUTPUT_DIR="${2:-./docker/jars}"

if [ -z "$RELEASE_TAG" ]; then
    echo "Error: Release tag required"
    echo "Usage: $0 <release-tag> [output-dir]"
    echo "Example: $0 v3.5.11 ./docker/jars"
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
    dest="${OUTPUT_DIR}/${local_name}"
    
    echo "  Downloading ${release_name} -> ${local_name}"
    
    if ! curl -fsSL -o "$dest" "$url"; then
        echo "Error: Failed to download ${url}"
        echo "Check that release ${RELEASE_TAG} exists and contains ${release_name}"
        exit 1
    fi
    
    # Verify download (basic size check)
    size=$(stat -f%z "$dest" 2>/dev/null || stat -c%s "$dest" 2>/dev/null)
    if [ "$size" -lt 1000 ]; then
        echo "Error: Downloaded file ${local_name} is too small (${size} bytes)"
        echo "The release may not contain this JAR or download failed"
        rm -f "$dest"
        exit 1
    fi
    
    echo "    ✓ ${local_name} (${size} bytes)"
done

echo "Successfully downloaded all JARs to ${OUTPUT_DIR}"
