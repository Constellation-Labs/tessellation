output "genesis_public_ip" {
  description = "Public IP of the genesis node"
  value       = hcloud_server.genesis.ipv4_address
}

output "genesis_private_ip" {
  description = "Private IP of the genesis node"
  value       = hcloud_server_network.genesis.ip
}

output "validator_public_ips" {
  description = "Public IPs of the validator nodes"
  value       = hcloud_server.validators[*].ipv4_address
}

output "validator_private_ips" {
  description = "Private IPs of the validator nodes"
  value       = hcloud_server_network.validators[*].ip
}

output "streaming_public_ip" {
  description = "Public IP of the snapshot streaming machine"
  value       = hcloud_server.streaming.ipv4_address
}

output "streaming_private_ip" {
  description = "Private IP of the snapshot streaming machine"
  value       = hcloud_server_network.streaming.ip
}

output "all_public_ips" {
  description = "All public IPs: genesis, validator-1, validator-2, streaming"
  value = concat(
    [hcloud_server.genesis.ipv4_address],
    hcloud_server.validators[*].ipv4_address,
    [hcloud_server.streaming.ipv4_address]
  )
}

output "all_private_ips" {
  description = "All private IPs: genesis, validator-1, validator-2, streaming"
  value = concat(
    [hcloud_server_network.genesis.ip],
    hcloud_server_network.validators[*].ip,
    [hcloud_server_network.streaming.ip]
  )
}

output "genesis_server_id" {
  description = "Server ID of the genesis node"
  value       = hcloud_server.genesis.id
}

output "validator_server_ids" {
  description = "Server IDs of the validator nodes"
  value       = hcloud_server.validators[*].id
}

output "streaming_server_id" {
  description = "Server ID of the snapshot streaming machine"
  value       = hcloud_server.streaming.id
}
