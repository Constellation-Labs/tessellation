#!/usr/bin/env bash
# Gather docker ps + service logs from each nightly host into runner-logs/
# for upload as a workflow artifact. Best-effort: never fails the workflow.
#
# Env:
#   NIGHTLY_HOSTS — comma-separated host IPs (n0 first)

set -uo pipefail

mkdir -p runner-logs

IFS=',' read -ra IPS <<< "$NIGHTLY_HOSTS"
LAST=$((${#IPS[@]} - 1))

for i in "${!IPS[@]}"; do
  echo "=== n$i (${IPS[$i]}) ===" >> runner-logs/remote-status.txt
  ssh "n$i" "docker ps -a 2>/dev/null" >> runner-logs/remote-status.txt 2>&1 || true
  for svc in gl0 gl1; do
    ssh "n$i" "docker logs $svc 2>&1 | tail -50" > "runner-logs/n${i}-${svc}.log" 2>&1 || true
  done
done

for svc in snapshot-streaming snapshot-streaming-postgres block-explorer; do
  ssh "n$LAST" "docker logs $svc 2>&1 | tail -100" > "runner-logs/n${LAST}-${svc}.log" 2>&1 || true
done
