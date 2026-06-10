# Tessellation Cluster Infrastructure (Hetzner)

Terraform for the Constellation Tessellation hypergraph cluster on Hetzner Cloud.
Provisions a live-chain cluster — **public-IP** addressing, 5 machines, dedicated
nodes, and durable state volumes — with per-environment profiles selected by
`var.environment`.

> Rationale and the full design write-up live in the internal-plans repo under
> `ai/reviewed/plans/testnet-hetzner-migration/` (`infrastructure.md`,
> `terraform-changes.md`).

## Topology

| Machine | Role | Count | Default type | State |
|---------|------|-------|--------------|-------|
| node-1..3 | source node (GL0 `l0` + GL1 `l1`, `run-validator`) | 3 | `ccx33` | Hetzner Volume @ `/opt/tessellation` |
| streaming | snapshot-streaming (+ self-hosted Postgres) | 1 | `cpx42` | local disk |
| monitoring | Prometheus + Grafana | 1 | `cpx22` | local disk |

`node[0]` is the **L0 anchor** — every L1 and the streaming node point their
`--l0-peer-host` / `node.l0Peers` at its public IP. There is **no genesis role**:
on live networks the chain is migrated, never re-genesised.

## Design notes

- **Public-IP addressing.** Every node is addressed by public IP, so there is no
  private Cloud Network and no static `cidrhost()` IPs; node IPs come from
  `terraform output`.
- **Dedicated monitoring host.** Prometheus + Grafana run on their own machine,
  separate from streaming.
- **Node sizing for the heap.** Source nodes default to `ccx33` (dedicated, 32 GB)
  to cover the ~18 GB JVM heap; `cpx51` is the shared-vCPU alternative.
- **State volumes.** A `hcloud_volume` (~400 GB) per source node, mounted at
  `/opt/tessellation`, so chain state survives server recreation.
- **Two firewalls:**
  - `base` (attached at server creation): SSH + GL0/GL1 public API/p2p
    world-facing, CLI (9002/9012) admin-only, Grafana scoped.
  - `scrape` (attached via `hcloud_firewall_attachment`): JMX (9100, no auth) and
    the `process`/`nftables`/`network-process` exporters (9436/9437/9435)
    source-scoped to the cluster's own public IPs, Prometheus (9090) to the
    monitoring node. Split out because its sources are the servers' computed IPs —
    putting them on the servers' `firewall_ids` would create a cycle.
- **Environment-neutral state backend.** Workspace prefix `tessellation-cluster`,
  one `terraform workspace` per environment (state at
  `tessellation-cluster/<env>/terraform.tfstate`). Backend config can't take
  variables, so the environment is selected via the workspace, not interpolation.

## Environments

`var.environment` accepts `dev`, `nightly`, `testnet`, `integrationnet`, `mainnet`
and prefixes every resource name (e.g. `testnet-node-1`, `mainnet-base`). Use one
workspace + one `environments/<env>.tfvars` per environment. `testnet.tfvars` and
`nightly.tfvars` ship today; copy one for the others.

## Usage

```sh
cd deploy/terraform
terraform init
terraform workspace new testnet   # one workspace per environment; or: workspace select testnet

# Provide the token + SSH keys out of band (never commit secrets):
export TF_VAR_hcloud_token=...
export TF_VAR_deploy_ssh_public_key="ssh-ed25519 AAAA..."
export TF_VAR_team_ssh_keys='["ssh-ed25519 AAAA...","ssh-ed25519 BBBB..."]'

terraform plan  -var-file=environments/testnet.tfvars
terraform apply -var-file=environments/testnet.tfvars

# Public IPs for the IP-rewrite map / Prometheus targets:
terraform output -json all_public_ips
```

Set real `allowed_ssh_cidrs` / `admin_cidrs` (the latter scopes Grafana,
Prometheus and ClickHouse) at apply time — the committed defaults are empty
(fail-closed), so a verbatim `apply` grants no SSH or admin access until you
provide CIDRs via `-var`, `TF_VAR_*`, or a secrets-managed `*.auto.tfvars`.

## Not managed here (out of band)

- **Hetzner Object Storage** bucket for snapshot archival — the `hcloud` provider
  has no object-storage resource; create it in the console / via the S3 API.
- **State seeding, `key.p12` placement, IP rewrites, Postgres dump/restore,
  rollback-hash capture, container start** — these are the cutover/deploy steps
  driven by `docker/bin/*`, not Terraform. See the migration plan's `cutover.md` /
  `deployment.md`.

## Open items to confirm before apply

1. CCX (dedicated) vs CPX (shared) for source nodes.
2. CCX availability in `hil` (else `ash` / EU).
3. Whether to keep fully public or re-introduce a private network for east-west
   traffic (egress counts against the 20 TB/mo included traffic).
4. Whether to move the TF state backend off AWS S3 to Hetzner Object Storage.
