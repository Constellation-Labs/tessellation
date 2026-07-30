#!/usr/bin/env bash
#
# deploy-cluster.sh — one-command deploy of a tessellation cluster for an environment.
#
#   just deploy testnet            # (default env = testnet)
#   just deploy nightly
#   deploy/deploy-cluster.sh testnet
#
# Deploys the SOFTWARE STACK onto an already-terraform-provisioned cluster:
#   1. builds the node software from a git ref (e.g. release/testnet) + publishes its SDK locally
#   2. deploys monitoring (Prometheus + Grafana + ClickHouse)
#   3. deploys nodes + snapshot-streaming via the existing `compose-runner.sh --up` pipeline
#   4. verifies
#
# It does NOT run terraform (provisioning is a separate lifecycle); it only READS the
# cluster's IPs from `terraform output`. Mirrors .github/workflows/nightly-deploy.yml.
#
# Per-environment branch config lives in the `case "$ENV"` block below — add an env = add an arm.
# Everything is overridable via env vars (CI-friendly):
#   TESSELLATION_REF SNAPSHOT_STREAMING_BRANCH BLOCK_EXPLORER_BRANCH
#   GRAFANA_ADMIN_PASSWORD CLICKHOUSE_PASSWORD CLICKHOUSE_HOST
#   TESSELLATION_VERSION BUILD_DIR SKIP_BUILD SSH_USER
#   SS_RESET_DB=true  wipe snapshot-streaming's postgres + resume state (default:
#                     preserve existing SS data and resume/backfill)
#   SS_DB_URL         snapshot-streaming's database. Default = the local postgres
#                     container on the streaming node. Set to an external postgres
#                     (postgresql://user:pass@host:port/db, plain form — no query
#                     params) and the deploy skips the local postgres container;
#                     SS, prisma, and the seed/preserve logic all use the URL.
#   DEPLOY_APP_ENV    CL_APP_ENV profile for the nodes (per-env default in the case
#                     block: testnet=>testnet, nightly=>dev). Non-dev values generate
#                     tn1-3-parity config: gl0 seedlist (cluster's own ids), per-IP
#                     snapshot allowlist, MPT debug dump, and NO CL_TEST_MODE/
#                     CL_LOCAL_MODE. NOTE: a FRESH GENESIS cannot run under
#                     CL_APP_ENV=testnet (real-chain landmarks like last-full-global-
#                     snapshot-ordinal=736766 are baked into the env; validated
#                     2026-07-01) — non-dev profiles are for data-preserving deploys.
#
# Real-data migration knobs (tn1-3 -> Hetzner cutover; see
# ~/src/internal-plans/ai/reviewed/plans/testnet-hetzner-migration/cutover.md):
#   NODE_KEYS_DIR        per-node key material <dir>/<i>/{key.p12,peer_id[,address]}
#                        (default: committed test keys; migration: the real tn keys)
#   NODE_KEY_ALIAS       CL_KEYALIAS override (tn keys use "keyalias", image default "alias")
#   NODE_KEY_PASSWORD    CL_PASSWORD override
#   SEEDLIST_FILE        ship this real seedlist instead of generating from cluster ids
#   SNAPSHOT_STORED_PATH set CL_SNAPSHOT_STORED_PATH (tn layout: data/incremental_snapshot);
#                        only for data rsynced with that layout — breaks fresh genesis
#   The deploy bind-mounts this branch's docker/entrypoint.sh over the image's baked
#   copy (see remote-deploy.sh), so the rollback-first guard + JVM-override logic apply
#   regardless of which branch built the image — no release-branch entrypoint PR needed.
#
set -euo pipefail

ENV="${1:-testnet}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SSH_USER="${SSH_USER:-admin}"
BUILD_DIR="${BUILD_DIR:-$ROOT/.cluster-build}"

