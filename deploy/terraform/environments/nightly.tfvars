# Nightly environment profile.
#
# All environments share this stack's topology: 3 source nodes + streaming + a
# dedicated monitoring node, each source node with a state volume. This profile
# just runs that topology with cheaper, nightly-sized boxes in the EU.
environment = "nightly"

# Location: Helsinki.
location = "hel1"

# Server types — shared vCPU, cheaper than the testnet defaults
node_server_type       = "cpx52" # 12 vCPU / 24 GB
streaming_server_type  = "cpx52" # 12 vCPU / 24 GB (streaming + Postgres)
monitoring_server_type = "cpx22" # 2 vCPU / 4 GB

# Per-node state volume. Nightly is fresh-genesis and short-lived, so far less
# state than testnet — keep it small.
data_volume_size = 100

# Source nodes
node_count = 3

# Access control — FAIL-CLOSED. Committed defaults are empty so a verbatim
# `apply` exposes nothing. Provide CIDRs at apply time (-var / TF_VAR_* /
# *.auto.tfvars). CI that SSHes to the nightly cluster must pass its runner CIDRs.
allowed_ssh_cidrs = [] # REQUIRED for SSH; do not commit real IPs
admin_cidrs       = [] # REQUIRED for Grafana/CLI/ClickHouse

# SSH keys — set via CLI (-var) or a secrets-managed *.auto.tfvars, not here.
# deploy_ssh_public_key = "ssh-ed25519 AAAA..."
# team_ssh_keys         = ["ssh-ed25519 AAAA...", "ssh-ed25519 BBBB..."]
