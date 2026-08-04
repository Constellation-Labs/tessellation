# Tessellation E2E CI Runners (Hetzner Cloud)

Ephemeral self-hosted GitHub Actions runners for the E2E matrix in
[`.github/workflows/e2e-just-test.yml`](../../.github/workflows/e2e-just-test.yml).
Replaces the GitHub-hosted `Ubuntu-22-64-core` larger runner.

One Hetzner Cloud server per queued E2E job. The server registers as an
**ephemeral** runner, runs exactly one job, powers itself off, and is deleted.

Autoscaler: [testflows/TestFlows-GitHub-Hetzner-Runners](https://github.com/testflows/TestFlows-GitHub-Hetzner-Runners)
(also used by Altinity for ClickHouse CI).

## Why one server per job

The E2E harness is **not multi-tenant**. `docker/bin/compose-runner.sh:174` creates
a fixed-name docker network `tessellation_common` on a fixed subnet (`NET_PREFIX`,
default `172.32.0.0/24`), with fixed container names (`gl0-0`, `gl1-0`, …) and
fixed host ports (9000–9412). Two concurrent jobs on one host collide on all four.

Packing several runners onto one big box would therefore require a Docker daemon
per runner in its own network namespace (privileged DinD). A server per job gets
the same isolation for free — **no DinD, and no changes to the test harness.**

It also avoids deliberately co-tenanting a suite that is already timing-fragile:
`docker/docker-compose.test.yaml` carries ~40 lines documenting how multi-JVM CPU
contention produces multi-second GC pauses, spurious chronic-non-signer
classification, and the "wedge profile" fork-recovery flake.

## Cost model

Baseline: 9 E2E jobs per run on `Ubuntu-22-64-core` at **$0.162/min** ≈ **$2,000/mo**
(the 10th group, `snapshot-streaming`, already runs on a GitHub-hosted
`ubuntu-22.04` runner and is unchanged).

Hetzner `hel1` prices below are from the Cloud API for this account. Monthly
figures assume ~540 job-servers/mo (9 jobs × ~60 runs) at **~1.22 billed hours
each** — Hetzner rounds every partial hour **up** to a full calendar hour, and
measured jobs run 3m41s–18m29s. Plus the €22.99/mo controller.

| Server type | Cores / RAM | €/h | Est. €/mo | Saving |
|---|---|---|---|---|
| `ccx33` | 8c / 32 GB | 0.2612 | ~195 | **REJECTED** — OOM + 503, see [../README.md](../README.md#sizing-applies-to-both--measured) |
| **`ccx43`** | **16c / 64 GB** | **0.5216** | **~353** | **~81%** |
| `ccx53` | 32c / 128 GB | 1.0088 | ~683 | ~63% |

`ccx43` is the default and the measured floor. Sizing is the dominant cost
variable, but it is now settled by data rather than estimation — see
[Right-sizing](#right-sizing).

Why the duty-cycle argument favours ephemeral over always-on hardware: real
demand is ~198 job-hours/month. An always-on 3-box fleet supplies 2,190
host-hours/month to serve it — ~9% utilization. Hetzner bare metal has better
$/core (AX162: 48 cores for €199/mo) but that 4× advantage doesn't overcome an
11× utilization gap.

## Prerequisites

1. **A dedicated Hetzner Cloud project.**
   The autoscaler enumerates servers in whatever project its token belongs to and
   deletes any it finds powered off. It must **never** share a project with the
   `testnet-*` / `nightly*` chain nodes. Upstream requires one project per
   repository regardless. Projects cannot be created via API — use the Cloud
   Console, then generate a **Read & Write** API token inside it.
   *Bonus: per-repo CI cost lands on its own invoice line.*

2. **Raise the project server limit.** New Hetzner projects cap at ~10 servers.
   `max_runners: 12` plus the controller exceeds that. Request an increase
   (support ticket) to ≥30 before relying on full matrix width, or scale-up
   silently caps and jobs queue.

3. **A GitHub classic PAT with `repo` scope.** Fine-grained tokens are **not
   supported** by the autoscaler. The token registers repo-level runners.

4. **Repo-level only.** Organization/group runners are not supported upstream.

## Deploy

```sh
cd deploy/ci-runners/autoscaled/terraform

export TF_VAR_hcloud_token=...                      # the CI project token, NOT testnet's
export TF_VAR_team_ssh_keys='["ssh-ed25519 AAAA..."]'
export TF_VAR_allowed_ssh_cidrs='["203.0.113.0/24"]'  # fail-closed: empty = no SSH

terraform init
terraform apply
```

Then install the autoscaler on the controller:

```sh
export HETZNER_TOKEN=...                            # same CI project token
export GITHUB_TOKEN=...                             # classic PAT, repo scope
export GITHUB_REPOSITORY=Constellation-Labs/tessellation

deploy/ci-runners/autoscaled/bootstrap-controller.sh "$(terraform -chdir=deploy/ci-runners/autoscaled/terraform output -raw controller_public_ip)"
```

`bootstrap-controller.sh` is idempotent — re-run it to roll out a `config.yaml`
change or upgrade the package. Tokens travel as env vars over the SSH channel,
never in argv.

## Operations

**Dashboard + metrics** (loopback-bound on the controller):

```sh
ssh -L 8090:127.0.0.1:8090 -L 9099:127.0.0.1:9099 admin@<controller-ip>
# then open http://127.0.0.1:8090
```

**Logs:**

```sh
ssh admin@<controller-ip> 'journalctl -u github-hetzner-runners -f'
ssh admin@<controller-ip> 'tail -f /var/log/github-hetzner-runners/service.log'
```

**Debug a wedged job** — every runner carries the controller's debug key, so you
can SSH into a live E2E cluster before it's reaped:

```sh
# From the controller (the debug key is root:runners 0640, so sudo is required):
ssh admin@<controller-ip>
hcloud server list                                  # or: check the dashboard
sudo ssh -i /etc/github-hetzner-runners/runner_key ubuntu@<runner-ip>
docker ps && docker logs gl0-0
```

Runners are reaped within ~60 s of the job ending, so grab logs promptly — or rely
on the workflow's own `Upload logs` step, which persists per-node logs as
artifacts for 7 days regardless.

**Resize the fleet** — edit `meta_label` / `default_server_type` in `config.yaml`
and re-run `bootstrap-controller.sh`. Fleet shape is deliberately controller-side
config, not workflow YAML: resizing needs no repo PR and doesn't disturb
in-flight runs.

**Rollback to GitHub-hosted runners** — set the repository variable
`E2E_RUNNER_LABEL` to `Ubuntu-22-64-core`. Takes effect on the next job, no PR,
no rerun. Unset it to return to the Hetzner fleet.

## Right-sizing

A job runs up to **15** containers (3 `gl0` + 3 `gl1` + 1 `ml0` + 3 `cl1` +
3 `dl1` + support), each JVM defaulting to `-Xmx8g` with
`-XX:ActiveProcessorCount=8`.

Measured on the fork 2026-07-31 → 08-03, all 11 jobs green: **peak 26.7 GB RAM,
peak load 15.42, 7% disk**, jobs 3m41s–18m29s. `ccx43` is the measured floor — see
[../README.md](../README.md#sizing-applies-to-both--measured) for why `ccx33` is
rejected.

To re-measure after a topology or heap change:

```sh
ssh -i /etc/github-hetzner-runners/runner_key ubuntu@<runner-ip>
docker stats --no-stream
free -m; nproc; uptime          # peak RSS, and load vs core count
```

- **Memory is the binding constraint.** These boxes have no swap, so exceeding it
  makes the kernel kill the runner agent, not fail a test — the job reports
  `cancelled` with no useful log.
- **Load above 1.0/core is survivable but not free.** It first shows up as HTTP
  503s from GL0's `/global-snapshots/latest/combined`, which reads like a flaky
  test rather than starvation. If that appears, step **up** rather than tuning
  timeouts; the workflow already sets `CL_DECLARATION_TIMEOUT`,
  `CL_RE_STALL_TIMEOUT` and friends generously for slow runners.

## Known risks

- **The controller is a single point of failure for all E2E CI.** If it dies, no
  runners are created and jobs queue until the workflow timeout. `Restart=always`
  covers crashes; a dead *box* needs `terraform apply` + bootstrap. The
  `E2E_RUNNER_LABEL` rollback is the fast mitigation.
- **A dead controller also stops reaping.** Powered-off servers keep billing
  (Hetzner charges for existence, not uptime). Check `hcloud server list` in the
  CI project after any controller outage.
- **Capacity.** CCX stock in a single location can be tight during bursts;
  scale-up failures surface on the dashboard. Adding a fallback location to the
  `in-*` meta label is the mitigation.
- **`recycle: false`** by choice — pooled powered-off servers bill while idle, and
  a guaranteed-fresh box per job is the isolation we're paying for. Our jobs run
  20–45 min, so a ~60 s boot is noise. Flip it on only if boot latency dominates.

## Not managed here

- **The ephemeral runner servers.** Created and destroyed by the autoscaler at job
  granularity, deliberately *not* in Terraform state — they're cattle with a
  ~30-minute lifespan and would show as permanent drift. `terraform plan` showing
  no runner servers is correct even while nine are running.
- **The hypergraph cluster** (`testnet-*`, `nightly*`) — separate Hetzner project,
  separate Terraform stack at [`deploy/terraform`](../terraform), separate state.
- **The `build` job** and the `snapshot-streaming` E2E group — both remain on
  GitHub-hosted `ubuntu-22.04` runners, which are cheap and unaffected.
