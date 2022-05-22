#! /usr/bin/env sh

HOST=$1
PORT_PUBLIC=$2

CURL_RETRY_CMD="curl --retry 60 --retry-delay 1 --retry-all-errors --fail --silent"

eval "$CURL_RETRY_CMD $HOST:$PORT_PUBLIC/node/health"
