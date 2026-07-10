#!/bin/bash
# Cloud-init script for nightly cluster node: ${hostname}
# Role: ${node_role}

set -e

# Update system
apt-get update
apt-get upgrade -y

# Install Docker and dependencies
apt-get install -y \
  ca-certificates \
  curl \
  gnupg \
  jq \
  lsb-release

# Add Docker GPG key and repository
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
chmod a+r /etc/apt/keyrings/docker.gpg

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(lsb_release -cs) stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null

apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Enable and start Docker
systemctl enable docker
systemctl start docker

# Add default user to docker group
usermod -aG docker ubuntu

# Set hostname
hostnamectl set-hostname ${hostname}

# Add SSH keys to authorized_keys
%{ for ssh_key in ssh_keys ~}
echo '${ssh_key}' >> /home/ubuntu/.ssh/authorized_keys
%{ endfor ~}

# Create working directories for tessellation
mkdir -p /opt/tessellation/{data,logs,config}
chown -R ubuntu:ubuntu /opt/tessellation

# UFW firewall setup
ufw default deny incoming
ufw default allow outgoing

# Base rules — all nodes
# SSH
ufw allow 22/tcp
# GL0: 9000-9002
ufw allow 9000/tcp
ufw allow 9001/tcp
ufw allow 9002/tcp
# GL1: 9010-9012
ufw allow 9010/tcp
ufw allow 9011/tcp
ufw allow 9012/tcp
# ML0: 9020-9022
ufw allow 9020/tcp
ufw allow 9021/tcp
ufw allow 9022/tcp
# CL1: 9030-9032
ufw allow 9030/tcp
ufw allow 9031/tcp
ufw allow 9032/tcp

# Streaming node runs monitoring — Grafana open, Prometheus + ClickHouse restricted to peer nodes
%{ if node_role == "streaming" ~}
ufw allow 3000/tcp
%{ endif ~}
%{ for ip in peer_ips ~}
ufw allow from ${ip} to any port 8123
ufw allow from ${ip} to any port 9090
%{ endfor ~}

ufw --force enable

echo "${hostname} (${node_role}) initialization complete"
