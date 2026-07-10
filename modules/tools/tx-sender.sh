#!/usr/bin/env bash
set -euo pipefail
#
# tx-sender launcher.
#
# Standardizes two things that bit us during load testing:
#   1. ENTROPY: ECDSA signing draws a per-signature nonce from SecureRandom. On low-entropy hosts
#      (WSL2, containers, fresh VMs) the JVM's blocking strong source (/dev/random) stalls signing
#      for seconds, throttling the sender to ~1 tx/5s. We default to the non-blocking urandom source
#      so signing never blocks. Set TX_SENDER_STRONG_ENTROPY=1 to opt back into the JVM default
#      (blocking /dev/random) -- only needed if you specifically require kernel-grade entropy.
#   2. JAR RESOLUTION: the assembly version string (e.g. ...+dirty / ...+1.<hash>.local) changes per
#      build, so a hardcoded jar name silently runs a STALE jar. We always resolve the NEWEST jar.
#
# Usage:
#   sbt tools/assembly                                   # build first
#   modules/tools/tx-sender.sh --config <conf>           # fast entropy (default)
#   TX_SENDER_STRONG_ENTROPY=1 modules/tools/tx-sender.sh --config <conf>   # blocking/strong entropy
#
# Any extra args (e.g. --config <path>) are passed straight through to the `tx-sender` subcommand.

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$(find "$HERE/target/scala-2.13" -name 'tessellation-tools-assembly-*.jar' -printf '%T@ %p\n' 2>/dev/null \
        | sort -rn | head -1 | cut -d' ' -f2-)"
[ -n "$JAR" ] || { echo "tx-sender: no assembly jar found under $HERE/target/scala-2.13 -- run: sbt tools/assembly" >&2; exit 1; }

OPTS=()
MODE="strong/blocking (JVM default)"
if [ -z "${TX_SENDER_STRONG_ENTROPY:-}" ]; then
  # Override only the SecureRandom source/strong-algorithm; single '=' keeps the default java.security
  # (a '==' would replace the whole file and remove all providers).
  SEC="$(mktemp -t tx-sender-security.XXXXXX)"
  trap 'rm -f "$SEC"' EXIT
  printf 'securerandom.source=file:/dev/./urandom\nsecurerandom.strongAlgorithms=SHA1PRNG:SUN\n' > "$SEC"
  OPTS+=("-Djava.security.egd=file:/dev/./urandom" "-Djava.security.properties=$SEC")
  MODE="fast/urandom (non-blocking)"
fi

echo "tx-sender: jar=$(basename "$JAR")  entropy=$MODE" >&2
exec java "${OPTS[@]}" -jar "$JAR" tx-sender "$@"
