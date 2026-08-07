variable "hcloud_token" {
  description = "Hetzner Cloud API token"
  type        = string
  sensitive   = true
}

variable "environment" {
  description = "Environment name"
  type        = string
  default     = "testnet"

  validation {
    condition     = contains(["dev", "nightly", "testnet", "integrationnet", "mainnet"], var.environment)
    error_message = "Environment must be: dev, nightly, testnet, integrationnet, or mainnet."
  }
}

variable "location" {
  description = <<-EOT
    Hetzner Cloud datacenter location. Testnet currently lives in AWS us-west-1;
    'hil' (Hillsboro, OR) is the closest US-West option, 'ash' (Ashburn, VA) the
    US-East one, 'hel1'/'fsn1'/'nbg1' the EU options. VERIFY the chosen location
    offers the dedicated CCX line before using a CCX node_server_type. Set EITHER
    location OR datacenter (not both); leave this "" to pin a datacenter instead.
  EOT
  type        = string
  default     = ""
}

variable "datacenter" {
  description = "Hetzner Cloud datacenter to pin (e.g. 'hel1-dc2'). Mutually exclusive with location; leave \"\" to use location instead."
  type        = string
  default     = ""
}

variable "node_count" {
  description = "Number of source nodes (all run-validator; node[0] is the L0 anchor peer). There is no genesis role on testnet."
  type        = number
  default     = 3
}

variable "node_server_type" {
  description = <<-EOT
    Server type for source nodes. Each runs ~18 GB JVM heap (GL0 10g + GL1 8g)
    + OS + page cache, so a 16 GB box like cpx42 is too small. CCX33 (dedicated,
    8 vCPU / 32 GB) recommended for stable consensus timing; cpx62 (shared new-gen,
    16 vCPU / 32 GB) is the budget alternative. hel1 ships new-gen CPX only (cpx*2),
    not the old cpx11-51.
  EOT
  type        = string
  default     = "ccx33"
}

variable "streaming_server_type" {
  description = "Server type for the snapshot-streaming node (16 GB heap + co-located self-hosted Postgres). hel1 offers new-gen CPX only; cpx52 (24 GB) leaves room for heap + Postgres, cpx42 (16 GB) is tight."
  type        = string
  default     = "cpx42"
}

variable "monitoring_server_type" {
  description = "Server type for the dedicated monitoring node (Prometheus + Grafana). cpx22 is enough; cpx32 if also running Vector log-shipping."
  type        = string
  default     = "cpx22"
}

variable "data_volume_size" {
  description = "Size (GB) of the per-node state volume mounted at /opt/tessellation. Source nodes hold ~300+ GB and growing — size with headroom."
  type        = number
  default     = 400
}

variable "deploy_ssh_public_key" {
  description = "SSH public key for CI/CD deployment access"
  type        = string
}

variable "team_ssh_keys" {
  description = "List of SSH public keys for team access (add the keys that today reach the AWS testnet nodes)"
  type        = list(string)
  default     = []
}

variable "allowed_ssh_cidrs" {
  description = "CIDR blocks allowed SSH + CLI (9002/9012) access. SET TO TEAM/VPN CIDRs — do not leave 0.0.0.0/0 on a public testnet."
  type        = list(string)
  default     = ["0.0.0.0/0", "::/0"]
}

variable "admin_cidrs" {
  description = "Admin/office CIDRs allowed to reach Grafana (3000), Prometheus (9090) and ClickHouse (8123) on the monitoring node. Do NOT commit real org IPs to this repo — set them via an untracked *.auto.tfvars."
  type        = list(string)
  default     = ["0.0.0.0/0", "::/0"]
}
