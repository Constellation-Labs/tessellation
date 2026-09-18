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

# Wait for the controller to accept SSH, WITHOUT tripping fail2ban.
#
# controller-init.tpl installs and enables fail2ban. Its default sshd jail bans
# an IP after maxretry=5 failed AUTHENTICATIONS within findtime=10m, for
# bantime=10m. sshd starts listening well before cloud-init finishes creating
# the `admin` user, so a naive "retry every few seconds until it works" loop
# produces a burst of genuine auth failures and bans the operator from their own
# new box -- observed 2026-09-16, ~40 attempts in 200s.
#
# So: poll the PORT fast (a TCP connect logs no auth failure and cannot ban
# anyone), then give cloud-init a grace period, then attempt real auth at
# intervals wider than findtime/maxretry (600/5 = 120s). Four attempts at 150s
# stays strictly under the threshold even in the worst case.
wait_for_ssh() {
  local host="$1" i

  log "Waiting for TCP 22 on ${host} (no auth yet -- cannot trip fail2ban)"
  for i in $(seq 1 60); do
    if nc -z -G 3 "$host" 22 2>/dev/null || nc -z -w 3 "$host" 22 2>/dev/null; then
      break
    fi
    sleep 5
  done

  # sshd answers before cloud-init has created `admin`. Authenticating during
  # that window is exactly what gets you banned, so wait it out first.
  log "Port open; allowing 90s for cloud-init to finish creating the admin user"
  sleep 90

  for i in 1 2 3 4; do
    if ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 -o BatchMode=yes \
         "${SSH_USER}@${host}" true 2>/dev/null; then
      log "SSH ready"
      return 0
    fi
    [ "$i" -lt 4 ] && { log "not ready (attempt $i/4); waiting 150s -- deliberately slow, see comment"; sleep 150; }
  done

  die "cannot SSH to ${SSH_USER}@${host} after ~10 min.
  If this box was reachable a moment ago, you are probably fail2ban-banned:
  check with 'ssh root@${host} fail2ban-client status sshd' from an allowed IP,
  or just wait out the 10 minute bantime. Do NOT retry in a tight loop."
}

# --- remote/local dispatch ---------------------------------------------------
if [ "${1:-}" != "--local" ]; then
  TARGET="${1:-}"
  [ -n "$TARGET" ] || die "usage: $0 <controller-ip> | --local"

  for v in HETZNER_TOKEN GITHUB_TOKEN GITHUB_REPOSITORY; do
    [ -n "${!v:-}" ] || die "$v must be set in the environment"
  done

  wait_for_ssh "$TARGET"

  log "Shipping ci-runners/ to ${SSH_USER}@${TARGET}"
  ssh "${SSH_USER}@${TARGET}" 'rm -rf ~/ci-runners && mkdir -p ~/ci-runners'
  # -r not -a: don't try to preserve local ownership onto the remote box.
  #
  # EXPLICIT paths, not a glob: "$SCRIPT_DIR"/* also matches terraform/, which
  # carries .terraform/ provider binaries (~24 MB), ci.auto.tfvars, and -- with a
  # local backend -- terraform state. None of that belongs on the controller, and
  # the glob turns a ~40 KB copy into a 24 MB one over a link to Helsinki.
  scp -q -r \
    "$SCRIPT_DIR/config.yaml" \
    "$SCRIPT_DIR/systemd" \
    "$SCRIPT_DIR/scripts" \
    "$SCRIPT_DIR/bootstrap-controller.sh" \
    "${SSH_USER}@${TARGET}:~/ci-runners/"

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

# config.scripts points at a directory that REPLACES the package's own scripts
# directory wholesale: scale_up.py's get_setup_script()/get_startup_script()
# raise if a file is missing there rather than falling back to the package
# defaults. Rather than vendor copies of every script (and silently pin the
# actions-runner version baked into startup-x64.sh), take the installed
# package's directory as the base and overlay only the file we actually change.
# Rebuilt from scratch on every run, so a package upgrade is picked up and stale
# files cannot linger.
log "Building the runner scripts directory (package defaults + our setup.sh)"
PKG_SCRIPTS="$(/opt/github-hetzner-runners/venv/bin/python -c \
  'import os, testflows.github.hetzner.runners.scripts as s; print(os.path.dirname(s.__file__))')"
[ -d "$PKG_SCRIPTS" ] || die "could not locate the package scripts directory"
$SUDO rm -rf /etc/github-hetzner-runners/scripts
$SUDO mkdir -p /etc/github-hetzner-runners/scripts
$SUDO cp "$PKG_SCRIPTS"/*.sh /etc/github-hetzner-runners/scripts/
$SUDO install -m 0644 ~/ci-runners/scripts/setup.sh \
  /etc/github-hetzner-runners/scripts/setup.sh
log "  scripts dir: $(ls /etc/github-hetzner-runners/scripts | tr '\n' ' ')"

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
# 0600 and owned by the SERVICE USER, not 0640 root:runners.
#
# OpenSSH refuses any private key whose mode has group or other bits set --
# "Permissions 0640 for '...' are too open" -- regardless of who owns it, since
# it tests (perm & 077) != 0. A group-readable key is therefore not a slightly
# looser key, it is an unusable one: the autoscaler creates a server, then can
# never SSH in to run setup.sh, so the runner never registers and the server is
# reaped as a zombie after max_server_ready_time. Silent, and it repeats for
# every job forever. Observed 2026-09-16 on first deploy.
#
# Operators read it with sudo, which is unaffected by the mode.
$SUDO chown runners:runners /etc/github-hetzner-runners/runner_key
$SUDO chmod 0600 /etc/github-hetzner-runners/runner_key
$SUDO chmod 0644 /etc/github-hetzner-runners/runner_key.pub

# Make that key the service user's DEFAULT ssh identity.
#
# server.py:133 builds every connection as
#   ssh -q -o "StrictHostKeyChecking no" -o "UserKnownHostsFile=/dev/null" root@<ip>
# with NO -i, so it uses whatever identity the invoking user has. Generating the
# key under /etc and pointing config.ssh_key at the .pub is not enough: the
# private half is never on the `runners` user's identity path, so every
# connection to a freshly created runner fails with no usable error, the runner
# never registers, and the server is reaped as a zombie. Observed 2026-09-16.
$SUDO install -d -m 0700 -o runners -g runners /var/lib/runners/.ssh
$SUDO tee /var/lib/runners/.ssh/config >/dev/null <<'SSHCFG'
Host *
  IdentityFile /etc/github-hetzner-runners/runner_key
  IdentitiesOnly yes
  StrictHostKeyChecking no
  UserKnownHostsFile /dev/null
SSHCFG
$SUDO chown runners:runners /var/lib/runners/.ssh/config
$SUDO chmod 0600 /var/lib/runners/.ssh/config

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
