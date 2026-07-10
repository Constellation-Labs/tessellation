variable "hcloud_token" {
  description = "Hetzner Cloud API token"
  type        = string
  sensitive   = true
}

variable "environment" {
  description = "Environment name"
  type        = string
  default     = "nightly"

  validation {
    condition     = contains(["nightly"], var.environment)
    error_message = "Environment must be: nightly."
  }
}

variable "location" {
  description = "Hetzner Cloud datacenter location (fsn1, nbg1, hel1)"
  type        = string
  default     = "hel1"
}

variable "network_cidr" {
  description = "CIDR block for the Hetzner Cloud Network"
  type        = string
  default     = "10.0.0.0/16"
}

variable "subnet_cidr" {
  description = "CIDR block for the subnet within the Cloud Network"
  type        = string
  default     = "10.0.1.0/24"
}

variable "node_server_type" {
  description = "Hetzner server type for node machines (genesis + validators)"
  type        = string
  default     = "cpx41" # 8 AMD vCPU, 16 GB RAM
}

variable "streaming_server_type" {
  description = "Hetzner server type for the snapshot streaming machine"
  type        = string
  default     = "cpx21" # 3 AMD vCPU, 4 GB RAM
}

variable "deploy_ssh_public_key" {
  description = "SSH public key for CI/CD deployment access"
  type        = string
}

variable "team_ssh_keys" {
  description = "List of SSH public keys for team access"
  type        = list(string)
  default     = []
}

variable "allowed_ssh_cidrs" {
  description = "CIDR blocks allowed SSH access"
  type        = list(string)
  default     = ["0.0.0.0/0", "::/0"]
}

variable "allowed_api_cidrs" {
  description = "CIDR blocks allowed to access tessellation API ports (in addition to private network)"
  type        = list(string)
  default     = ["0.0.0.0/0", "::/0"]
}
