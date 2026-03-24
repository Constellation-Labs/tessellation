terraform {
  required_version = ">= 1.0"

  backend "s3" {
    bucket               = "tessellation-nightly"
    key                  = "nightly/terraform.tfstate"
    region               = "us-west-1"
    workspace_key_prefix = "nightly"
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

# Cloud Network for private communication between instances
resource "hcloud_network" "nightly" {
  name     = "nightly-${var.environment}-network"
  ip_range = var.network_cidr

  labels = {
    environment = var.environment
    managed_by  = "terraform"
  }
}

resource "hcloud_network_subnet" "nightly" {
  network_id   = hcloud_network.nightly.id
  type         = "cloud"
  network_zone = "eu-central"
  ip_range     = var.subnet_cidr
}

# SSH Keys
resource "hcloud_ssh_key" "deploy" {
  name       = "nightly-${var.environment}-deploy"
  public_key = var.deploy_ssh_public_key
}

resource "hcloud_ssh_key" "team" {
  for_each   = { for idx, key in var.team_ssh_keys : idx => key }
  name       = "nightly-${var.environment}-team-${each.key}"
  public_key = each.value
}

# Firewall
resource "hcloud_firewall" "nightly" {
  name = "nightly-${var.environment}-firewall"

  # SSH from allowed IPs
  dynamic "rule" {
    for_each = length(var.allowed_ssh_cidrs) > 0 ? [1] : []
    content {
      direction  = "in"
      protocol   = "tcp"
      port       = "22"
      source_ips = var.allowed_ssh_cidrs
    }
  }

  # Tessellation ports (GL0: 9000-9002, GL1: 9010-9012, ML0: 9020-9022, CL1: 9030-9032, DL1: 9040-9042)
  # Allow from private network and allowed CIDRs
  # Split around 9090 (Prometheus) which is restricted to private subnet only below
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "9000-9089"
    source_ips = concat([var.subnet_cidr], var.allowed_api_cidrs)
  }

  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "9091-9099"
    source_ips = concat([var.subnet_cidr], var.allowed_api_cidrs)
  }

  # Grafana
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "3000"
    source_ips = var.allowed_ssh_cidrs
  }

  # Prometheus
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "9090"
    source_ips = [var.subnet_cidr]
  }

  # Node exporter
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "9100"
    source_ips = [var.subnet_cidr]
  }

  # All outbound
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

# Cluster module
module "cluster" {
  source = "./components/cluster"

  environment          = var.environment
  location             = var.location
  node_server_type     = var.node_server_type
  streaming_server_type = var.streaming_server_type
  network_id           = hcloud_network.nightly.id
  firewall_ids         = [hcloud_firewall.nightly.id]
  subnet_cidr          = var.subnet_cidr
  team_ssh_keys        = var.team_ssh_keys

  ssh_key_ids = concat(
    [hcloud_ssh_key.deploy.id],
    [for k in hcloud_ssh_key.team : k.id]
  )

  depends_on = [hcloud_network_subnet.nightly]
}
