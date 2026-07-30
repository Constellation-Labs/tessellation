# Source nodes (machines 1..node_count).
# All identical: each runs run-validator with rollback (GL0 l0 + GL1 l1). There
# is NO genesis role on testnet — the chain is live (~ordinal 3M+) and is never
# re-genesised. node[0] is simply the L0 anchor peer that every L1 and the
# snapshot-streaming node point at.
resource "hcloud_server" "node" {
  count = var.node_count

  name         = "${var.environment}-node-${count.index + 1}"
  server_type  = var.node_server_type
  image        = "ubuntu-24.04"
  location     = var.location != "" ? var.location : null
  datacenter   = var.datacenter != "" ? var.datacenter : null
  ssh_keys     = var.ssh_key_ids
  firewall_ids = var.firewall_ids

  user_data = templatefile("${path.module}/../../templates/node-init.tpl", {
    hostname    = "${var.environment}-node-${count.index + 1}"
    ssh_keys    = var.team_ssh_keys
    node_role   = "node"
    data_volume = true
  })

  labels = {
    environment = var.environment
    role        = "node"
    index       = tostring(count.index + 1)
    managed_by  = "terraform"
  }

  lifecycle {
    ignore_changes = [user_data, firewall_ids]
  }
}

# Per-node state volume (~300+ GB of data/), mounted at /opt/tessellation by
# cloud-init. Decouples the ~300 GB of chain state from the box so reprovisions
# and resizes are safe. Pre-formatted ext4 here; cloud-init mounts (never
# reformats) it — see templates/node-init.tpl.
resource "hcloud_volume" "node_state" {
  count = var.node_count

  name      = "${var.environment}-node-${count.index + 1}-state"
  size      = var.data_volume_size
  server_id = hcloud_server.node[count.index].id
  format    = "ext4"
  automount = false # mounted at /opt/tessellation in cloud-init, not /mnt/HC_Volume_*

  labels = {
    environment = var.environment
    role        = "node"
    managed_by  = "terraform"
  }
}

# Snapshot-streaming node. Also self-hosts Postgres (container, loopback) per
# the migration plan; ~40 GB of state fits the local disk, so no volume by
# default — add one here if the Postgres dataset grows.
resource "hcloud_server" "streaming" {
  name         = "${var.environment}-streaming"
  server_type  = var.streaming_server_type
  image        = "ubuntu-24.04"
  location     = var.location != "" ? var.location : null
  datacenter   = var.datacenter != "" ? var.datacenter : null
  ssh_keys     = var.ssh_key_ids
  firewall_ids = var.firewall_ids

  user_data = templatefile("${path.module}/../../templates/node-init.tpl", {
    hostname    = "${var.environment}-streaming"
    ssh_keys    = var.team_ssh_keys
    node_role   = "streaming"
    data_volume = false
  })

  labels = {
    environment = var.environment
    role        = "streaming"
    managed_by  = "terraform"
  }

  lifecycle {
    ignore_changes = [user_data, firewall_ids]
  }
}

# Monitoring node — DEDICATED Prometheus + Grafana host on its own machine,
# separate from streaming.
resource "hcloud_server" "monitoring" {
  name         = "${var.environment}-monitoring"
  server_type  = var.monitoring_server_type
  image        = "ubuntu-24.04"
  location     = var.location != "" ? var.location : null
  datacenter   = var.datacenter != "" ? var.datacenter : null
  ssh_keys     = var.ssh_key_ids
  firewall_ids = var.firewall_ids

  user_data = templatefile("${path.module}/../../templates/node-init.tpl", {
    hostname    = "${var.environment}-monitoring"
    ssh_keys    = var.team_ssh_keys
    node_role   = "monitoring"
    data_volume = false
  })

  labels = {
    environment = var.environment
    role        = "monitoring"
    managed_by  = "terraform"
  }

  lifecycle {
    ignore_changes = [user_data, firewall_ids]
  }
}