log() { printf '\033[36m[deploy:%s]\033[0m %s\n' "$ENV" "$*"; }
die() { printf '\033[31m[deploy:%s] ERROR:\033[0m %s\n' "$ENV" "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 0. Per-environment branch config (override any via env var)
# ---------------------------------------------------------------------------
case "$ENV" in
  testnet)
    TESSELLATION_REF="${TESSELLATION_REF:-release/testnet}"
    SNAPSHOT_STREAMING_BRANCH="${SNAPSHOT_STREAMING_BRANCH:-release/testnet}"
    BLOCK_EXPLORER_BRANCH="${BLOCK_EXPLORER_BRANCH:-increase_delegated_stakes}"
    DEPLOY_APP_ENV="${DEPLOY_APP_ENV:-testnet}"
    ;;
  nightly)
    # nightly tracks release/testnet (same branch/channel/image as testnet) but
    # keeps the dev app-env profile (test/local mode, no seedlist) for the tests
    TESSELLATION_REF="${TESSELLATION_REF:-release/testnet}"
    SNAPSHOT_STREAMING_BRANCH="${SNAPSHOT_STREAMING_BRANCH:-release/testnet}"
    BLOCK_EXPLORER_BRANCH="${BLOCK_EXPLORER_BRANCH:-increase_delegated_stakes}"
    DEPLOY_APP_ENV="${DEPLOY_APP_ENV:-dev}"
    ;;
  integrationnet)
    TESSELLATION_REF="${TESSELLATION_REF:-release/integrationnet}"
    SNAPSHOT_STREAMING_BRANCH="${SNAPSHOT_STREAMING_BRANCH:-release/integrationnet}"
    BLOCK_EXPLORER_BRANCH="${BLOCK_EXPLORER_BRANCH:-increase_delegated_stakes}"
    DEPLOY_APP_ENV="${DEPLOY_APP_ENV:-integrationnet}"
    ;;
  mainnet)
    TESSELLATION_REF="${TESSELLATION_REF:-release/mainnet}"
    SNAPSHOT_STREAMING_BRANCH="${SNAPSHOT_STREAMING_BRANCH:-release/mainnet}"
    BLOCK_EXPLORER_BRANCH="${BLOCK_EXPLORER_BRANCH:-increase_delegated_stakes}"
    DEPLOY_APP_ENV="${DEPLOY_APP_ENV:-mainnet}"
    ;;
  *)
    die "unknown environment '$ENV' (add a case arm in deploy-cluster.sh). Known: nightly, testnet, integrationnet, mainnet"
    ;;
esac
export SNAPSHOT_STREAMING_BRANCH BLOCK_EXPLORER_BRANCH DEPLOY_APP_ENV

# Image source: build locally + ship a tarball (default), or pull a prebuilt image from
# a registry (GHCR). Channel tag = branch with "release/" stripped, matching the CI build.
CHANNEL="${TESSELLATION_REF#release/}"
IMAGE_SOURCE="${IMAGE_SOURCE:-build}"
if [ "$IMAGE_SOURCE" = "registry" ]; then
  CL_DOCKER_CORE_IMAGE="${CL_DOCKER_CORE_IMAGE:-ghcr.io/constellation-labs/tessellation}"
  CL_DOCKER_SS_IMAGE="${CL_DOCKER_SS_IMAGE:-ghcr.io/constellation-labs/snapshot-streaming}"
  # default = moving channel tag; pin an immutable build via TESSELLATION_IMAGE_TAG=<version>|sha-<short>
  TESSELLATION_DOCKER_VERSION="${TESSELLATION_IMAGE_TAG:-$CHANNEL}"
  export IMAGE_SOURCE CL_DOCKER_CORE_IMAGE CL_DOCKER_SS_IMAGE TESSELLATION_DOCKER_VERSION
else
  export IMAGE_SOURCE
fi

log "tessellation ref=$TESSELLATION_REF  snapshot-streaming=$SNAPSHOT_STREAMING_BRANCH  block-explorer=$BLOCK_EXPLORER_BRANCH"

# ---------------------------------------------------------------------------
# 1. Resolve cluster hosts from terraform output (unless HOSTS overrides)
#    HOSTS, if set, must be: "node0,node1,node2,streaming,monitoring"
# ---------------------------------------------------------------------------
if [ -n "${HOSTS:-}" ]; then
  IFS=',' read -r -a H <<< "$HOSTS"
  NODE_IPS=("${H[0]:-}" "${H[1]:-}" "${H[2]:-}"); STREAM_IP="${H[3]:-}"; MON_IP="${H[4]:-}"
else
  command -v terraform >/dev/null || die "terraform not found (and HOSTS not set)"
  pushd "$ROOT/deploy/terraform" >/dev/null
  mapfile -t NODE_IPS < <(terraform output -json node_public_ips 2>/dev/null \
      | python3 -c "import json,sys;[print(x) for x in json.load(sys.stdin)]")
  STREAM_IP="$(terraform output -raw streaming_public_ip 2>/dev/null || true)"
  MON_IP="$(terraform output -raw monitoring_public_ip 2>/dev/null || true)"
  popd >/dev/null
fi
[ "${#NODE_IPS[@]}" -ge 3 ] && [ -n "$STREAM_IP" ] && [ -n "$MON_IP" ] \
  || die "could not resolve hosts (nodes=${NODE_IPS[*]:-} streaming=${STREAM_IP:-} monitoring=${MON_IP:-}). Provision first or set HOSTS=..."

