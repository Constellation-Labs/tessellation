output "genesis_public_ip" {
  description = "Public IP of the genesis node (machine 1)"
  value       = module.cluster.genesis_public_ip
}

output "genesis_private_ip" {
  description = "Private IP of the genesis node (machine 1)"
  value       = module.cluster.genesis_private_ip
}

output "validator_public_ips" {
  description = "Public IPs of the validator nodes (machines 2-3)"
  value       = module.cluster.validator_public_ips
}

output "validator_private_ips" {
  description = "Private IPs of the validator nodes (machines 2-3)"
  value       = module.cluster.validator_private_ips
}

output "streaming_public_ip" {
  description = "Public IP of the snapshot streaming machine (machine 4)"
  value       = module.cluster.streaming_public_ip
}

output "streaming_private_ip" {
  description = "Private IP of the snapshot streaming machine (machine 4)"
  value       = module.cluster.streaming_private_ip
}

output "all_public_ips" {
  description = "All public IPs in order: genesis, validator-1, validator-2, streaming"
  value       = module.cluster.all_public_ips
}

output "all_private_ips" {
  description = "All private IPs in order: genesis, validator-1, validator-2, streaming"
  value       = module.cluster.all_private_ips
}

output "network_id" {
  description = "Hetzner Cloud Network ID"
  value       = hcloud_network.nightly.id
}
