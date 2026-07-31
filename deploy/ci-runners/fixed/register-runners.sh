#!/usr/bin/env bash
#
# register-runners.sh — install and register the GitHub Actions runner on each
# server in the fixed pool.
#
#   deploy/ci-runners/fixed/register-runners.sh <ip1,ip2,ip3>
#   deploy/ci-runners/fixed/register-runners.sh "$(terraform -chdir=deploy/ci-runners/fixed/terraform output -raw runner_ips_csv)"
#
# Idempotent: re-run to roll out a hook change, upgrade the runner, or re-register
# after a token rotation. An already-registered runner is unregistered and
# re-registered cleanly rather than duplicated.
#
# Always required:
#   GITHUB_REPOSITORY  e.g. Constellation-Labs/tessellation  (not a secret)
#
# AUTHORIZATION — pick ONE. GitHub will not let a machine register without proof
# of authorization, but you do not need a long-lived credential for it:
#
#   (a) NO-PAT PATH (preferred when PAT policy / SSO is in the way)
#       REG_TOKENS   comma-separated per-runner registration tokens, in the same
#                    order as the IPs. Copy each from the repo UI:
#                      Settings -> Actions -> Runners -> New self-hosted runner
#                      -> Linux, then take the value after `--token` in the
#                      displayed ./config.sh command.
#                    One token per runner. They expire after ~1 hour, are
#                    single-use, and grant nothing but "join this repo".
#
#   (b) PAT PATH (convenient for repeat runs / automation)
#       GITHUB_TOKEN GitHub CLASSIC PAT with `repo` scope. Used ONLY here, on the
#                    operator's machine, to mint the same short-lived per-host
#                    registration tokens automatically. The PAT itself is never
#                    copied to the runners.
#
# Optional:
#   RUNNER_LABELS      default: tessellation-e2e  (must match the workflow's runs-on)
#   RUNNER_VERSION     default: 2.336.0
#   SSH_USER           default: admin
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SSH_USER="${SSH_USER:-admin}"
RUNNER_LABELS="${RUNNER_LABELS:-tessellation-e2e}"
RUNNER_VERSION="${RUNNER_VERSION:-2.336.0}"

die() { echo "ERROR: $*" >&2; exit 1; }
log() { echo "==> $*"; }

TARGETS="${1:-}"
[ -n "$TARGETS" ] || die "usage: $0 <ip1,ip2,...>"
[ -n "${GITHUB_REPOSITORY:-}" ] || die "GITHUB_REPOSITORY must be set in the environment"

command -v curl >/dev/null || die "curl is required"

IFS=',' read -ra HOSTS <<< "$TARGETS"

# Decide the authorization mode up front, so a misconfiguration fails before we
# touch any server rather than half-way through the pool.
REG_TOKEN_LIST=()
if [ -n "${REG_TOKENS:-}" ]; then
  AUTH_MODE="pre-minted"
  IFS=',' read -ra REG_TOKEN_LIST <<< "$REG_TOKENS"
  if [ "${#REG_TOKEN_LIST[@]}" -ne "${#HOSTS[@]}" ]; then
    die "REG_TOKENS has ${#REG_TOKEN_LIST[@]} token(s) but ${#HOSTS[@]} host(s) were given — supply one token per runner, in IP order"
  fi
  log "Authorization: pre-minted registration tokens (no PAT)"
elif [ -n "${GITHUB_TOKEN:-}" ]; then
  AUTH_MODE="pat"
  log "Authorization: classic PAT (registration tokens will be minted per host)"
else
  die "set either REG_TOKENS (one UI-copied registration token per runner, comma-separated) or GITHUB_TOKEN (classic PAT with 'repo' scope) — see the header of this script"
fi

# Resolve the runner tarball checksum once, from the release GitHub publishes, so
# we verify the download on every host without hardcoding a hash that silently
# rots when RUNNER_VERSION is bumped.
log "Resolving runner v${RUNNER_VERSION} checksum"
RUNNER_FILE="actions-runner-linux-x64-${RUNNER_VERSION}.tar.gz"
RUNNER_SHA="$(curl -sSL \
  "https://api.github.com/repos/actions/runner/releases/tags/v${RUNNER_VERSION}" \
  | grep -o '<!-- BEGIN SHA linux-x64 -->[0-9a-f]*' \
  | grep -o '[0-9a-f]\{64\}' | head -1 || true)"
[ -n "$RUNNER_SHA" ] || die "could not resolve the sha256 for runner v${RUNNER_VERSION} — check the version exists"
log "  sha256=${RUNNER_SHA}"

