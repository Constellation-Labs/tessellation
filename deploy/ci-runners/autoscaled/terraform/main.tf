terraform {
  required_version = ">= 1.0"

  # SEPARATE STATE from deploy/terraform (the hypergraph cluster). This stack
  # lives in its own Hetzner Cloud PROJECT with its own token, because
  # github-hetzner-runners requires a dedicated project per repository and
  # enumerates/deletes servers in whatever project it is given. Sharing a project
  # with testnet/nightly would put chain nodes within reach of the autoscaler.
  #
  # Drop a local backend_override.tf (gitignored) containing
  #   terraform { backend "local" {} }
  # to run without AWS credentials.
  backend "s3" {
    bucket = "tessellation-nightly"
    # Distinct key per variant — the autoscaled and fixed stacks must never share
    # state, so both can exist (even simultaneously) without clobbering.
    key    = "ci-runners-autoscaled/terraform.tfstate"
    region = "us-west-1"
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

# ---------------------------------------------------------------------------
# SCOPE OF THIS STACK
#
# Manages ONLY the always-on controller box. The E2E runner servers are created
# and destroyed by the autoscaler at job granularity and are deliberately NOT
# terraform-managed — they are cattle with a ~30 minute lifespan, and putting
# them in state would mean permanent drift. `terraform plan` showing no runner
# servers is correct, even while nine of them are running.
# ---------------------------------------------------------------------------

resource "hcloud_ssh_key" "team" {
  for_each   = { for idx, key in var.team_ssh_keys : idx => key }
  name       = "ci-runners-team-${each.key}"
  public_key = each.value
}

resource "hcloud_firewall" "controller" {
  name = "ci-runners-controller"

  # SSH — team/VPN only. The dashboard (8090) and metrics (9099) are bound to
  # loopback in config.yaml and reached over an SSH tunnel, so they are
  # intentionally not opened here.
  dynamic "rule" {
    for_each = length(var.allowed_ssh_cidrs) > 0 ? [1] : []
    content {
      direction  = "in"
      protocol   = "tcp"
      port       = "22"
      source_ips = var.allowed_ssh_cidrs
    }
  }

  # Outbound: GitHub API + Hetzner API + apt/PyPI.
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
    component  = "ci-runners"
    managed_by = "terraform"
  }
}

resource "hcloud_server" "controller" {
  name         = "ci-runners-controller"
  server_type  = var.controller_server_type
  image        = "ubuntu-24.04"
  location     = var.location
  ssh_keys     = [for k in hcloud_ssh_key.team : k.id]
  firewall_ids = [hcloud_firewall.controller.id]

  user_data = templatefile("${path.module}/templates/controller-init.tpl", {
    hostname = "ci-runners-controller"
    ssh_keys = var.team_ssh_keys
  })

  labels = {
    component  = "ci-runners"
    role       = "controller"
    managed_by = "terraform"
  }

  # Matches deploy/terraform: cloud-init and firewall attachment drift after
  # first boot shouldn't force a rebuild of a box holding live CI credentials.
  lifecycle {
    ignore_changes = [user_data, firewall_ids]
  }
}
