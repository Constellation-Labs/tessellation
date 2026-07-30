terraform {
  required_version = ">= 1.0"

  # State lives under a per-environment workspace (Terraform forbids variables in
  # backend config). Environments are separated by workspaces; rename the bucket below
  # "tessellation-testnet" if you prefer a dedicated per-stack bucket. State stays on AWS
  # S3. Select the env with `terraform workspace select <env>`. (Independent of the app's snapshot S3 -> Hetzner Object Storage move).
  backend "s3" {
    bucket               = "tessellation-nightly"
    key                  = "terraform.tfstate"
    region               = "us-west-1"
    workspace_key_prefix = "tessellation-cluster"
  }

  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.45"
    }
  }
}

provider "hcloud" {
  token = var.hcloud_token
}

# NOTE: No hcloud_network / hcloud_network_subnet. Testnet addresses every node
# by PUBLIC IP (CL_EXTERNAL_IP, --l0-peer-host, node.l0Peers, scrape targets),
# so the private Cloud Network is intentionally dropped. Re-add it
# only if you later want a private management path for east-west traffic.

# ---------------------------------------------------------------------------
# SSH keys
# ---------------------------------------------------------------------------
resource "hcloud_ssh_key" "deploy" {
  name       = "${var.environment}-deploy"
  public_key = var.deploy_ssh_public_key
}

resource "hcloud_ssh_key" "team" {
  for_each   = { for idx, key in var.team_ssh_keys : idx => key }
  name       = "${var.environment}-team-${each.key}"
  public_key = each.value
}

# ---------------------------------------------------------------------------
# Base firewall — attached to every server at creation.
# Only rules whose sources are known up front (CIDRs / world). Do NOT reference
# server IPs here: the servers take this firewall via firewall_ids, so a rule
# sourced from those same servers' IPs would create a dependency cycle. The
# source-scoped JMX/exporter rules live in hcloud_firewall.scrape below and are
# attached separately.
# ---------------------------------------------------------------------------
resource "hcloud_firewall" "base" {
  name = "${var.environment}-base"

  # SSH — team / VPN only
  dynamic "rule" {
    for_each = length(var.allowed_ssh_cidrs) > 0 ? [1] : []
    content {
      direction  = "in"
      protocol   = "tcp"
      port       = "22"
      source_ips = var.allowed_ssh_cidrs
    }
  }

  # GL0 / GL1 public API — world-facing (wallets, explorers, external peers)
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "9000"
    source_ips = ["0.0.0.0/0", "::/0"]
  }
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "9010"
    source_ips = ["0.0.0.0/0", "::/0"]
  }

  # GL0 / GL1 p2p — testnet peers dial in from anywhere
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "9001"
    source_ips = ["0.0.0.0/0", "::/0"]
  }
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "9011"
    source_ips = ["0.0.0.0/0", "::/0"]
  }

  # GL0 / GL1 CLI — admin only (deliberately not lumped into a broad
  # 9000-9089 block exposed to allowed_api_cidrs)
  dynamic "rule" {
    for_each = length(var.allowed_ssh_cidrs) > 0 ? [1] : []
    content {
      direction  = "in"
      protocol   = "tcp"
      port       = "9002"
      source_ips = var.allowed_ssh_cidrs
    }
  }
  dynamic "rule" {
    for_each = length(var.allowed_ssh_cidrs) > 0 ? [1] : []
    content {
      direction  = "in"
      protocol   = "tcp"
      port       = "9012"
      source_ips = var.allowed_ssh_cidrs
    }
  }

  # Grafana (monitoring node). Scoped to the team CIDR; widen to the world only
  # if you intend to share dashboards (aws-grafana ran anonymous-viewer, so this
  # may be desired). Tie it to its own var rather than SSH if they diverge.
  dynamic "rule" {
    for_each = length(var.admin_cidrs) > 0 ? [1] : []
    content {
      direction  = "in"
      protocol   = "tcp"
      port       = "3000"
      source_ips = var.admin_cidrs
    }
  }

  # Outbound: all (S3 / Object Storage, image pulls, peer dial-out)
  rule {
    direction       = "out"
    protocol        = "tcp"
    port            = "any"
    destination_ips = ["0.0.0.0/0", "::/0"]
  }
  rule {
    direction       = "out"
    protocol        = "udp"
    port            = "any"
    destination_ips = ["0.0.0.0/0", "::/0"]
  }
  rule {
    direction       = "out"
    protocol        = "icmp"
    destination_ips = ["0.0.0.0/0", "::/0"]
  }

  labels = {
    environment = var.environment
    managed_by  = "terraform"
  }
}