NODES_REMOTE="$SSH_USER@${NODE_IPS[0]},$SSH_USER@${NODE_IPS[1]},$SSH_USER@${NODE_IPS[2]},$SSH_USER@$STREAM_IP"
MON_REMOTE="$SSH_USER@${NODE_IPS[0]},$SSH_USER@${NODE_IPS[1]},$SSH_USER@${NODE_IPS[2]},$SSH_USER@$MON_IP"
# Monitoring host scrapes the nodes' public ports — exempt it from the per-IP
# snapshot rate limiter alongside the cluster (only used when DEPLOY_APP_ENV != dev)
export SNAPSHOT_ALLOWLIST_EXTRA="${SNAPSHOT_ALLOWLIST_EXTRA:-$MON_IP}"
log "nodes=${NODE_IPS[*]}  streaming=$STREAM_IP  monitoring=$MON_IP  app-env=$DEPLOY_APP_ENV"

# ---------------------------------------------------------------------------
# 2. Pre-accept SSH host keys (the deploy scripts ssh/scp non-interactively)
# ---------------------------------------------------------------------------
log "accepting SSH host keys"
for h in "${NODE_IPS[@]}" "$STREAM_IP" "$MON_IP"; do
  ssh-keygen -R "$h" >/dev/null 2>&1 || true
  ssh-keyscan -t ed25519 -T 8 "$h" >> "$HOME/.ssh/known_hosts" 2>/dev/null \
    || log "  warn: host key scan failed for $h (booted yet?)"
done

# ---------------------------------------------------------------------------
# 3. Build node software from TESSELLATION_REF (clone -> assembly -> publishLocal SDK)
#    Skipped with SKIP_BUILD=true (reuse already-staged docker/jars + published SDK).
# ---------------------------------------------------------------------------
if [ "${SKIP_BUILD:-false}" = "true" ]; then
  VER="${TESSELLATION_VERSION:?SKIP_BUILD=true requires TESSELLATION_VERSION to be set}"
  log "SKIP_BUILD=true — reusing staged jars + SDK version $VER"
elif [ "$IMAGE_SOURCE" = "registry" ]; then
  # Registry mode: node + snapshot-streaming images are pulled from GHCR, so there is
  # nothing to build locally (no node assembly, no SDK publishLocal, no SS jar).
  VER="${TESSELLATION_VERSION:-$TESSELLATION_DOCKER_VERSION}"
  log "IMAGE_SOURCE=registry — pulling prebuilt images (tag $TESSELLATION_DOCKER_VERSION); skipping local build"
else
  REPO_URL="$(git -C "$ROOT" remote get-url origin)"
  if [ ! -d "$BUILD_DIR/.git" ]; then
    log "cloning $REPO_URL -> $BUILD_DIR (first run; cached afterwards)"
    git clone --quiet "$REPO_URL" "$BUILD_DIR"
  fi
  log "fetching + checking out $TESSELLATION_REF in build clone"
  git -C "$BUILD_DIR" fetch --quiet --tags origin "$TESSELLATION_REF"
  git -C "$BUILD_DIR" checkout --quiet --force FETCH_HEAD
  VER="${TESSELLATION_VERSION:-$(git -C "$BUILD_DIR" describe --tags --always | sed 's/^v//')}"
  log "building node jars + SDK at version $VER (commit $(git -C "$BUILD_DIR" rev-parse --short HEAD))"

  ( cd "$BUILD_DIR"
    # Node fat-jars are only needed to build the image locally; in registry mode the
    # node image is pulled from GHCR, so skip the (slow) node assembly.
    if [ "$IMAGE_SOURCE" != "registry" ]; then
      sbt -batch "set ThisBuild / version := \"$VER\"" \
        dagL0/assembly dagL1/assembly keytool/assembly wallet/assembly tools/assembly
    fi
    # -no-link-warnings: release/testnet has a fatal Scaladoc [[member]] link that would
    # otherwise abort the SDK publish (matches the fix in docker/bin/assembly.sh).
    # publishLocal always runs — snapshot-streaming resolves this exact SDK.
    sbt -batch "set ThisBuild / version := \"$VER\"" \
      "set sdk / Compile / doc / scalacOptions += \"-no-link-warnings\"" \
      sdk/publishLocal )

  if [ "$IMAGE_SOURCE" != "registry" ]; then
  log "staging node jars into docker/jars/"
  rm -f "$ROOT/docker/jars/"*.jar
  mkdir -p "$ROOT/docker/jars"
  for m in dag-l0 dag-l1 keytool wallet tools; do
    src="$(ls -1t "$BUILD_DIR/modules/$m/target/scala-2.13/"*assembly*.jar 2>/dev/null | head -1 || true)"
    [ -n "$src" ] || die "build produced no assembly jar for module $m"
    case "$m" in dag-l0) dst=gl0 ;; dag-l1) dst=gl1 ;; *) dst="$m" ;; esac
    cp -f "$src" "$ROOT/docker/jars/$dst.jar"
  done
  # Empty placeholders for the metagraph jars the Dockerfile COPYs (ml0/cl1/dl1) but a
  # hypergraph node never runs. assembly.sh touches these on its skip-path; since --skip-assembly
  # bypasses assembly.sh entirely (we staged jars directly), create them here so the image builds.
  touch "$ROOT/docker/jars/ml0.jar" "$ROOT/docker/jars/cl1.jar" "$ROOT/docker/jars/dl1.jar"
  log "staged: $(cd "$ROOT/docker/jars" && ls *.jar | tr '\n' ' ')"
  fi
