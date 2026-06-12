#!/usr/bin/env bash
# Configure ~/.ssh on the GitHub Actions runner so subsequent steps can
# ssh into the nightly cluster as n0, n1, ... using short host aliases.
#
# Env:
#   NIGHTLY_HOSTS   — comma-separated list of host IPs (n0 first)
#   NIGHTLY_SSH_KEY — private key contents

set -euo pipefail

mkdir -p ~/.ssh
printf '%s\n' "$NIGHTLY_SSH_KEY" > ~/.ssh/nightly_key
chmod 600 ~/.ssh/nightly_key
touch ~/.ssh/config

IFS=',' read -ra IPS <<< "$NIGHTLY_HOSTS"
for i in "${!IPS[@]}"; do
  cat >> ~/.ssh/config <<EOF
Host n$i
    User root
    Hostname ${IPS[$i]}
    IdentityFile ~/.ssh/nightly_key
    StrictHostKeyChecking no
    UserKnownHostsFile /dev/null
EOF
done
chmod 600 ~/.ssh/config
