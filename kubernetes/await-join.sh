#! /usr/bin/env sh

HOST=$1
PORT_PUBLIC=$2
PORT_P2P=$3
ID=$4

CURL_RETRY_CMD="curl --retry 60 --retry-delay 1 --retry-all-errors --fail --silent"

eval "$CURL_RETRY_CMD $HOST:$PORT_PUBLIC/node/health"

cat <<EOF > /tmp/peer-to-join.json
{
  "id": "$ID",
  "ip": "$HOST",
  "p2pPort": "$PORT_P2P"
}
EOF

eval "$CURL_RETRY_CMD 127.0.0.1:9002/cluster/join -H \"Content-Type: application/json\" -d @/tmp/peer-to-join.json" && \
  echo "Join requested"
