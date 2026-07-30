output "node_public_ips" {
  description = "Public IPs of the source nodes, in order"
  value       = hcloud_server.node[*].ipv4_address
}

output "l0_anchor_public_ip" {
  description = "Public IP of the L0 anchor (node[0])"
  value       = hcloud_server.node[0].ipv4_address
}

output "streaming_public_ip" {
  description = "Public IP of the snapshot-streaming node"
  value       = hcloud_server.streaming.ipv4_address
}

output "monitoring_public_ip" {
  description = "Public IP of the monitoring node"
  value       = hcloud_server.monitoring.ipv4_address
}

output "all_public_ips" {
  description = "All public IPs in order: nodes..., streaming, monitoring"
  value = concat(
    hcloud_server.node[*].ipv4_address,
    [hcloud_server.streaming.ipv4_address],
    [hcloud_server.monitoring.ipv4_address],
  )
}

output "all_server_ids" {
  description = "All server IDs in order: nodes..., streaming, monitoring"
  value = concat(
    hcloud_server.node[*].id,
    [hcloud_server.streaming.id],
    [hcloud_server.monitoring.id],
  )
}
