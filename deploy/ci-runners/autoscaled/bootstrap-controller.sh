#!/usr/bin/env bash
#
# bootstrap-controller.sh — install/update the github-hetzner-runners autoscaler
# on the CI controller box.
#
#   deploy/ci-runners/autoscaled/bootstrap-controller.sh <controller-ip>     # remote (normal)
#   deploy/ci-runners/autoscaled/bootstrap-controller.sh --local             # on the box itself
#
# Idempotent: safe to re-run to roll out a config change or upgrade the package.
#
# Required in the environment (never passed on the command line, so they stay out
# of shell history and the process table):
#   HETZNER_TOKEN       Hetzner Cloud API token for the DEDICATED CI project.
#                       MUST NOT be a token for the testnet/nightly project — the
#                       autoscaler enumerates and deletes servers in whatever
#                       project it is pointed at.
#   GITHUB_TOKEN        GitHub CLASSIC PAT with `repo` scope (manages self-hosted
#                       runners). Fine-grained tokens are NOT supported upstream.
#   GITHUB_REPOSITORY   e.g. Constellation-Labs/tessellation
#
# Optional:
#   SSH_USER            default: admin
#   RUNNER_PKG_VERSION  pin the pip package, e.g. 1.10.0 (default: latest)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SSH_USER="${SSH_USER:-admin}"
RUNNER_PKG_VERSION="${RUNNER_PKG_VERSION:-}"

die() { echo "ERROR: $*" >&2; exit 1; }
log() { echo "==> $*"; }

# --- remote/local dispatch ---------------------------------------------------
if [ "${1:-}" != "--local" ]; then
  TARGET="${1:-}"
  [ -n "$TARGET" ] || die "usage: $0 <controller-ip> | --local"

  for v in HETZNER_TOKEN GITHUB_TOKEN GITHUB_REPOSITORY; do
    [ -n "${!v:-}" ] || die "$v must be set in the environment"
  done

  log "Shipping ci-runners/ to ${SSH_USER}@${TARGET}"
  ssh "${SSH_USER}@${TARGET}" 'rm -rf ~/ci-runners && mkdir -p ~/ci-runners'
  # -r not -a: don't try to preserve local ownership onto the remote box.
  scp -q -r "$SCRIPT_DIR"/* "${SSH_USER}@${TARGET}:~/ci-runners/"

  log "Running bootstrap on the controller"
  # Tokens travel over the SSH channel as env vars, not as argv.
  ssh "${SSH_USER}@${TARGET}" \
    "HETZNER_TOKEN='$HETZNER_TOKEN' \
     GITHUB_TOKEN='$GITHUB_TOKEN' \
     GITHUB_REPOSITORY='$GITHUB_REPOSITORY' \
     RUNNER_PKG_VERSION='$RUNNER_PKG_VERSION' \
     bash ~/ci-runners/bootstrap-controller.sh --local"

  log "Done. Service status:"
  ssh "${SSH_USER}@${TARGET}" 'systemctl --no-pager --lines=20 status github-hetzner-runners || true'
  exit 0
fi

# --- local (on-controller) path ----------------------------------------------
for v in HETZNER_TOKEN GITHUB_TOKEN GITHUB_REPOSITORY; do
  [ -n "${!v:-}" ] || die "$v must be set in the environment"
done

[ "$(id -u)" -eq 0 ] && SUDO="" || SUDO="sudo"
export DEBIAN_FRONTEND=noninteractive
APT="$SUDO apt-get -o DPkg::Lock::Timeout=600 -y"

log "Installing OS packages"
$APT update
$APT install python3 python3-venv python3-pip openssh-client

log "Creating the 'runners' service account"
# System account, no login shell: it only ever runs the autoscaler.
id -u runners >/dev/null 2>&1 || $SUDO useradd --system --create-home \
  --home-dir /var/lib/runners --shell /usr/sbin/nologin runners

log "Installing github-hetzner-runners into /opt/github-hetzner-runners/venv"
$SUDO mkdir -p /opt/github-hetzner-runners
$SUDO python3 -m venv /opt/github-hetzner-runners/venv
$SUDO /opt/github-hetzner-runners/venv/bin/pip install --upgrade pip
if [ -n "$RUNNER_PKG_VERSION" ]; then
  $SUDO /opt/github-hetzner-runners/venv/bin/pip install \
    "testflows.github.hetzner.runners==${RUNNER_PKG_VERSION}"
else
  $SUDO /opt/github-hetzner-runners/venv/bin/pip install --upgrade \
    testflows.github.hetzner.runners
fi
$SUDO /opt/github-hetzner-runners/venv/bin/github-hetzner-runners -v

log "Installing config + scripts to /etc/github-hetzner-runners"
$SUDO mkdir -p /etc/github-hetzner-runners
$SUDO install -m 0644 ~/ci-runners/config.yaml /etc/github-hetzner-runners/config.yaml

log "Writing the token env file (mode 0600)"
# Written via a root-only temp file then moved, so the tokens are never briefly
# world-readable on disk.
TMP_ENV="$(mktemp)"
chmod 600 "$TMP_ENV"
cat > "$TMP_ENV" <<EOF
GITHUB_TOKEN=${GITHUB_TOKEN}
GITHUB_REPOSITORY=${GITHUB_REPOSITORY}
HETZNER_TOKEN=${HETZNER_TOKEN}
EOF
$SUDO install -o root -g runners -m 0640 "$TMP_ENV" /etc/github-hetzner-runners/env
rm -f "$TMP_ENV"

log "Ensuring a debug SSH keypair for the ephemeral runners"
# Installed on every runner so an operator can SSH in and inspect a wedged E2E
# cluster before the server is reaped. Generated once and kept.
if [ ! -f /etc/github-hetzner-runners/runner_key ]; then
  $SUDO ssh-keygen -t ed25519 -N '' -C 'tessellation-ci-runner-debug' \
    -f /etc/github-hetzner-runners/runner_key
fi
# Private key readable by the service group (not world): the autoscaler needs it
# for SSH-based operations such as recycle-without-rebuild and its `ssh`
# subcommand. Operators must use sudo to read it — see README.
$SUDO chown root:runners /etc/github-hetzner-runners/runner_key
$SUDO chmod 0640 /etc/github-hetzner-runners/runner_key
$SUDO chmod 0644 /etc/github-hetzner-runners/runner_key.pub

log "Preparing the log directory"
$SUDO mkdir -p /var/log/github-hetzner-runners
$SUDO chown runners:runners /var/log/github-hetzner-runners

log "Installing the systemd unit"
$SUDO install -m 0644 ~/ci-runners/systemd/github-hetzner-runners.service \
  /etc/systemd/system/github-hetzner-runners.service
$SUDO systemctl daemon-reload
$SUDO systemctl enable github-hetzner-runners
$SUDO systemctl restart github-hetzner-runners

log "Waiting for the service to settle"
for _ in $(seq 1 10); do
  if systemctl is-active --quiet github-hetzner-runners; then
    log "Service is active."
    exit 0
  fi
  sleep 2
done

die "service did not reach active state — check: journalctl -u github-hetzner-runners -n 100"