for i in "${!HOSTS[@]}"; do
  HOST="${HOSTS[$i]}"
  NAME="ci-runner-$((i + 1))"

  if [ "$AUTH_MODE" = "pre-minted" ]; then
    REG_TOKEN="${REG_TOKEN_LIST[$i]}"
    log "[$NAME @ $HOST] using pre-minted registration token"
  else
    log "[$NAME @ $HOST] minting a registration token"
    # Short-lived (1 h) and single-purpose. Minted per host so a failure part-way
    # through the loop doesn't leave a stale shared token lying around.
    REG_TOKEN="$(curl -sSL -X POST \
      -H "Authorization: Bearer ${GITHUB_TOKEN}" \
      -H "Accept: application/vnd.github+json" \
      "https://api.github.com/repos/${GITHUB_REPOSITORY}/actions/runners/registration-token" \
      | python3 -c 'import json,sys; print(json.load(sys.stdin).get("token",""))')"
    [ -n "$REG_TOKEN" ] || die "failed to mint a registration token — is GITHUB_TOKEN a classic PAT with 'repo' scope, and authorized for SSO if the org enforces it?"
  fi

  log "[$NAME] shipping job hooks"
  ssh "${SSH_USER}@${HOST}" 'sudo install -d -m 0755 -o runner -g runner /opt/actions-hooks'
  scp -q "$SCRIPT_DIR"/hooks/*.sh "${SSH_USER}@${HOST}:/tmp/"
  ssh "${SSH_USER}@${HOST}" '
    sudo install -m 0755 -o runner -g runner /tmp/job-started.sh   /opt/actions-hooks/job-started.sh
    sudo install -m 0755 -o runner -g runner /tmp/job-completed.sh /opt/actions-hooks/job-completed.sh
    rm -f /tmp/job-started.sh /tmp/job-completed.sh'

  log "[$NAME] installing + registering the runner"
  # Tokens travel as env vars over the SSH channel, never in argv.
  ssh "${SSH_USER}@${HOST}" \
    "REG_TOKEN='$REG_TOKEN' \
     GITHUB_REPOSITORY='$GITHUB_REPOSITORY' \
     RUNNER_NAME='$NAME' \
     RUNNER_LABELS='$RUNNER_LABELS' \
     RUNNER_FILE='$RUNNER_FILE' \
     RUNNER_SHA='$RUNNER_SHA' \
     RUNNER_VERSION='$RUNNER_VERSION' \
     bash -s" <<'REMOTE'
set -euo pipefail

RUNNER_DIR=/home/runner/actions-runner

# Every step runs as the user that owns the thing it touches. /home/runner is
# mode 0750, so the SSH user (admin) cannot even traverse into it — each command
# is wrapped in `sudo -u runner` (runner-owned files) or plain `sudo` (systemd),
# and never relies on the calling shell's cwd.
sudo install -d -o runner -g runner -m 0755 "$RUNNER_DIR"

# If a runner is already installed, stop it and drop the LOCAL config.
#
# `remove --local` deliberately, not `remove --token`: a registration token is
# SINGLE-USE, so spending it here would leave nothing for the actual
# registration below. --local tears down the on-box config without calling
# GitHub; the stale server-side registration is then superseded by --replace,
# which reuses the same runner name.
if sudo test -f "${RUNNER_DIR}/svc.sh"; then
  echo "existing runner found — stopping and unconfiguring locally"
  sudo bash -c "cd '${RUNNER_DIR}' && ./svc.sh stop || true; ./svc.sh uninstall || true"
  sudo -u runner bash -c "cd '${RUNNER_DIR}' && ./config.sh remove --local || true"
fi

sudo -u runner bash -c "
  set -euo pipefail
  cd '${RUNNER_DIR}'
  if [ ! -f './${RUNNER_FILE}' ]; then
    echo 'downloading runner v${RUNNER_VERSION}'
    curl -fsSL -o '${RUNNER_FILE}' \
      'https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/${RUNNER_FILE}'
  fi
  echo '${RUNNER_SHA}  ${RUNNER_FILE}' | sha256sum -c -
  tar xzf './${RUNNER_FILE}'
"

# Wire the cleanup hooks. Critical for a PERSISTENT runner: without them the E2E
# cluster's containers, the tessellation_common network, and root-owned node data
# survive into the next job and break it.
sudo -u runner tee "${RUNNER_DIR}/.env" >/dev/null <<EOF
ACTIONS_RUNNER_HOOK_JOB_STARTED=/opt/actions-hooks/job-started.sh
ACTIONS_RUNNER_HOOK_JOB_COMPLETED=/opt/actions-hooks/job-completed.sh
EOF

# NOT --ephemeral: this pool is persistent, so the runner stays registered and
# picks up job after job. (The autoscaled variant is the ephemeral one.)
sudo -u runner bash -c "
  set -euo pipefail
  cd '${RUNNER_DIR}'
  ./config.sh --unattended --replace \
    --url 'https://github.com/${GITHUB_REPOSITORY}' \
    --token '${REG_TOKEN}' \
    --name '${RUNNER_NAME}' \
    --labels '${RUNNER_LABELS}' \
    --work _work
"

sudo bash -c "cd '${RUNNER_DIR}' && ./svc.sh install runner && ./svc.sh start"
sleep 3
sudo bash -c "cd '${RUNNER_DIR}' && ./svc.sh status" | head -20
REMOTE

  log "[$NAME] done"
done

log "All runners registered with labels: ${RUNNER_LABELS}"
log "Verify at: https://github.com/${GITHUB_REPOSITORY}/settings/actions/runners"
