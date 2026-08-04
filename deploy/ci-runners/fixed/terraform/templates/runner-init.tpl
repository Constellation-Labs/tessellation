#!/bin/bash
# Cloud-init for a fixed CI runner: ${hostname}
#
# OS-level setup only. The GitHub Actions runner itself is installed and
# registered by deploy/ci-runners/fixed/register-runners.sh, which is re-runnable
# — so re-registering or upgrading the runner never needs a reprovision. Mirrors
# the thin-cloud-init convention in deploy/terraform/templates/node-init.tpl.

set -e
export DEBIAN_FRONTEND=noninteractive

hostnamectl set-hostname ${hostname}

APT="apt-get -o DPkg::Lock::Timeout=600 -y"

$APT update
$APT upgrade
# libatomic1: Node.js binaries link against libatomic.so.1 and it is NOT present on
# the minimal Ubuntu cloud image. Without it any node (nvm-installed or otherwise)
# dies with "error while loading shared libraries: libatomic.so.1". GitHub's hosted
# images ship it. Observed as a real E2E failure before it was added here.
# jq / wget / unzip / git are likewise assumed present by docker/bin/*.
$APT install ca-certificates curl wget gnupg jq lsb-release unzip git ufw fail2ban acl libatomic1

# --- Docker CE --------------------------------------------------------------
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
chmod a+r /etc/apt/keyrings/docker.gpg

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(lsb_release -cs) stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null

$APT update
$APT install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Cap container log growth. These runners are PERSISTENT (unlike the autoscaled
# variant, where the whole server is discarded after one job), so 13 chatty JVM
# containers per job would otherwise fill the disk over days.
cat > /etc/docker/daemon.json <<'EOF'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "100m", "max-file": "3" }
}
EOF

systemctl enable docker
systemctl restart docker

# --- Users ------------------------------------------------------------------
# `admin` (uid 1000) for operators, matching the tessellation cluster hosts so
# the same team SSH config (`User admin`) works everywhere.
if ! id -u admin >/dev/null 2>&1; then
  useradd -m -u 1000 -s /bin/bash admin
fi
usermod -aG docker admin
echo "admin ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/admin
chmod 0440 /etc/sudoers.d/admin

install -d -m 0700 -o admin -g admin /home/admin/.ssh
: > /home/admin/.ssh/authorized_keys
%{ for key in ssh_keys ~}
echo "${key}" >> /home/admin/.ssh/authorized_keys
%{ endfor ~}
chmod 0600 /home/admin/.ssh/authorized_keys
chown admin:admin /home/admin/.ssh/authorized_keys

# `runner` runs the Actions runner service. Needs docker (to start the E2E
# cluster) and passwordless sudo (the workflow's own steps run
# `sudo apt-get install sbt` and `sudo tee`).
if ! id -u runner >/dev/null 2>&1; then
  useradd -m -u 1001 -s /bin/bash runner
fi
usermod -aG docker runner
echo "runner ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/runner
chmod 0440 /etc/sudoers.d/runner

# actions/setup-java and setup-node install here; pre-creating it owned by
# `runner` avoids a first-job permission failure.
install -d -m 0775 -o runner -g runner /opt/hostedtoolcache

# GitHub's hosted runner images let the unprivileged runner user write to
# /usr/local/bin, and workflows rely on it. e2e-just-test.yml installs `just` with
#   curl ... | bash -s -- --to /usr/local/bin
# with NO sudo, which fails on a stock Ubuntu box where /usr/local/bin is
# root-owned 0755 (observed: "Install just" step failing on the first real job).
# Grant group write rather than chowning to runner, so root stays the owner.
chown root:runner /usr/local/bin
chmod 2775 /usr/local/bin

# nvm for the runner user. docker/bin/install_dependencies.sh gates its Node.js
# setup on `[ -d "$HOME/.nvm" ]` (check_node, ~line 316) — it does NOT look for
# node on PATH. GitHub's hosted images ship nvm, so that check short-circuits
# there; on a bare box it does not, and `just _check_deps` then installs nvm +
# node mid-job. Pre-installing it here restores parity and keeps that cost out of
# every job.
sudo -u runner bash -c '
  export NVM_DIR="$HOME/.nvm"
  curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
  . "$NVM_DIR/nvm.sh"
  nvm install 18
  nvm alias default 18
'

# --- Kernel / limits tuning for ~13 concurrent JVMs -------------------------
# The default vm.max_map_count (65530) is the one that actually bites: 13 JVMs
# plus docker's overlay mounts exhaust it and the JVM dies with
# "Native memory allocation (mmap) failed" / OutOfMemoryError long before RAM
# is exhausted.
cat > /etc/sysctl.d/99-ci-runner.conf <<'EOF'
vm.max_map_count = 262144
fs.file-max = 2097152
fs.inotify.max_user_instances = 8192
fs.inotify.max_user_watches = 524288
# The E2E harness and snapshot pollers open many short-lived connections between
# the 13 containers; widen the ephemeral range and reclaim TIME_WAIT sockets.
net.ipv4.ip_local_port_range = 10240 65535
net.ipv4.tcp_tw_reuse = 1
net.core.somaxconn = 4096
EOF
sysctl --system

cat > /etc/security/limits.d/99-ci-runner.conf <<'EOF'
*  soft  nofile  1048576
*  hard  nofile  1048576
*  soft  nproc   unlimited
*  hard  nproc   unlimited
EOF

# limits.d does not apply to systemd services (the runner is one), so set the
# systemd-wide defaults too.
mkdir -p /etc/systemd/system.conf.d
cat > /etc/systemd/system.conf.d/99-ci-runner.conf <<'EOF'
[Manager]
DefaultLimitNOFILE=1048576
EOF
systemctl daemon-reexec

# --- Host firewall ----------------------------------------------------------
# Only SSH inbound. The E2E cluster's 9000-9412 ports are reached from the test
# harness over localhost, never from off-box.
ufw --force reset
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw --force enable

systemctl enable --now fail2ban

$APT install unattended-upgrades
dpkg-reconfigure -f noninteractive unattended-upgrades

echo "ci-runner cloud-init complete: $(date -Is)"
