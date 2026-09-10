variable "hcloud_token" {
  description = <<-EOT
    Hetzner Cloud API token. Unlike the autoscaled variant, this stack is SAFE to
    point at the existing tessellation project: nothing here enumerates or
    deletes servers it does not own, and its Terraform state is separate from
    deploy/terraform. A dedicated project is still nicer for cost attribution.
  EOT
  type        = string
  sensitive   = true
}

variable "location" {
  description = "Hetzner Cloud location. hel1 (Helsinki) matches the existing tessellation clusters."
  type        = string
  default     = "hel1"
}

variable "runner_count" {
  description = <<-EOT
    Number of always-on runner servers. ONE RUNNER PER SERVER, so this IS the E2E
    concurrency limit: the 9-job matrix runs in ceil(9 / runner_count) waves.

    This is the cost-vs-feedback-time dial. At hel1 ccx43 (EUR 325.49/mo each),
    measured against the ~$2000/mo (~EUR 1852) GitHub baseline:

      3 runners  3 waves  ~75 min PR   EUR   976/mo   47% saving   <- default
      5 runners  2 waves  ~50 min PR   EUR  1627/mo   12% saving
      9 runners  1 wave   ~25 min PR   EUR  2929/mo   58% MORE EXPENSIVE

    Per-core price is flat across CCX sizes, so there is no economy of scale:
    always-on cloud only saves money by accepting queueing. If you need full
    concurrency AND savings, use the autoscaled variant (~76%) or bare metal.
  EOT
  type        = number
  default     = 3

  validation {
    condition     = var.runner_count >= 1 && var.runner_count <= 12
    error_message = "runner_count must be between 1 and 12; above 9 buys nothing (the matrix is 9 jobs)."
  }
}

variable "runner_server_type" {
  description = <<-EOT
    Server type per runner. A job runs ~13 JVM containers (3 gl0 + 3 gl1 + 1 ml0
    + 3 cl1 + 3 dl1), each defaulting to `-Xmx8g` with
    `-XX:ActiveProcessorCount=8`.

      ccx33   8c /  32 GB  EUR 162.99/mo  — 74% saving at 3 runners; 32 GB is tight, measure first
      ccx43  16c /  64 GB  EUR 325.49/mo  — default
      ccx53  32c / 128 GB  EUR 629.49/mo  — only if consensus timing flakes demand it

    The ccx43 default is INFERRED from container count and heap defaults, not
    measured. Stepping down to ccx33 takes 3 runners from EUR 976 to EUR 489/mo,
    so measure peak RSS on the first green run (see README).

    CCX (dedicated vCPU) not CPX (shared): docker-compose.test.yaml documents at
    length how shared-CPU contention produces multi-second JVM pauses and
    spurious consensus failures. Do not move to CPX to save money.
  EOT
  type        = string
  default     = "ccx43"
}

variable "ssh_key_names" {
  description = <<-EOT
    Names of SSH keys ALREADY REGISTERED in the Hetzner project, granted `admin`
    on every runner. Referenced by name rather than uploaded: Hetzner SSH keys are
    project-global with unique fingerprints, so re-uploading a key any team member
    has already added fails with a 409 uniqueness_error.

    List them with: hcloud ssh-key list

    In the shared tessellation project these are per-person
    (`roman@constellationnetwork.io`, ...) plus `testnet-deploy`. Naming the team's
    keys here gives everyone access without managing key material in Terraform.
  EOT
  type        = list(string)
  default     = []

  validation {
    condition     = length(var.ssh_key_names) > 0
    error_message = "Provide at least one existing Hetzner SSH key name (see: hcloud ssh-key list), or the runners cannot be reached."
  }
}

variable "allowed_ssh_cidrs" {
  description = <<-EOT
    CIDRs allowed to SSH the runners. FAIL-CLOSED: default empty, so a verbatim
    apply exposes no inbound port. Supply team/VPN CIDRs via -var,
    TF_VAR_allowed_ssh_cidrs, or a gitignored *.auto.tfvars. Do not commit real
    org IPs.
  EOT
  type        = list(string)
  default     = []
}
