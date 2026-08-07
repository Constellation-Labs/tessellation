variable "environment" {
  description = "Environment name"
  type        = string
}

variable "location" {
  description = "Hetzner Cloud location (mutually exclusive with datacenter)"
  type        = string
}

variable "datacenter" {
  description = "Hetzner Cloud datacenter (mutually exclusive with location)"
  type        = string
}

variable "node_count" {
  description = "Number of source nodes"
  type        = number
}

variable "node_server_type" {
  description = "Server type for source nodes"
  type        = string
}

variable "streaming_server_type" {
  description = "Server type for the snapshot-streaming node"
  type        = string
}

variable "monitoring_server_type" {
  description = "Server type for the monitoring node"
  type        = string
}

variable "data_volume_size" {
  description = "Size (GB) of each per-node state volume"
  type        = number
}

variable "firewall_ids" {
  description = "Base firewall IDs applied to all servers at creation"
  type        = list(number)
}

variable "ssh_key_ids" {
  description = "List of SSH key IDs to inject into servers"
  type        = list(number)
}

variable "team_ssh_keys" {
  description = "List of SSH public keys for team access (passed to cloud-init)"
  type        = list(string)
  default     = []
}
