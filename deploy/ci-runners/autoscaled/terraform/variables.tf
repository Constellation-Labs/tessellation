variable "hcloud_token" {
  description = <<-EOT
    Hetzner Cloud API token for the DEDICATED CI project. Must NOT be the
    testnet/nightly project token: github-hetzner-runners lists and deletes
    servers in the project it is given, and upstream requires one project per
    repository.
  EOT
  type        = string
  sensitive   = true
}

variable "location" {
  description = <<-EOT
    Hetzner Cloud location for the controller. Keep it the same as the runners'
    location (config.yaml `in-*` meta label) so controller->runner SSH for
    debugging stays intra-datacenter. hel1 (Helsinki) matches the existing
    tessellation clusters. VERIFY the chosen location stocks the CCX line.
  EOT
  type        = string
  default     = "hel1"
}

variable "controller_server_type" {
  description = <<-EOT
    Controller server type. The autoscaler is a light Python process (polls two
    APIs, shells out to SSH); cpx22 (2 vCPU / 4 GB, EUR 19.49/mo) is ample. This
    is the only always-on cost in the stack.
  EOT
  type        = string
  default     = "cpx22"
}

variable "team_ssh_keys" {
  description = "SSH public keys granted `admin` on the controller. At least one is required or the box is unreachable."
  type        = list(string)
  default     = []

  validation {
    condition     = length(var.team_ssh_keys) > 0
    error_message = "Provide at least one team SSH public key, or the controller cannot be reached."
  }
}

variable "allowed_ssh_cidrs" {
  description = <<-EOT
    CIDRs allowed to SSH the controller. FAIL-CLOSED: the default is empty, so a
    verbatim apply exposes no inbound port at all. Supply team/VPN CIDRs via
    -var, TF_VAR_allowed_ssh_cidrs, or a gitignored *.auto.tfvars. Do not commit
    real org IPs.
  EOT
  type        = list(string)
  default     = []
}
