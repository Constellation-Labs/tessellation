terraform {
  required_version = ">= 1.0"

  # Distinct key per variant — the autoscaled and fixed stacks must never share
  # state, so both can exist (even simultaneously) without clobbering.
  #
  # Drop a local backend_override.tf (gitignored) containing
  #   terraform { backend "local" {} }
  # to run without AWS credentials.
  backend "s3" {
    bucket = "tessellation-nightly"
    key    = "ci-runners-fixed/terraform.tfstate"
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
# Fixed pool of always-on GitHub Actions runners.
#
# UNLIKE the autoscaled variant, nothing here enumerates or deletes servers it
# doesn't own, so this stack is SAFE to run inside the existing Hetzner project
# alongside the testnet/nightly clusters. Terraform only manages the resources
# declared below (they carry component=ci-runners labels and a distinct name
# prefix), and its state is separate from deploy/terraform.
#
# ONE RUNNER PER SERVER, deliberately. The E2E harness is not multi-tenant:
# compose-runner.sh creates a fixed-name docker network `tessellation_common` on
# a fixed subnet (NET_PREFIX, default 172.32.0.0/24) with fixed container names
# (gl0-0, gl1-0, ...) and fixed host ports (9000-9412). Two concurrent jobs on
# one host collide on all four. Packing more runners per box would require a
# Docker daemon per runner in its own network namespace (privileged DinD) — see
# the README for that trade-off.
#
# CONSEQUENCE: concurrency == runner_count. The 9-job E2E matrix runs in
# ceil(9 / runner_count) waves, so runner_count is a direct
# cost-vs-PR-feedback-time dial. See README for the table.
# ---------------------------------------------------------------------------

# SSH keys are PROJECT-GLOBAL in Hetzner and their fingerprints must be unique,
# so we reference the keys already registered in the project by name rather than
# uploading copies (which fails with a 409 uniqueness_error the moment any team
# member's key is already there — and in a shared project they all are).
#
# Looking them up also gives us the public key material, which cloud-init needs
# to populate the `admin` user's authorized_keys — Hetzner itself only injects
# these for `root`.
data "hcloud_ssh_key" "team" {
  for_each = toset(var.ssh_key_names)
  name     = each.value
}

resource "hcloud_firewall" "runner" {
  name = "ci-runners-fixed"

  # SSH — team/VPN only. Runners need NO inbound anything else: they dial out to
  # GitHub to collect jobs, and the E2E cluster's 9000-9412 ports are bound
  # inside the box and only ever reached from the test harness on localhost.
  dynamic "rule" {
    for_each = length(var.allowed_ssh_cidrs) > 0 ? [1] : []
    content {
      direction  = "in"
      protocol   = "tcp"
      port       = "22"
      source_ips = var.allowed_ssh_cidrs
    }
  }

  # Outbound: GitHub (job polling, artifact download), Docker Hub, apt, Maven.
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
    variant    = "fixed"
    managed_by = "terraform"
  }
}

resource "hcloud_server" "runner" {
  count = var.runner_count

  name         = "ci-runner-${count.index + 1}"
  server_type  = var.runner_server_type
  image        = "ubuntu-24.04"
  location     = var.location
  ssh_keys     = [for k in data.hcloud_ssh_key.team : k.id]
  firewall_ids = [hcloud_firewall.runner.id]

  user_data = templatefile("${path.module}/templates/runner-init.tpl", {
    hostname = "ci-runner-${count.index + 1}"
    ssh_keys = [for k in data.hcloud_ssh_key.team : k.public_key]
  })

  labels = {
    component  = "ci-runners"
    variant    = "fixed"
    role       = "runner"
    index      = tostring(count.index + 1)
    managed_by = "terraform"
  }

  # Matches deploy/terraform: post-boot cloud-init / firewall drift shouldn't
  # force a rebuild of a box holding a registered runner identity.
  lifecycle {
    ignore_changes = [user_data, firewall_ids]
  }
}