fi
export TESSELLATION_VERSION="$VER"   # so the snapshot-streaming build resolves this exact SDK

# ---------------------------------------------------------------------------
# 4. Deploy monitoring first (so ClickHouse is up before nodes can ship logs)
# ---------------------------------------------------------------------------
log "deploying monitoring -> $MON_IP"
REMOTE_NODES="$MON_REMOTE" \
GRAFANA_ADMIN_PASSWORD="${GRAFANA_ADMIN_PASSWORD:-admin}" \
CLICKHOUSE_PASSWORD="${CLICKHOUSE_PASSWORD:-clickhouse}" \
  bash "$ROOT/docker/monitoring/deploy-monitoring.sh"

# ---------------------------------------------------------------------------
# 5. Deploy nodes + snapshot-streaming via the existing pipeline
#    (--skip-assembly reuses the ref jars staged above; CLICKHOUSE_HOST makes nodes ship logs)
# ---------------------------------------------------------------------------
log "deploying nodes + snapshot-streaming (TESSELLATION_VERSION=$VER)"
cd "$ROOT"
CLICKHOUSE_HOST="${CLICKHOUSE_HOST:-$MON_IP}" \
CLICKHOUSE_PASSWORD="${CLICKHOUSE_PASSWORD:-clickhouse}" \
  bash docker/bin/compose-runner.sh --up --skip-assembly \
    --remote="$NODES_REMOTE" \
    --snapshot-streaming-branch="$SNAPSHOT_STREAMING_BRANCH" \
    --block-explorer-branch="$BLOCK_EXPLORER_BRANCH"

# ---------------------------------------------------------------------------
# 6. Verify
# ---------------------------------------------------------------------------
log "verifying"
ok=1
for ip in "${NODE_IPS[@]}"; do
  info="$(ssh -o BatchMode=yes -o ConnectTimeout=8 "$SSH_USER@$ip" \
    'curl -s -m5 http://localhost:9000/node/info; echo; curl -s -m5 http://localhost:9000/global-snapshots/latest/ordinal' 2>/dev/null || true)"
  state="$(printf '%s' "$info" | python3 -c "import sys,json;print(json.loads(sys.stdin.readline()).get('state'))" 2>/dev/null || echo '?')"
  ord="$(printf '%s' "$info" | tail -1)"
  printf '  node %-15s state=%s latest=%s\n' "$ip" "$state" "$ord"
  [ "$state" = "Ready" ] || ok=0
done
SS_VERIFY_DB_URL="${SS_DB_URL:-postgresql://snapshot_streaming:snapshot_streaming@127.0.0.1:5432/snapshot_streaming}"
# Split the URL into libpq PG* env vars so the password isn't passed as a positional
# psql arg (which would show in ps/docker-run argv on the streaming node) — matches
# the env-based form used elsewhere.
read -r _pg_h _pg_p _pg_u _pg_pw _pg_db < <(python3 - "$SS_VERIFY_DB_URL" <<'PYEOF'
import sys
from urllib.parse import urlsplit, unquote
u = urlsplit(sys.argv[1])
print(u.hostname or "127.0.0.1", u.port or 5432, unquote(u.username or ""), unquote(u.password or ""), u.path.lstrip("/"))
PYEOF
)
ss_count="$(ssh -o BatchMode=yes "$SSH_USER@$STREAM_IP" \
  "docker run --rm --network host -e PGHOST=$_pg_h -e PGPORT=$_pg_p -e PGUSER=$_pg_u -e PGPASSWORD=$_pg_pw -e PGDATABASE=$_pg_db postgres:15-alpine psql -t -A -c 'SELECT COUNT(*) FROM global_snapshots;'" 2>/dev/null || echo '?')"
prom_up="$(ssh -o BatchMode=yes "$SSH_USER@$MON_IP" \
  "curl -s -m5 'http://localhost:9090/api/v1/targets?state=any'" 2>/dev/null \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print(sum(1 for t in d['data']['activeTargets'] if t['health']=='up'))" 2>/dev/null || echo '?')"
printf '  snapshot-streaming global_snapshots=%s   prometheus targets up=%s\n' "$ss_count" "$prom_up"
log "Grafana: http://$MON_IP:3000"

[ "$ok" = "1" ] || die "one or more nodes not Ready — see above"
log "done: $ENV cluster deployed (tessellation $VER)"
