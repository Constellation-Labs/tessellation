output "controller_public_ip" {
  description = "Public IP of the autoscaler controller. Feed this to bootstrap-controller.sh."
  value       = hcloud_server.controller.ipv4_address
}

output "controller_id" {
  description = "Hetzner server ID of the controller."
  value       = hcloud_server.controller.id
}

output "bootstrap_command" {
  description = "Next step after apply — installs/updates the autoscaler on the controller."
  value       = "deploy/ci-runners/autoscaled/bootstrap-controller.sh ${hcloud_server.controller.ipv4_address}"
}

output "tunnel_command" {
  description = "Open the autoscaler dashboard (8090) and Prometheus metrics (9099), both loopback-bound on the controller."
  value       = "ssh -L 8090:127.0.0.1:8090 -L 9099:127.0.0.1:9099 admin@${hcloud_server.controller.ipv4_address}"
}
