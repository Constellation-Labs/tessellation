# Genesis Node (Machine 1)
# Runs GL0-0, GL1-0, ML0-0, CL1-0
resource "hcloud_server" "genesis" {
  name        = "nightly-${var.environment}-genesis"
  server_type = var.node_server_type
  image       = "ubuntu-22.04"
  location    = var.location
  ssh_keys    = var.ssh_key_ids
  firewall_ids = var.firewall_ids

  user_data = templatefile("${path.module}/../../templates/node-init.tpl", {
    hostname  = "nightly-genesis"
    ssh_keys  = var.team_ssh_keys
    node_role = "genesis"
    peer_ips  = []
  })

  labels = {
    environment = var.environment
    role        = "genesis"
    managed_by  = "terraform"
  }

  lifecycle {
    ignore_changes = [user_data]
  }
}

resource "hcloud_server_network" "genesis" {
  server_id  = hcloud_server.genesis.id
  network_id = var.network_id
  ip         = cidrhost(var.subnet_cidr, 11) # 10.0.1.11
}

# Validator Nodes (Machines 2-3)
resource "hcloud_server" "validators" {
  count = 2

  name        = "nightly-${var.environment}-validator-${count.index + 1}"
  server_type = var.node_server_type
  image       = "ubuntu-22.04"
  location    = var.location
  ssh_keys    = var.ssh_key_ids
  firewall_ids = var.firewall_ids

  user_data = templatefile("${path.module}/../../templates/node-init.tpl", {
    hostname  = "nightly-validator-${count.index + 1}"
    ssh_keys  = var.team_ssh_keys
    node_role = "validator"
    peer_ips  = []
  })

  labels = {
    environment = var.environment
    role        = "validator"
    index       = tostring(count.index + 1)
    managed_by  = "terraform"
  }

  lifecycle {
    ignore_changes = [user_data]
  }
}

resource "hcloud_server_network" "validators" {
  count = 2

  server_id  = hcloud_server.validators[count.index].id
  network_id = var.network_id
  ip         = cidrhost(var.subnet_cidr, 12 + count.index) # 10.0.1.12, 10.0.1.13
}

# Snapshot Streaming (Machine 4)
resource "hcloud_server" "streaming" {
  name        = "nightly-${var.environment}-streaming"
  server_type = var.streaming_server_type
  image       = "ubuntu-22.04"
  location    = var.location
  ssh_keys    = var.ssh_key_ids
  firewall_ids = var.firewall_ids

  user_data = templatefile("${path.module}/../../templates/node-init.tpl", {
    hostname  = "nightly-streaming"
    ssh_keys  = var.team_ssh_keys
    node_role = "streaming"
    peer_ips  = [
      cidrhost(var.subnet_cidr, 11), # genesis
      cidrhost(var.subnet_cidr, 12), # validator-1
      cidrhost(var.subnet_cidr, 13), # validator-2
    ]
  })

  labels = {
    environment = var.environment
    role        = "streaming"
    managed_by  = "terraform"
  }

  lifecycle {
    ignore_changes = [user_data]
  }
}

resource "hcloud_server_network" "streaming" {
  server_id  = hcloud_server.streaming.id
  network_id = var.network_id
  ip         = cidrhost(var.subnet_cidr, 14) # 10.0.1.14
}
