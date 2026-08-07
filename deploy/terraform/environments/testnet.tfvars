environment = "testnet"

# Location: Helsinki (Hetzner places servers within the location's datacenters).
location = "hel1"

# Server types (bigger than nightly — ~18 GB heap per source node)
node_server_type       = "cpx62" # 16 vCPU / 32 GB (new-gen; available in hel1)
streaming_server_type  = "cpx52" # 12 vCPU / 24 GB (room for 16 GB heap + Postgres)
monitoring_server_type = "cpx42" # 8 vCPU / 16 GB

# Per-node state volume. Capped at 333 GB to fit the current ~1000 GB Hetzner volume
# quota (3 x 333 = 999). Bump back toward 400+ once the project volume quota is raised.
data_volume_size = 333

# Source nodes
node_count = 3

# Access control — FAIL-CLOSED. The committed defaults are empty so a verbatim
# `apply` exposes nothing. Provide real team/VPN CIDRs at apply time (-var,
# TF_VAR_allowed_ssh_cidrs / TF_VAR_admin_cidrs, or a secrets-managed
# *.auto.tfvars) — SSH and admin (Grafana/Prometheus/ClickHouse) access require it.
allowed_ssh_cidrs = [] # REQUIRED for SSH — e.g. ["203.0.113.0/24"]; do not commit real IPs
admin_cidrs       = [] # REQUIRED for Grafana/CLI/ClickHouse — e.g. ["203.0.113.0/24"]

# SSH keys — set via CLI (-var) or a secrets-managed *.auto.tfvars, not here.
# deploy_ssh_public_key = "ssh-ed25519 AAAA..."
# team_ssh_keys         = ["ssh-ed25519 AAAA...", "ssh-ed25519 BBBB..."]
