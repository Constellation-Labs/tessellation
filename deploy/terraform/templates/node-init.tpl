#!/bin/bash
# Cloud-init for cluster node: ${hostname}
# Role: ${node_role}
#
# Notes:
#   - mounts a Hetzner Volume at /opt/tessellation when data_volume = true
#   - UFW is ENABLED here (host firewall block below) as defense-in-depth behind
#     the Hetzner Cloud Firewall, mirroring the live nodes (n0-n3)

set -e
export DEBIAN_FRONTEND=noninteractive

# Run apt update + upgrade FIRST, right after the server boots. Use a lock
# timeout so we wait out apt-daily / unattended-upgrades holding the dpkg lock
# at first boot instead of aborting cloud-init.
APT="apt-get -o DPkg::Lock::Timeout=600 -y"

# --- System: update + full upgrade ------------------------------------------
$APT update
$APT upgrade

# --- Docker CE --------------------------------------------------------------
$APT install ca-certificates curl gnupg jq lsb-release

install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
chmod a+r /etc/apt/keyrings/docker.gpg

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(lsb_release -cs) stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null

$APT update
$APT install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

systemctl enable docker
systemctl start docker

# Create the operational user 'admin' (uid/gid 1000 — matching the AWS source
# nodes / tn1) with docker access + passwordless sudo. Hetzner's Ubuntu image is
# root-only; we do not operate as root.
getent group admin >/dev/null 2>&1 || groupadd -g 1000 admin
id admin >/dev/null 2>&1 || useradd -m -u 1000 -g 1000 -s /bin/bash admin
usermod -aG docker admin
echo "admin ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/90-admin && chmod 0440 /etc/sudoers.d/90-admin

# --- Hostname ---------------------------------------------------------------
hostnamectl set-hostname ${hostname}

# --- SSH keys ---------------------------------------------------------------
# Seed admin's authorized_keys with the Hetzner-injected deploy key (it lands on
# root) so 'admin' can log in, then append any team keys.
install -d -m 700 -o admin -g admin /home/admin/.ssh
[ -f /root/.ssh/authorized_keys ] && cat /root/.ssh/authorized_keys >> /home/admin/.ssh/authorized_keys
%{ for ssh_key in ssh_keys ~}
echo '${ssh_key}' >> /home/admin/.ssh/authorized_keys
%{ endfor ~}
chown admin:admin /home/admin/.ssh/authorized_keys
chmod 600 /home/admin/.ssh/authorized_keys

# --- State volume (source nodes only) ---------------------------------------
# The Hetzner Volume is attached to this server and pre-formatted ext4 by
# Terraform (hcloud_volume.format = "ext4"). MOUNT it at /opt/tessellation —
# DO NOT reformat, that would wipe migrated chain state. Wait for the device to
# appear (attach can lag first boot), then mount + persist in fstab with nofail.
%{ if data_volume ~}
for i in $(seq 1 30); do
  VOL=$(ls /dev/disk/by-id/scsi-0HC_Volume_* 2>/dev/null | head -1 || true)
  [ -n "$VOL" ] && break
  sleep 2
done
mkdir -p /opt/tessellation
if [ -n "$VOL" ]; then
  mountpoint -q /opt/tessellation || mount -o discard,defaults "$VOL" /opt/tessellation
  grep -q "/opt/tessellation" /etc/fstab || echo "$VOL /opt/tessellation ext4 discard,nofail,defaults 0 0" >> /etc/fstab
else
  echo "WARNING: no Hetzner Volume found to mount at /opt/tessellation" >&2
fi
%{ endif ~}

# --- Working dirs (REMOTE_DIR for docker/bin/compose-runner.sh) -------------
# On source nodes this lands on the mounted volume; elsewhere on the local disk.
mkdir -p /opt/tessellation/{data,logs,config}
chown -R admin:admin /opt/tessellation

# --- Host firewall (UFW) — mirrors the live nodes (n0-n3) -------------------
# Defense-in-depth behind the Hetzner Cloud Firewall (primary control; main.tf).
# Source-agnostic ports are opened here at boot; source-scoped rules (exporters
# -> monitoring node IP, admin services -> admin CIDRs) are enforced by the cloud
# firewall, since those source IPs aren't all known at first boot. Keep the two
# in lockstep.
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
%{ if node_role == "node" ~}
# GL0 9000/9001 + GL1 9010/9011 (public API + p2p) — world-facing.
# CLI ports 9002/9012 are intentionally left closed (admin via SSH only).
ufw allow 9000/tcp
ufw allow 9001/tcp
ufw allow 9010/tcp
ufw allow 9011/tcp
# Per-process exporters scraped by the monitoring node:
# process-exporter 9256, network-process-exporter 9435. The cloud firewall
# (testnet-scrape in main.tf) scopes the source to the cluster IPs; UFW just
# needs the ports open or Prometheus scrapes hang (BLOCKED at the host).
ufw allow 9256/tcp
ufw allow 9435/tcp
%{ endif ~}
%{ if node_role == "monitoring" ~}
# Grafana 3000 / Prometheus 9090 / ClickHouse 8123 — opened here; the cloud
# firewall scopes the source to admin CIDRs (as on live n3).
ufw allow 3000/tcp
ufw allow 9090/tcp
ufw allow 8123/tcp
%{ endif ~}
ufw --force enable

echo "${hostname} (${node_role}) initialization complete"
