output "runner_public_ips" {
  description = "Public IPs of the runner servers, in order."
  value       = hcloud_server.runner[*].ipv4_address
}

output "runner_ips_csv" {
  description = "Comma-separated runner IPs — the argument form register-runners.sh takes."
  value       = join(",", hcloud_server.runner[*].ipv4_address)
}

output "concurrency" {
  description = "Concurrent E2E jobs this pool can serve (one runner per server), and the resulting wave count for the 9-job matrix."
  value       = "${var.runner_count} concurrent; ${ceil(9 / var.runner_count)} wave(s) for the 9-job E2E matrix"
}

output "estimated_monthly_eur" {
  description = "Rough always-on cost at hel1 list prices. Verify against the Hetzner console — these are hardcoded and will drift."
  value = format(
    "%.2f EUR/mo (%d x %s)",
    var.runner_count * lookup({
      ccx33 = 162.99
      ccx43 = 325.49
      ccx53 = 629.49
      ccx63 = 1006.99
    }, var.runner_server_type, 0),
    var.runner_count,
    var.runner_server_type,
  )
}

output "register_command" {
  description = "Next step after apply — installs tooling and registers each runner with GitHub."
  value       = "deploy/ci-runners/fixed/register-runners.sh ${join(",", hcloud_server.runner[*].ipv4_address)}"
}
