# Nightly Build Infrastructure

Terraform configuration for provisioning a 4-machine Hetzner Cloud VPS cluster used as a persistent nightly build environment for Tessellation.

## Why

There is no long-lived environment where the full hypergraph + metagraph stack runs continuously. The only automated deployment happens ephemerally inside GitHub Actions runners during CI. This cluster provides a place to catch issues that only manifest after hours or days of runtime — consensus halts, memory leaks, state growth, gossip degradation — with proper monitoring and log retention.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                  Hetzner Cloud Network (10.0.1.0/24)            │
│                                                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │  Machine 1   │  │  Machine 2   │  │  Machine 3   │          │
│  │  Genesis     │  │  Validator 1  │  │  Validator 2  │          │
│  │  10.0.1.11   │  │  10.0.1.12   │  │  10.0.1.13   │          │
│  │              │  │              │  │              │          │
│  │  GL0-0       │  │  GL0-1       │  │  GL0-2       │          │
│  │  GL1-0       │  │  GL1-2       │  │  GL1-1       │          │
│  │  ML0-0       │  │  ML0-1       │  │  ML0-2       │          │
│  │  CL1-0       │  │  CL1-1       │  │  CL1-2       │          │
│  │              │  │              │  │              │          │
│  │  Grafana     │  │              │  │              │          │
│  │  Prometheus  │  │              │  │              │          │
│  └──────────────┘  └──────────────┘  └──────────────┘          │
│                                                                 │
│  ┌──────────────┐                                               │
│  │  Machine 4   │                                               │
│  │  Streaming   │                                               │
│  │  10.0.1.14   │                                               │
│  └──────────────┘                                               │
└─────────────────────────────────────────────────────────────────┘
```

| Machine | Role | Server Type | vCPU | RAM | ~Cost/month |
|---------|------|-------------|------|-----|-------------|
| 1 | Genesis node + monitoring | CPX41 | 8 AMD | 16 GB | €15 |
| 2 | Validator 1 | CPX41 | 8 AMD | 16 GB | €15 |
| 3 | Validator 2 | CPX41 | 8 AMD | 16 GB | €15 |
| 4 | Snapshot streaming | CPX21 | 3 AMD | 4 GB | €5 |
| | **Total** | | | | **~€50** |

All instances run Ubuntu 22.04 with Docker CE and the Docker Compose plugin pre-installed via cloud-init. Inter-node communication uses Hetzner Cloud Network private IPs. Tessellation services run with `--network host` so containers bind directly to the host interface — no Docker overlay networking required.

## Directory Structure

```
nightly/terraform/
├── main.tf                        # Provider, S3 backend, network, firewall, SSH keys, module call
├── variables.tf                   # Root-level input variables
├── outputs.tf                     # Public and private IPs of all machines
├── .gitignore                     # Terraform state/cache exclusions
│
├── components/
│   └── cluster/
│       ├── main.tf                # hcloud_server resources (genesis, 2x validator, streaming)
│       ├── variables.tf           # Module input variables
│       └── outputs.tf             # Per-machine IP and server ID outputs
│
├── environments/
│   └── nightly.tfvars             # Environment-specific values (server types, location, CIDRs)
│
├── scripts/
│   └── deploy-nightly.sh          # CLI wrapper for terraform init/plan/apply/destroy
│
└── templates/
    └── node-init.tpl              # Cloud-init template: installs Docker, creates working dirs
```

### Key files

**`main.tf`** — Sets up the Hetzner Cloud provider, S3 state backend (shared `ded-terraform` bucket, keyed under `nightly/`), the private Cloud Network + subnet, firewall rules for SSH/tessellation ports/Grafana/Prometheus/node-exporter, SSH key resources, and calls the `cluster` module.

**`components/cluster/main.tf`** — Provisions the 4 servers with static private IPs on the Cloud Network: genesis at `10.0.1.11`, validators at `10.0.1.12`–`10.0.1.13`, streaming at `10.0.1.14`. Each server gets the `node-init.tpl` cloud-init script with its hostname and role.

**`templates/node-init.tpl`** — Cloud-init user data script that runs on first boot. Installs Docker CE + compose plugin from Docker's official APT repository, injects team SSH keys, and creates `/opt/tessellation/{data,logs,config}` working directories.

**`environments/nightly.tfvars`** — Defaults for the nightly environment: `hel1` (Helsinki) datacenter, `cpx41` for nodes, `cpx21` for streaming. SSH and API CIDR restrictions are set to `0.0.0.0/0` as placeholders — tighten these before production use.

**`scripts/deploy-nightly.sh`** — Shell wrapper that validates prerequisites (Terraform, Hetzner token, AWS CLI for state backend), creates the S3 state bucket if needed, initializes the backend, selects the `nightly` workspace, and runs plan/apply. Adapted from the DED `deploy-terraform.sh`.

## Prerequisites

- [Terraform](https://developer.hashicorp.com/terraform/install) >= 1.0
- [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html) configured with access to the `tessellation-nightly` S3 bucket (used for state storage; the deploy script creates this bucket automatically if it doesn't exist)
- A [Hetzner Cloud](https://console.hetzner.cloud/) project with an API token
- An SSH key pair for CI/CD deployment access

## Usage

### 1. Set environment variables

```bash
# Required
export HCLOUD_TOKEN='your-hetzner-cloud-api-token'
export TF_VAR_deploy_ssh_public_key='ssh-ed25519 AAAA... deploy@ci'