# ---------------------------------------------------------------------------
# Source-scoped firewall — metric exporters + Prometheus/ClickHouse restricted
# to the cluster's own public IPs / admin CIDRs. The exporters have no auth, so
# this source scoping is the only protection — never widen it to 0.0.0.0/0.
# Sources are the servers' computed public IPs, so this firewall is ATTACHED
# SEPARATELY (below) instead of via the servers' firewall_ids, which would
# otherwise create a cycle. This resolves automatically in a single apply via
# the dependency graph.
# ---------------------------------------------------------------------------
locals {
  # All cluster public IPs as /32 CIDRs for source-scoped rules.
  cluster_public_cidrs = [for ip in module.cluster.all_public_ips : "${ip}/32"]
}

resource "hcloud_firewall" "scrape" {
  name = "${var.environment}-scrape"

  # Metric exporters scraped by the monitoring node (see prometheus.yaml):
  # process-exporter (9256) + network-process-exporter (9435). Scoped to the
  # cluster's own public IPs. (JMX 9100 and the 9436/9437 exporters were opened
  # previously but nothing scrapes them and JMX isn't enabled on the nodes, so
  # they're intentionally not exposed.)
  dynamic "rule" {
    for_each = toset(["9256", "9435"])
    content {
      direction  = "in"
      protocol   = "tcp"
      port       = rule.value
      source_ips = local.cluster_public_cidrs
    }
  }

  # ClickHouse (8123) — admin CIDRs only (as on live n3)
  dynamic "rule" {
    for_each = length(var.admin_cidrs) > 0 ? [1] : []
    content {
      direction  = "in"
      protocol   = "tcp"
      port       = "8123"
      source_ips = var.admin_cidrs
    }
  }

  # Prometheus — monitoring node only (loopback in practice). Guarded like the
  # sibling admin rules: an empty admin_cidrs must drop the rule, not apply an
  # empty source_ips (which terraform rejects).
  dynamic "rule" {
    for_each = length(var.admin_cidrs) > 0 ? [1] : []
    content {
      direction  = "in"
      protocol   = "tcp"
      port       = "9090"
      source_ips = var.admin_cidrs
    }
  }

  labels = {
    environment = var.environment
    managed_by  = "terraform"
  }
}

resource "hcloud_firewall_attachment" "scrape" {
  firewall_id = hcloud_firewall.scrape.id
  server_ids  = module.cluster.all_server_ids
}

# ---------------------------------------------------------------------------
# Cluster: 3 source nodes (+ state volumes) + streaming + monitoring
# ---------------------------------------------------------------------------
module "cluster" {
  source = "./components/cluster"

  environment            = var.environment
  location               = var.location
  datacenter             = var.datacenter
  node_count             = var.node_count
  node_server_type       = var.node_server_type
  streaming_server_type  = var.streaming_server_type
  monitoring_server_type = var.monitoring_server_type
  data_volume_size       = var.data_volume_size
  firewall_ids           = [hcloud_firewall.base.id]
  team_ssh_keys          = var.team_ssh_keys

  ssh_key_ids = concat(
    [hcloud_ssh_key.deploy.id],
    [for k in hcloud_ssh_key.team : k.id]
  )
}
