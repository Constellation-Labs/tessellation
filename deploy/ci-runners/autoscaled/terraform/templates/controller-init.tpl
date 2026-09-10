#!/bin/bash
# Cloud-init for the CI runners autoscaler controller: ${hostname}
#
# Deliberately minimal — no Docker, no data volume. This box runs one Python
# service. Everything the autoscaler needs is installed by
# deploy/ci-runners/autoscaled/bootstrap-controller.sh, which is re-runnable; keeping
# cloud-init thin means a config rollout never requires reprovisioning.
#
# Mirrors deploy/terraform/templates/node-init.tpl: `admin` user at uid 1000 with
# passwordless sudo, and UFW enabled as defense-in-depth behind the Hetzner Cloud
# Firewall.

set -e
export DEBIAN_FRONTEND=noninteractive

hostnamectl set-hostname ${hostname}

APT="apt-get -o DPkg::Lock::Timeout=600 -y"

$APT update
$APT upgrade
$APT install ca-certificates curl gnupg jq python3 python3-venv python3-pip ufw fail2ban

# --- Operational user -------------------------------------------------------
# uid/gid 1000 to match the tessellation cluster hosts, so the same team SSH
# config (`User admin`) works everywhere.
if ! id -u admin >/dev/null 2>&1; then
  useradd -m -u 1000 -s /bin/bash admin
fi
echo "admin ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/admin
chmod 0440 /etc/sudoers.d/admin

install -d -m 0700 -o admin -g admin /home/admin/.ssh
: > /home/admin/.ssh/authorized_keys
%{ for key in ssh_keys ~}
echo "${key}" >> /home/admin/.ssh/authorized_keys
%{ endfor ~}
chmod 0600 /home/admin/.ssh/authorized_keys
chown admin:admin /home/admin/.ssh/authorized_keys

# --- Host firewall ----------------------------------------------------------
# The dashboard (8090) and metrics (9099) are loopback-bound in config.yaml and
# reached via SSH tunnel, so only 22 is opened here.
ufw --force reset
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw --force enable

systemctl enable --now fail2ban

# --- Unattended security updates -------------------------------------------
# This box holds long-lived GitHub and Hetzner API tokens; keep it patched.
$APT install unattended-upgrades
dpkg-reconfigure -f noninteractive unattended-upgrades

echo "controller cloud-init complete: $(date -Is)"