# Optional: team SSH keys for manual access
export TF_VAR_team_ssh_keys='["ssh-ed25519 AAAA... alice", "ssh-ed25519 AAAA... bob"]'
```

The Hetzner token can also be passed as `TF_VAR_hcloud_token`. Both forms are checked by the deploy script.

### 2. Preview changes

```bash
cd nightly/terraform
./scripts/deploy-nightly.sh plan
```

### 3. Deploy

```bash
./scripts/deploy-nightly.sh deploy
```

This will show the plan and prompt for confirmation before applying.

### 4. Get outputs

```bash
# Human-readable
./scripts/deploy-nightly.sh output

# JSON (for scripts and CI)
./scripts/deploy-nightly.sh output-json
```

Outputs include public and private IPs for all 4 machines, which are consumed by the GitHub Actions deployment workflows and E2E test scripts.

### 5. Destroy

```bash
./scripts/deploy-nightly.sh destroy
```

## Networking

### Firewall rules

| Port | Protocol | Source | Purpose |
|------|----------|--------|---------|
| 22 | TCP | `allowed_ssh_cidrs` | SSH access |
| 9000–9099 | TCP | Private subnet + `allowed_api_cidrs` | Tessellation services (GL0, GL1, ML0, CL1, DL1) |
| 3000 | TCP | `allowed_ssh_cidrs` | Grafana dashboard |
| 9090 | TCP | Private subnet | Prometheus scraping |
| 9100 | TCP | Private subnet | Node exporter metrics |
| All | TCP/UDP/ICMP | `0.0.0.0/0` (outbound) | Docker image pulls, ClickHouse, etc. |

### Private IPs

The Cloud Network subnet `10.0.1.0/24` assigns static IPs to each machine:

| Machine | Private IP | Used in `.env` as |
|---------|-----------|-------------------|
| Genesis | `10.0.1.11` | `CL_EXTERNAL_IP`, `CL_DOCKER_GL0_PEER_HTTP_HOST`, `CL_DOCKER_GL0_JOIN_IP` targets |
| Validator 1 | `10.0.1.12` | `CL_EXTERNAL_IP` |
| Validator 2 | `10.0.1.13` | `CL_EXTERNAL_IP` |
| Streaming | `10.0.1.14` | Snapshot streaming target |

These IPs are used by the docker compose `.env` files on each machine. Validators point their `JOIN_IP` and `PEER_HTTP_HOST` variables to `10.0.1.11` (genesis).

## State management

Terraform state is stored in S3 at `s3://tessellation-nightly/nightly/nightly/terraform.tfstate` in a dedicated bucket separate from other project infrastructure. The deploy script creates this bucket automatically with versioning enabled if it doesn't already exist. The `nightly` Terraform workspace provides isolation from other environments.

## Customization

### Server sizing

To change instance types, edit `environments/nightly.tfvars`:

```hcl
node_server_type      = "cpx51"   # 16 vCPU, 32 GB — if 16 GB is insufficient
streaming_server_type = "cpx31"   # 4 vCPU, 8 GB — if streaming needs more resources
```

### Datacenter location

Available Hetzner locations: `hel1` (Helsinki), `fsn1` (Falkenstein), `nbg1` (Nuremberg). All are EU. Default is `hel1`.

### Restricting access

Replace the placeholder CIDRs in `nightly.tfvars` with actual team/VPN IP ranges:

```hcl
allowed_ssh_cidrs = ["203.0.113.0/24"]   # Team VPN
allowed_api_cidrs = ["203.0.113.0/24"]   # Team VPN
```
