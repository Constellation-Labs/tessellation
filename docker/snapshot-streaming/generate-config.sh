#!/usr/bin/env bash

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="${PROJECT_ROOT:-$SCRIPT_DIR/../..}"
NET_PREFIX="${NET_PREFIX:-172.32.0}"

CONFIG_DEST="$SCRIPT_DIR/application.conf"

# Read peer ID from node 0
PEER_ID_FILE="$PROJECT_ROOT/nodes/0/peer_id"
if [ ! -f "$PEER_ID_FILE" ]; then
  echo "ERROR: peer_id file not found at $PEER_ID_FILE"
  exit 1
fi
PEER_ID=$(cat "$PEER_ID_FILE" | tr -d '[:space:]')

cat > "$CONFIG_DEST" <<'STATICEOF'
# Include tessellation's bundled application.conf from the classpath
# This provides all node-shared config keys (gossip, delegated-staking, fields-added-ordinals, etc.)
include classpath("application")
STATICEOF

cat >> "$CONFIG_DEST" <<EOF
snapshotStreaming {
  environment = dev
  lastIncrementalSnapshotPath = "/app/data/seed-snapshot.json.gz"

  node {
    l0Peers = [
      {
        id = "$PEER_ID"
        ip = "${NET_PREFIX}.10"
        port = 9000
      }
    ]
    pullInterval = 5s
    pullLimit = 50
  }

  db {
    host = "${NET_PREFIX}.60"
    port = 5432
    user = "snapshot_streaming"
    password = "snapshot_streaming"
    database = "snapshot_streaming"
    maxSessions = 16
  }

  opensearch {
    uri = "http://localhost:9200"
    bulkSize = 500
    indexes {
      snapshots = "snapshots"
      blocks = "blocks"
      transactions = "transactions"
      balances = "balances"
      currency {
        snapshots = "currency-snapshots"
        blocks = "currency-blocks"
        transactions = "currency-transactions"
        balances = "currency-balances"
        feeTransactions = "currency-fee-transactions"
      }
    }
  }

  s3 {
    bucketRegion = "us-west-2"
    bucketName = "disabled"
    bucketDir = "disabled"
    api {}
    uploadEnabled = false
  }
}
EOF

echo "Generated snapshot-streaming config: $CONFIG_DEST"
