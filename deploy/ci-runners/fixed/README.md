# Fixed pool E2E runners (Hetzner Cloud, no autoscaler)

A fixed set of always-on Hetzner Cloud servers, each running one persistent
GitHub Actions runner registered under the label `tessellation-e2e`.

The alternative implementation of the same label is
[`../autoscaled`](../autoscaled) — see [`../README.md`](../README.md) for how to
choose.

## Why you might pick this one

- **No dedicated Hetzner project needed.** Nothing here enumerates or deletes
  servers it doesn't own, so it is safe to run inside the existing tessellation
  project alongside the `testnet-*` / `nightly*` clusters. Terraform state is
  separate from `deploy/terraform`, and every resource carries
  `component=ci-runners` labels with a distinct `ci-runner-*` name prefix.
- **No controller.** No always-on service that, if it dies, stops all CI *and*
  stops reaping billable servers.
- **Warm caches.** Docker layers and any sbt/coursier state persist between jobs,
  so there's no per-job boot or cold-cache cost.

## The honest trade-off

**Always-on cloud only saves money by accepting queueing.** Per-core price is
essentially flat across the CCX line (~€20/core/mo at every size), so there is no
economy of scale to exploit — buying full 9-way concurrency means buying 9 boxes.

One runner per server (see [below](#why-one-runner-per-server)), so
**concurrency = `runner_count`**. Measured on this fleet, the 9 E2E groups total
**~70 min of work** (see [Measured](#measured-2026-07-31--08-03)), so wall-clock
is roughly `70 / runner_count` minutes. Against the ~$2,000/mo (≈€1,852 at
~1.08 USD/EUR) GitHub baseline, at `hel1` prices:

| Runners × type | Concurrent | PR wall-clock | €/mo | Saving |
|---|---|---|---|---|
| 2× `ccx43` | 2 | ~35 min | 651 | 65% |
| **3× `ccx43`** | **3** | **~23 min** ≈ today | **976** | **47%** |
| 4× `ccx43` | 4 | ~18 min | 1,302 | 30% |
| ~~any `ccx33`~~ | — | — | — | **rejected — see below** |

Current PR feedback is ~25 min, so 3 runners roughly preserves it. If you want
bigger savings at the same speed, use [`../autoscaled`](../autoscaled) (~81%,
elastic) instead — the duty cycle is only ~9%, so paying for always-on capacity
is structurally wasteful.

## Measured (2026-07-31 → 08-03)

Full matrix validated on a single `ccx33`, then stress-tested. All 11 jobs passed
on the first full run.

| Group | Containers | Duration |
|---|---|---|
| `dag-cluster` | 7 | 3m41s |
| `rewards` | 15 | 4m01s |
| `data-with-fee` | 15 | 4m18s |
| `token-lock-replacement` | 15 | 5m16s |
| `delegated-staking` | 15 | 5m36s |
| `token-locks` | 15 | 6m27s |
| `currency` | 15 | 7m58s |
| `spend` | 15 | 14m14s |
| `allow-spends` | 15 | 18m29s |
| **total** | | **~70 min** |

### Swap: the OOM backstop

Hetzner images ship with no swap, so cloud-init adds a **16 GB swapfile with
`vm.swappiness=10`**. That is deliberately an emergency backstop, not a way to
run from disk: the kernel reclaims page cache first and only pages out anonymous
JVM heap under genuine pressure.

It changes the failure mode, not the sizing. Without swap, overshooting RAM makes
the kernel **kill a process** — and on 2026-08-03 the victim was the Actions
runner agent itself, so the unit went `failed` and every queued job hung forever.
With swap, the same overshoot makes the job **slower**, which the suite's generous
consensus timeouts can absorb.

Sizing still matters (see below); swap just stops a marginal box from taking the
whole queue down with it.

### Why `ccx33` (8 vCPU / 32 GB) is rejected

It passed once, then failed **twice in two different ways** under repeat runs —
both resource exhaustion, neither a real defect:

- **Memory.** p90 **99%**, peak **100%** of 32 GB, with **no swap**. The kernel
  OOM-killer killed the Actions runner agent itself during `allow-spends`
  (`Out of memory: Killed process (node)`), the systemd unit went to `failed`, and
  the eight remaining matrix jobs hung in `queued` forever.
- **CPU.** Peak load **15.42 on 8 cores (193%)**; 16% of active samples exceeded
  1.0/core. Under that contention GL0's snapshot route returned **HTTP 503** on
  `/global-snapshots/latest/combined` and `spend` failed.

`ccx43` (16 vCPU / 64 GB) puts the same peaks at ~42% memory and ~96% load, and
only **0.7%** of samples exceeded load 16. `ccx53` would give more CPU margin but
roughly halves the saving.

## Why one runner per server

The E2E harness is not multi-tenant. `docker/bin/compose-runner.sh:174` creates a
fixed-name docker network `tessellation_common` on a fixed subnet (`NET_PREFIX`,
default `172.32.0.0/24`), with fixed container names (`gl0-0`, `gl1-0`, …) and
fixed host ports (9000–9412). Two concurrent jobs on one host collide on all four.

Packing more runners per box would need either a Docker daemon per runner in its
own network namespace (privileged DinD), or parameterizing the harness's network
name and container-name suffix. Both are real options for improving €/concurrency
— and both are extra moving parts in a suite that
`docker/docker-compose.test.yaml` already documents as timing-fragile under
multi-JVM contention. Neither is done here.

## Deploy

```sh
cd deploy/ci-runners/fixed/terraform

export TF_VAR_hcloud_token=...                        # existing project token is fine
export TF_VAR_team_ssh_keys='["ssh-ed25519 AAAA..."]'
export TF_VAR_allowed_ssh_cidrs='["203.0.113.0/24"]'  # fail-closed: empty = no SSH
# optional: export TF_VAR_runner_count=3 TF_VAR_runner_server_type=ccx43

terraform init
terraform apply
```

Then install and register the runners.

### Choose a scope first: `GITHUB_TARGET`

| Value | Scope | Requires |
|---|---|---|
| `Constellation-Labs/tessellation` | that repo only | **admin** on the repo — write/maintain is *not* enough |
| `Constellation-Labs` | every repo in the org | org admin |

Repo-level is tighter, but repository **admin** is a higher bar than most
maintainers have; if `registration-token` returns 403, that's why. Org-level is
the usual way in.

Because an org runner is reachable from every repo in the org, the script always
passes `--no-default-labels`, so each runner advertises **only**
`tessellation-e2e` — not `self-hosted`/`Linux`/`X64`. Without that, any workflow
anywhere in the org using `runs-on: self-hosted` could be scheduled onto a fleet
sized purely for tessellation E2E and would fail. Consider also placing org
runners in a **runner group scoped to `tessellation`** for defence in depth.

GitHub requires proof of authorization to join a machine, but **no long-lived
credential is needed** — pick either path below.

### Option A — no PAT (UI-copied registration tokens)

Use this when org policy blocks classic PATs, SAML SSO is in the way, or you'd
simply rather not mint one.

In the repo UI, go to **Settings → Actions → Runners → New self-hosted runner →
Linux**. The displayed `./config.sh --token AXXXX...` command contains a
registration token. Copy the value after `--token`. **Repeat once per runner** —
each token is single-use.

```sh
export GITHUB_TARGET=Constellation-Labs          # org-level; or Constellation-Labs/tessellation for repo-level
export REG_TOKENS="AXXXX...,BYYYY...,CZZZZ..."   # one per runner, in IP order

deploy/ci-runners/fixed/register-runners.sh \
  "$(terraform -chdir=deploy/ci-runners/fixed/terraform output -raw runner_ips_csv)"
```

Those tokens expire in ~1 hour, are single-use, and grant nothing beyond "join
this repository" — so there's no lasting credential to store or rotate. The
script validates the token count against the host count before touching any
server.

### Option B — classic PAT

More convenient for repeat runs, since the tokens are minted automatically.

```sh
export GITHUB_TARGET=Constellation-Labs          # org-level; or Constellation-Labs/tessellation for repo-level
export GITHUB_TOKEN=...          # classic PAT, `repo` scope

deploy/ci-runners/fixed/register-runners.sh \
  "$(terraform -chdir=deploy/ci-runners/fixed/terraform output -raw runner_ips_csv)"
```

The PAT stays on your machine — it's used only to mint the same short-lived
per-host registration tokens. The PAT itself never reaches the runners. If the
org enforces SAML SSO, authorize the token for it first (**Configure SSO** next
to the token in the Tokens (classic) list), or API calls return a confusing 404.

`REG_TOKENS` takes precedence over `GITHUB_TOKEN` if both are set.

---

`register-runners.sh` is idempotent: re-run it to roll out a hook change, bump
`RUNNER_VERSION`, or re-register. An existing runner is stopped and its *local*
config removed (`config.sh remove --local`, so the single-use registration token
isn't consumed), then re-registered with `--replace` under the same name — so
runners are replaced, never duplicated.

Verify at `https://github.com/<repo>/settings/actions/runners` — you should see
`ci-runner-1..N` idle with the `tessellation-e2e` label.

## Operations

```sh
# Runner service status / logs
ssh admin@<runner-ip> 'sudo /home/runner/actions-runner/svc.sh status'
ssh admin@<runner-ip> 'sudo journalctl -u "actions.runner.*" -f'

# What's running right now
ssh admin@<runner-ip> 'docker ps && df -h / && free -m'
```

**Scale the pool:** change `TF_VAR_runner_count`, `terraform apply`, then re-run
`register-runners.sh` with the new IP list. Scaling *down* — unregister the
doomed runner first so GitHub doesn't queue jobs onto a dead box:

```sh
ssh admin@<runner-ip> 'cd /home/runner/actions-runner && sudo ./svc.sh stop && sudo ./svc.sh uninstall'
# then reduce runner_count and apply
```

**Resize the boxes:** changing `runner_server_type` replaces the servers, so
re-run `register-runners.sh` afterwards.

**Rollback to GitHub-hosted runners:** set the repository variable
`E2E_RUNNER_LABEL` to `Ubuntu-22-64-core`. Effective on the next job, no PR.

## State hygiene

Persistent runners accumulate state, which is the main operational risk here (the
autoscaled variant gets a fresh box per job and has no equivalent problem). Two
hooks, installed by `register-runners.sh` and wired via the runner's `.env`,
handle it:

- **`hooks/job-started.sh`** — defensive pre-flight, for when a job dies hard and
  skips its completion hook: remove stale containers, drop a leftover
  `tessellation_common` network, delete root-owned `nodes/` from the workspace,
  and prune if disk is over 70%.
- **`hooks/job-completed.sh`** — teardown: force-remove all containers (the
  compose files set `restart: unless-stopped`, so a plain stop isn't enough), drop
  the network, prune volumes and dangling images, and full-prune above 80% disk.

Both run *after* the workflow's own log-collection steps, so diagnostics are
preserved. Both always `exit 0` — a cleanup error must never turn a green run red.

The root-owned-`nodes/` cleanup matters more than it looks: containers write node
data as root into the workspace, and `actions/checkout` can't unlink it as the
`runner` user, so without this a job fails before it starts. The hooks delete it
from inside a container, the same trick as the `clean-data` recipe in the
`justfile`.

## Right-sizing

`ccx43` is the floor, established by measurement — see
[Why `ccx33` is rejected](#why-ccx33-8-vcpu--32-gb-is-rejected). To re-measure
after any change to the topology or heap settings:

```sh
ssh admin@<runner-ip> 'docker stats --no-stream; free -m; uptime; nproc'
```

Watch memory first — it is the harder limit. Cloud-init now provisions 16 GB of
swap as a backstop, so an overshoot degrades to paging rather than an OOM kill, but
`free -m` showing swap in active use means the box is undersized. Load above
1.0/core is survivable (the workflow already sets generous consensus timeouts) but
shows up as HTTP 503s from GL0's snapshot routes before it shows up as anything
legible.

## Known risks

- **A wedged runner silently swallows jobs.** GitHub will keep assigning to a
  registered-but-broken runner until the workflow times out. Check the runners
  page if E2E jobs hang without logs.
- **An OOM can delete a runner from the fleet.** `svc.sh install` generates a unit
  with no `Restart=`, and the listener exits 0 on teardown, so one OOM leaves the
  unit `failed` and every later job queued forever. `register-runners.sh` installs
  a drop-in with `Restart=always` + `OOMPolicy=continue` to prevent this — do not
  remove it. Observed for real on 2026-08-03.
- **Disk exhaustion** is the classic persistent-runner failure and shows up as
  weird mid-test errors, not clean "no space" messages. The hooks guard at 70/80%;
  `ccx43` ships 360 GB. Peak observed disk use was 7%.
- **Idle capacity is pure waste** — real demand is ~198 job-hours/month against
  2,190 host-hours for a 3-box pool (~9% duty cycle). That's the structural
  argument for the autoscaled variant.
