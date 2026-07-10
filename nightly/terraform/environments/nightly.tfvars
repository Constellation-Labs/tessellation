environment = "nightly"
location    = "hel1"

# Hetzner Cloud Network
network_cidr = "10.0.0.0/16"
subnet_cidr  = "10.0.1.0/24"

# Server types
# CPX41: 8 AMD vCPU, 16 GB RAM (~€15/month)
# CPX21: 3 AMD vCPU, 4 GB RAM (~€5/month)
node_server_type      = "cpx41"
streaming_server_type = "cpx21"

# SSH access - restrict to team IPs in production
# TODO: Replace with actual team/VPN CIDR blocks
allowed_ssh_cidrs = ["0.0.0.0/0", "::/0"]
allowed_api_cidrs = ["0.0.0.0/0", "::/0"]

# SSH keys - set via CLI or secrets
# deploy_ssh_public_key = "ssh-ed25519 AAAA..."
# team_ssh_keys = ["ssh-ed25519 AAAA..."]
