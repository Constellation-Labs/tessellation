variable "environment" {
  description = "Environment name"
  type        = string
}

variable "location" {
  description = "Hetzner Cloud datacenter location"
  type        = string
}

variable "node_server_type" {
  description = "Server type for node machines (genesis + validators)"
  type        = string
}

variable "streaming_server_type" {
  description = "Server type for the snapshot streaming machine"
  type        = string
}

variable "network_id" {
  description = "Hetzner Cloud Network ID"
  type        = string
}

variable "subnet_cidr" {
  description = "CIDR block for the subnet"
  type        = string
}

variable "firewall_ids" {
  description = "List of firewall IDs to apply to all servers"
  type        = list(number)
}

variable "ssh_key_ids" {
  description = "List of SSH key IDs to inject into servers"
  type        = list(number)
}

variable "team_ssh_keys" {
  description = "List of SSH public keys for team access"
  type        = list(string)
  default     = []
}
