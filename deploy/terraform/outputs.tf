# Public IPs are the cluster's addressing model. Feed these into the cutover
# IP-rewrite map (.env / application.conf / prometheus.yaml) and the firewall
# scoping via `terraform output -json`.

output "node_public_ips" {
  description = "Public IPs of the 3 source nodes, in order. node_public_ips[0] is the L0 anchor."
  value       = module.cluster.node_public_ips
}

output "l0_anchor_public_ip" {
  description = "Public IP of the L0 anchor (node[0]) — every L1 and snapshot-streaming points --l0-peer-host / node.l0Peers here."
  value       = module.cluster.l0_anchor_public_ip
}

output "streaming_public_ip" {
  description = "Public IP of the snapshot-streaming node"
  value       = module.cluster.streaming_public_ip
}

output "monitoring_public_ip" {
  description = "Public IP of the monitoring node (Prometheus + Grafana)"
  value       = module.cluster.monitoring_public_ip
}

output "all_public_ips" {
  description = "All public IPs in order: node-1, node-2, node-3, streaming, monitoring"
  value       = module.cluster.all_public_ips
}

output "all_server_ids" {
  description = "All server IDs (nodes + streaming + monitoring)"
  value       = module.cluster.all_server_ids
}
